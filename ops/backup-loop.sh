#!/usr/bin/env bash
# Runs ops/backup.sh once a day at BACKUP_HOUR (UTC, default 3), for the "backup" service in docker-compose.yml.
# A failed backup is reported and retried the next day; the SofraBackupMissing alert catches a run of failures.
set -uo pipefail

hour="${BACKUP_HOUR:-3}"
while true; do
  now=$(date -u +%s)
  today=$(date -u +%Y-%m-%d)
  next=$(date -u -d "$today $hour:00:00" +%s 2>/dev/null || date -u -D "%Y-%m-%d %H:%M:%S" -d "$today $hour:00:00" +%s)
  if (( next <= now )); then
    next=$(( next + 86400 ))
  fi
  echo "backup-loop: next backup in $(( (next - now) / 60 )) minutes"
  sleep $(( next - now ))
  bash /usr/local/bin/sofra-backup || echo "backup-loop: backup failed; trying again tomorrow" >&2
done
