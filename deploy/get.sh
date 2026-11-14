#!/usr/bin/env bash
# Sofra in one line, on a Linux computer (Ubuntu, Debian, Raspberry Pi OS, Fedora…):
#
#   curl -fsSL https://raw.githubusercontent.com/UmutVarol/Restaurant-Order-Processing/main/deploy/get.sh | bash
#
# Installs Docker and Git if they are missing, downloads Sofra to ~/sofra (or updates it there) and runs the
# installer, which asks two questions; pressing Enter accepts the suggestion. Options are passed on to install.sh:
#   curl -fsSL …/get.sh | bash -s -- --name "Lokanta" --local 192.168.1.20 --yes
# Read it first if you like: it is this file, nothing else is downloaded except Docker's own installer.
set -euo pipefail

REPO="${SOFRA_REPO:-https://github.com/UmutVarol/Restaurant-Order-Processing.git}"
BRANCH="${SOFRA_BRANCH:-main}"
DIR="${SOFRA_DIR:-$HOME/sofra}"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31mHata / Error: %s\033[0m\n' "$*" >&2; exit 1; }

[[ "$(uname -s)" == Linux ]] || fail "this one-line setup is for Linux. On Windows or macOS install Docker Desktop and Git, then: git clone $REPO sofra && cd sofra/deploy && ./install.sh"

SUDO=""
if [[ $EUID -ne 0 ]]; then
  command -v sudo >/dev/null || fail "run as root, or install sudo"
  SUDO="sudo"
fi

if ! command -v git >/dev/null; then
  say "Git kuruluyor / Installing Git…"
  if command -v apt-get >/dev/null; then $SUDO apt-get update -qq && $SUDO apt-get install -y -qq git
  elif command -v dnf >/dev/null; then $SUDO dnf install -y -q git
  elif command -v pacman >/dev/null; then $SUDO pacman -Sy --noconfirm git
  else fail "install Git, then run this again"; fi
fi

if ! command -v docker >/dev/null; then
  say "Docker kuruluyor (birkaç dakika) / Installing Docker (a few minutes)…"
  curl -fsSL https://get.docker.com | $SUDO sh
  $SUDO systemctl enable --now docker >/dev/null 2>&1 || true
fi
# use Docker through sudo until this user may use it directly (after logging in again)
DOCKER_SUDO=""
docker info >/dev/null 2>&1 || DOCKER_SUDO="$SUDO"
if [[ -n "$DOCKER_SUDO" && $EUID -ne 0 ]]; then
  $SUDO usermod -aG docker "$USER" 2>/dev/null || true
fi

if [[ -d "$DIR/.git" ]]; then
  say "Sofra güncelleniyor / Updating Sofra in $DIR…"
  git -C "$DIR" pull --ff-only
else
  say "Sofra indiriliyor / Downloading Sofra to $DIR…"
  git clone --depth 1 --branch "$BRANCH" "$REPO" "$DIR"
fi

cd "$DIR/deploy"
# piped into bash, the questions read from the terminal rather than from the download
if [[ -t 1 && -r /dev/tty ]]; then
  $DOCKER_SUDO ./install.sh "$@" < /dev/tty
else
  $DOCKER_SUDO ./install.sh --yes "$@"
fi
