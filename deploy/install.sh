#!/usr/bin/env bash
# Sets up Sofra on this computer: writes the settings (.env, with fresh secrets) and the HTTPS front door
# (Caddyfile), builds or pulls the app, starts everything and prints the first manager's sign-in.
#
#   ./install.sh                     asks a few questions
#   ./install.sh --name "Lokanta" --domain sofra.example.com --email owner@example.com --yes
#   ./install.sh --name "Lokanta" --local 192.168.1.20 --yes
#
# Options:
#   --name NAME        the restaurant's name, shown on the sign-in screen and to guests
#   --domain HOST      a domain pointing at this computer: a free certificate from Let's Encrypt (ports 80/443 open)
#   --local ADDRESS    the restaurant's own network only (an IP like 192.168.1.20): a certificate from Caddy's local
#                      authority, which each tablet and phone trusts once (see docs/INSTALL.md)
#   --email ADDRESS    for Let's Encrypt's expiry notices (with --domain)
#   --time-zone ZONE   default Europe/Istanbul
#   --image IMAGE      a released image (ghcr.io/umutvarol/restaurant-order-processing:1.2.3) instead of building here
#   --yes              do not ask; use the options and defaults
#
# Running it again keeps the existing secrets and data: it only rewrites the Caddyfile and restarts.
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31mHata / Error: %s\033[0m\n' "$*" >&2; exit 1; }

name="" domain="" local_address="" email="" time_zone="Europe/Istanbul" image="" assume_yes=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) name="${2:?}"; shift 2 ;;
    --domain) domain="${2:?}"; shift 2 ;;
    --local) local_address="${2:?}"; shift 2 ;;
    --email) email="${2:?}"; shift 2 ;;
    --time-zone) time_zone="${2:?}"; shift 2 ;;
    --image) image="${2:?}"; shift 2 ;;
    --yes|-y) assume_yes=true; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) fail "unknown option $1 (see --help)" ;;
  esac
done

# no terminal to answer in (a script, a pipe): take the suggestions
[[ -t 0 ]] || assume_yes=true

# ---------------------------------------------------------------- what this computer needs
command -v docker >/dev/null || fail "Docker is not installed. https://docs.docker.com/engine/install/ (Linux) or Docker Desktop (Windows, macOS)."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is missing (the 'docker compose' command)."
docker info >/dev/null 2>&1 || fail "Docker is installed but not running, or this user may not use it (try sudo)."
command -v openssl >/dev/null || fail "openssl is needed to create the secrets."

ask() { # ask VAR "question" default
  local answer
  if $assume_yes; then printf -v "$1" '%s' "$3"; return; fi
  read -r -p "$2 [${3}]: " answer
  printf -v "$1" '%s' "${answer:-$3}"
}

# ---------------------------------------------------------------- questions (only what is not given)
if [[ -f .env ]]; then
  say "Mevcut kurulum bulundu (.env). Gizli anahtarlar ve veriler korunuyor."
  say "Existing installation found (.env): secrets and data are kept."
  set -a
  # shellcheck source=/dev/null
  source ./.env
  set +a
  name="${name:-${RESTAURANT_NAME:-}}"
  [[ -z "$domain" && -z "$local_address" ]] && { if [[ "${SOFRA_MODE:-}" == local ]]; then local_address="$SOFRA_DOMAIN"; else domain="${SOFRA_DOMAIN:-}"; fi; }
  email="${email:-${ACME_EMAIL:-}}"
  image="${image:-${SOFRA_IMAGE:-}}"
fi

echo "Her soruda Enter'a basmak önerilen cevabı seçer. / Press Enter to accept each suggestion."
[[ -n "$name" ]] || ask name "Restoranın adı / Restaurant name" "Restoranım"
# kept to what a name needs, so it goes into .env and sed safely
name="$(printf '%s' "$name" | tr -d '"$`\\|&\n' | cut -c1-60)"
if [[ -z "$domain" && -z "$local_address" ]]; then
  guess="$(hostname -I 2>/dev/null | awk '{print $1}')"
  echo "Sofra'ya nereden ulaşılacak? / Where will Sofra be reached?"
  echo "  1) Yalnızca restoranın ağından (tablet, telefon, kasa aynı Wi-Fi'da) / the restaurant's own network only"
  echo "  2) İnternetten, bir alan adıyla (misafir QR ödemeleri, uzaktan yönetim) / from the internet, with a domain"
  ask mode "Seçim / Choice" "1"
  if [[ "$mode" == 2 ]]; then
    ask domain "Alan adı (bu bilgisayarı gösteren) / Domain pointing at this computer" "sofra.example.com"
    ask email "Sertifika bildirimleri için e-posta (boş bırakılabilir) / Email for certificate notices (optional)" ""
  else
    ask local_address "Bu bilgisayarın ağdaki adresi / This computer's address on the network" "${guess:-192.168.1.20}"
  fi
fi
[[ -n "$domain" && -n "$local_address" ]] && fail "choose --domain or --local, not both"

if [[ -n "$domain" ]]; then
  mode=public site="$domain" tls_line=""
  [[ "$domain" =~ ^[A-Za-z0-9.-]+$ ]] || fail "'$domain' is not a domain name"
else
  mode=local site="$local_address" tls_line="tls internal"
  [[ "$local_address" =~ ^[A-Za-z0-9.:-]+$ ]] || fail "'$local_address' is not an address"
fi

# ---------------------------------------------------------------- settings and secrets
secret() { openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' '-_'; }
# the first manager's temporary password: readable, typed once, replaced at the first sign-in
# (finite input to every pipe stage: with pipefail, cutting off an endless /dev/urandom would abort the script)
readable() { openssl rand -base64 192 | LC_ALL=C tr -dc 'abcdefghjkmnpqrstuvwxyz23456789' | cut -c1-12 | sed 's/\(....\)\(....\)\(....\)/\1-\2-\3/'; }

if [[ ! -f .env ]]; then
  admin_password="$(readable)"
  umask 077
  cat > .env <<ENV
# Sofra settings, written by install.sh on $(date -u +%Y-%m-%d). Keep this file private and backed up
# (somewhere other than this computer): without JWT_SECRET and TOTP_ENCRYPTION_KEY a restored database is harder
# to use, and POSTGRES_PASSWORD opens the database.
RESTAURANT_NAME="${name//\"/}"
TIME_ZONE=${time_zone}
SOFRA_MODE=${mode}
SOFRA_DOMAIN=${site}
ACME_EMAIL=${email}
SOFRA_IMAGE=${image}

POSTGRES_PASSWORD=$(secret 24)
JWT_SECRET=$(secret 48)
TOTP_ENCRYPTION_KEY=$(openssl rand -base64 32 | tr -d '\n')
TOTP_REQUIRED_FOR_MANAGERS=true
# used only when the database has no accounts yet; change it at the first sign-in (Sofra asks you to)
ADMIN_PASSWORD=${admin_password}

BACKUP_HOUR=3
BACKUP_KEEP_DAYS=14

# Online (QR) payments: IYZICO with your merchant keys, or NONE
PAYMENT_PROVIDER=NONE
IYZICO_API_KEY=
IYZICO_SECRET_KEY=
# Legal documents: your ÖKC bridge and e-Arşiv integrator (see docs/INSTALL.md)
FISCAL_OKC_BRIDGE_URL=
FISCAL_OKC_API_KEY=
FISCAL_EARSIV_BASE_URL=
FISCAL_EARSIV_API_KEY=
ENV
  umask 022
else
  # keep secrets; update what the questions may have changed
  sed -i.bak -e "s|^RESTAURANT_NAME=.*|RESTAURANT_NAME=\"${name//\"/}\"|" -e "s|^SOFRA_MODE=.*|SOFRA_MODE=${mode}|" \
    -e "s|^SOFRA_DOMAIN=.*|SOFRA_DOMAIN=${site}|" -e "s|^ACME_EMAIL=.*|ACME_EMAIL=${email}|" -e "s|^SOFRA_IMAGE=.*|SOFRA_IMAGE=${image}|" .env
  rm -f .env.bak
fi
chmod 600 .env

global_email=""
[[ -n "$email" ]] && global_email="	email ${email}"
{
  cat Caddyfile.template
  # on the local network, http:// is the welcome page that gives new devices the certificate
  [[ "$mode" == local ]] && cat Caddyfile.local-http
} | sed -e "s|{DOMAIN}|${site}|g" -e "s|{TLS}|${tls_line}|" \
  | awk -v email="$global_email" '{print} /admin off/ && email != "" {print email}' > Caddyfile
mkdir -p backups

# ---------------------------------------------------------------- start
if [[ -n "$image" ]]; then
  # an image already on this computer (docker load from a USB stick, say) is used as it is
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    say "Uygulama indiriliyor / Pulling the app ($image)…"
    docker compose pull app
  fi
else
  say "Uygulama derleniyor; ilk seferde birkaç dakika sürer / Building the app; the first time takes a few minutes…"
  docker compose build app
fi
docker compose up -d
# the Caddyfile is mounted, not built in: restarting picks up a changed one
docker compose restart caddy >/dev/null

say "Sofra başlatılıyor / Starting Sofra…"
for _ in $(seq 1 90); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q app)" 2>/dev/null || true)"
  [[ "$status" == healthy ]] && break
  sleep 2
done
[[ "$status" == healthy ]] || fail "the app did not start; see: docker compose logs app"
# the first manager exists now; the temporary password is shown below once and has no reason to stay on disk
admin_password="$(sed -n 's/^ADMIN_PASSWORD=//p' .env)"
if [[ -n "$admin_password" ]]; then
  sed -i.bak 's|^ADMIN_PASSWORD=.*|ADMIN_PASSWORD=|' .env && rm -f .env.bak
fi

if [[ "$mode" == local ]]; then
  # the local authority's certificate, to install once on every tablet and phone (docs/INSTALL.md)
  for _ in $(seq 1 30); do
    docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./sofra-local-ca.crt >/dev/null 2>&1 && break
    curl -ks "https://${site}/actuator/health" >/dev/null 2>&1 || true
    sleep 1
  done
fi

echo
say "Sofra hazır / Sofra is ready:  https://${site}"
if [[ -n "$admin_password" ]]; then
  echo "  İlk yönetici / First manager:   kullanıcı adı / username  admin"
  echo "                                  geçici şifre / temporary password  ${admin_password}"
  echo "  İlk girişte kendi şifrenizi belirleyeceksiniz; ardından Yönetim > Kurulum ekranındaki adımları izleyin."
  echo "  You choose your own password at the first sign-in; then follow the steps on Manage > Setup."
fi
if [[ "$mode" == local ]]; then
  echo
  say "  Tablet, telefon ve kasada şu adresi açın / On every tablet, phone and till open:  http://${site}"
  echo "  Açılan sayfadaki iki adımı izleyin (sertifika + uygulama olarak ekle). / Follow the two steps on that page."
fi
echo "  Yedekler / Backups: $(pwd)/backups (her gece / nightly ${BACKUP_HOUR:-3}:00 UTC). Başka bir yere de kopyalayın / copy them elsewhere too."
