#!/usr/bin/env bash
# Updates Sofra: a verified backup first, then the new version, then a health check. Database changes run by
# themselves when the app starts (Flyway); the backup is the way back if anything goes wrong.
#
#   ./update.sh                 rebuild from the source here (after `git pull`)
#   ./update.sh 1.3.0           switch to a released image, ghcr.io/umutvarol/restaurant-order-processing:1.3.0
set -euo pipefail
cd "$(dirname "$0")"
[[ -f .env ]] || { echo "No .env here: run ./install.sh first." >&2; exit 1; }

if [[ $# -ge 1 ]]; then
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: ./update.sh [x.y.z]" >&2; exit 64; }
  image="ghcr.io/umutvarol/restaurant-order-processing:$1"
  sed -i.bak "s|^SOFRA_IMAGE=.*|SOFRA_IMAGE=${image}|" .env && rm -f .env.bak
fi

echo "1/3 Backup before the update…"
docker compose run --rm --no-deps --entrypoint sofra-backup backup

set -a
# shellcheck source=/dev/null
source ./.env
set +a
echo "2/3 New version…"
if [[ -n "${SOFRA_IMAGE:-}" ]]; then
  # a registry image is pulled; one loaded onto this computer by hand (docker load) is used as it is
  docker compose pull app || docker image inspect "$SOFRA_IMAGE" >/dev/null
else
  docker compose build app
fi
docker compose up -d

echo "3/3 Waiting for Sofra…"
for _ in $(seq 1 90); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q app)" 2>/dev/null || true)"
  [[ "$status" == healthy ]] && break
  sleep 2
done
if [[ "$status" != healthy ]]; then
  echo "Sofra did not come back. See 'docker compose logs app'. To go back: restore the backup just made" >&2
  echo "(docs/OPERATIONS.md, Restoring) and run the previous version (./update.sh <previous version>)." >&2
  exit 1
fi
echo "Updated and running: https://${SOFRA_DOMAIN}"
