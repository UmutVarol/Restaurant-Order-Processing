#!/usr/bin/env bash
# The only manager forgot their password: gives the account a new temporary password, switches it back on and ends
# its sessions. Run on the server, so having this computer is what proves who you are.
#
#   ./reset-admin.sh            the account "admin"
#   ./reset-admin.sh elif       another account
set -euo pipefail
cd "$(dirname "$0")"
user="${1:-admin}"
[[ "$user" =~ ^[a-z0-9._-]{3,50}$ ]] || { echo "not a username: $user" >&2; exit 64; }

password="$(openssl rand -base64 192 | LC_ALL=C tr -dc 'abcdefghjkmnpqrstuvwxyz23456789' | cut -c1-12 | sed 's/\(....\)\(....\)\(....\)/\1-\2-\3/')"
# bcrypt in the database (pgcrypto), in the format the app reads ({bcrypt} prefix); the password itself is never stored
updated="$(docker compose exec -T postgres psql -U sofra -d sofra -v ON_ERROR_STOP=1 -qtA \
  -v user="$user" -v password="$password" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE app_users
   SET password_hash = '{bcrypt}' || crypt(:'password', gen_salt('bf', 10)),
       must_change_password = TRUE, enabled = TRUE, session_version = session_version + 1
 WHERE lower(username) = lower(:'user')
RETURNING username;
SQL
)"
[[ -n "$updated" ]] || { echo "no account named $user" >&2; exit 1; }
echo "Hesap / account:  $updated"
echo "Geçici şifre / temporary password:  $password"
echo "İlk girişte yeni şifre istenir. Birkaç saniye içinde geçerli olur. / Asked to change at sign-in; works within seconds."
