#!/usr/bin/env bash
# Restores a Sofra backup made by ops/backup.sh into a database.
#
#   PGHOST=… PGUSER=… PGPASSWORD=… ops/restore.sh <file.dump> <target database> [--replace]
#
# The checksum beside the dump is checked first. The target must exist and be empty, unless --replace is given,
# which drops what the target holds first: stop the app before replacing the database it uses. After the restore
# the script prints Flyway's latest migration and a few row counts, to compare with what the restaurant expects.
# Rehearse it on a spare database every month (docs/OPERATIONS.md): a backup nobody has restored is a hope.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: ops/restore.sh <file.dump> <target database> [--replace]" >&2
  exit 64
fi
file="$1"
target="$2"
replace="${3:-}"

if [[ -f "$file.sha256" ]]; then
  # -c alone: GNU coreutils and busybox (the postgres:alpine image) both take it
  if ! (cd "$(dirname "$file")" && sha256sum -c "$(basename "$file").sha256" >/dev/null 2>&1); then
    echo "restore: $file does not match its checksum; it was changed or damaged after the backup" >&2
    exit 2
  fi
  echo "restore: checksum ok"
else
  echo "restore: no $file.sha256 beside the dump; refusing to restore an unchecked file" >&2
  exit 2
fi

tables="$(psql --dbname="$target" --no-align --tuples-only --command="SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
if [[ "$tables" != "0" && "$replace" != "--replace" ]]; then
  echo "restore: $target already has $tables tables; add --replace to overwrite it (stop the app first)" >&2
  exit 3
fi
options=(--no-owner --no-privileges --exit-on-error --single-transaction)
if [[ "$replace" == "--replace" ]]; then
  options+=(--clean --if-exists)
fi
pg_restore "${options[@]}" --dbname="$target" "$file"

psql --dbname="$target" --no-align --tuples-only --field-separator=' ' <<'SQL'
SELECT 'restore: schema version ' || version || ' (' || description || ')' FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;
SELECT 'restore: ' || (SELECT count(*) FROM orders) || ' orders, ' || (SELECT count(*) FROM bills) || ' bills, '
    || (SELECT count(*) FROM payments) || ' payments, ' || (SELECT count(*) FROM menu_items) || ' dishes';
SQL
