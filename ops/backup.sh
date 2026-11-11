#!/usr/bin/env bash
# Backs up Sofra's PostgreSQL database: one compressed dump a run, checked before it counts, old ones pruned.
#
#   PGHOST=… PGUSER=… PGPASSWORD=… PGDATABASE=sofra ops/backup.sh [backup dir]
#
# Connection settings are the standard libpq variables (PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE).
# BACKUP_DIR (or the first argument) is where dumps go; KEEP_DAYS (default 14) how long they stay.
# BACKUP_METRICS_FILE, if set, gets sofra_backup_last_success_timestamp_seconds after each verified backup, for the
# node exporter's textfile collector and the SofraBackupMissing alert (ops/prometheus/alerts.yml).
#
# A dump counts only once pg_restore can read its whole table of contents and it holds Flyway's history table;
# a checksum file sits beside it. Exits non-zero on any failure, so cron or a supervisor can see it.
set -euo pipefail

dir="${1:-${BACKUP_DIR:-./backups}}"
keep_days="${KEEP_DAYS:-14}"
database="${PGDATABASE:-sofra}"
mkdir -p "$dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$dir/sofra-$database-$stamp.dump"
partial="$file.partial"

trap 'rm -f -- "$partial"' EXIT
# custom format: compressed, and pg_restore can restore one table out of it if ever needed
pg_dump --format=custom --compress=6 --no-owner --no-privileges --dbname="$database" --file="$partial"

# the dump must be readable and must be Sofra's: pg_restore lists it, and Flyway's history is inside
contents="$(pg_restore --list "$partial")"
if ! grep -q "TABLE DATA public flyway_schema_history" <<<"$contents"; then
  echo "backup: $partial does not hold flyway_schema_history; not a Sofra database?" >&2
  exit 2
fi
mv -- "$partial" "$file"
trap - EXIT
(cd "$dir" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")
echo "backup: wrote $file ($(du -h "$file" | cut -f1), $(grep -c "TABLE DATA" <<<"$contents") tables)"

# prune: only this script's own files, only older than KEEP_DAYS, never the one just written
find "$dir" -maxdepth 1 -type f \( -name "sofra-$database-*.dump" -o -name "sofra-$database-*.dump.sha256" \) \
  -mtime +"$keep_days" ! -name "$(basename "$file")*" -print -delete | sed 's/^/backup: pruned /'

if [[ -n "${BACKUP_METRICS_FILE:-}" ]]; then
  printf '# HELP sofra_backup_last_success_timestamp_seconds When the last verified backup finished\n# TYPE sofra_backup_last_success_timestamp_seconds gauge\nsofra_backup_last_success_timestamp_seconds %s\n' \
    "$(date +%s)" > "$BACKUP_METRICS_FILE.tmp" && mv -- "$BACKUP_METRICS_FILE.tmp" "$BACKUP_METRICS_FILE"
fi
