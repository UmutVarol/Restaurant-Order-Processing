# Running Sofra: monitoring, alerts and backups

What a restaurant (or whoever looks after its server) needs to keep Sofra healthy during service, and to get it
back after a bad night. Each alert in [`ops/prometheus/alerts.yml`](../ops/prometheus/alerts.yml) links to its section
below.

## What to watch

| Where | What it says |
|---|---|
| `GET /actuator/health/readiness` | Whether the app can take traffic (database reachable). Load balancers and Docker use this one. |
| `GET /actuator/health` | `UP`, `DEGRADED` or `DOWN`. A manager's token also shows the parts: `fiscalHealthIndicator`, `printingHealthIndicator`, `onlinePaymentsHealthIndicator`, with counts and ages. Anonymous callers see only the overall status. |
| `GET /actuator/prometheus` | Metrics for Prometheus, with a manager's token. |

`DEGRADED` means the restaurant is still serving but something is failing quietly: a legal document is not issued,
tickets are not printing, or online payments are not settling. It answers HTTP 200 and does **not** make the app
unready, so a stuck printer never takes the till offline.

The metrics behind it, read from the database at each scrape:

| Metric | Meaning |
|---|---|
| `sofra_fiscal_unissued`, `sofra_fiscal_unissued_oldest_seconds` | ÖKC receipts / e-Arşiv invoices pending or failed, and the age of the oldest |
| `sofra_print_failed`, `sofra_print_queued_oldest_seconds` | tickets that failed for good in the last hour; age of the oldest still queued |
| `sofra_payments_pending`, `sofra_payments_pending_oldest_seconds` | online payments not yet finished, and the oldest |
| `sofra_payments_suspicious_total{reason}` | forged or odd payment callbacks, by reason |

The tolerances before `DEGRADED` can be changed with `restaurant.operations.fiscal-tolerated` (default 15 minutes),
`restaurant.operations.print-queue-tolerated` (2 minutes) and `restaurant.operations.payment-tolerated` (40 minutes).

### Scraping

```yaml
scrape_configs:
  - job_name: sofra
    metrics_path: /actuator/prometheus
    authorization: { credentials_file: /etc/prometheus/sofra-token }  # a manager account kept for monitoring
    static_configs: [{ targets: ["sofra.example.internal:8080"] }]
rule_files: [/etc/prometheus/sofra-alerts.yml]                         # ops/prometheus/alerts.yml
```

The alert rules are checked and unit-tested in CI (`promtool check rules`, `promtool test rules`), and a test in the
app fails if a rule names a metric the app does not publish.

## Alerts

### SofraDown

Prometheus cannot reach the app for two minutes. Check the container (`docker compose ps`, `docker compose logs app`)
and the database. The till keeps taking orders offline and sends them when the app is back (see the README), so
customers can still be served; card and cash payments work at the terminal as usual.

### SofraServerErrors

More than 2% of requests fail with a server error. Look at `docker compose logs app` for the stack traces. A full disk on the database host and
a database restart are the usual causes.

### SofraLegalDocumentsNotIssued

A paid bill has had no ÖKC receipt or e-Arşiv invoice for more than fifteen minutes. **This is a legal problem, not a
technical one**: the customer must get a document. Sofra retries every minute on its own.

1. Open the bill on the cashier screen (or `GET /api/v1/fiscal/documents`) and read the error on the failed document.
2. Fix the cause: ÖKC device off or offline, e-Arşiv provider credentials expired, provider down.
3. Retry from the bill (or `POST /api/v1/fiscal/documents/{id}/retry`).
4. If the device or provider cannot come back during service, issue the receipt by hand on the cash register as the
   law requires and note the bill number, so it is not issued twice when Sofra's retry succeeds later.

### SofraTicketsNotPrinting

A kitchen or bar ticket has failed for good, or nothing has printed for two minutes. **Tell the kitchen at once**:
orders may be waiting that nobody has seen. The kitchen screen still shows every order.

1. Check the printer: paper, power, network cable, IP address unchanged (*Settings → Printers → Test*).
2. Reprint what was missed from the order (`POST /api/v1/orders/{id}/reprint`).

### SofraOnlinePaymentsStuck

An online (QR) payment has been unfinished for over 40 minutes. Sofra asks the provider about unfinished payments every
minute and gives them up after 30, so this means reconciliation is not reaching the provider. Check the provider's
status page and the app logs for `payment` errors; compare the payment in the provider's merchant panel before
closing the bill by hand. Never mark it paid on the guest's word alone. See [PAYMENTS.md](PAYMENTS.md).

### SofraSuspiciousPayments

More than five suspicious payment events in fifteen minutes: forged provider callbacks, amounts that do not match,
someone spinning payment links. The payments were refused, so no money was lost, but someone may be probing the
system. The `reason` label says which kind; the audit log entries carry the IP addresses. Block them at the
firewall if they keep coming, and tell the payment provider.

### SofraBackupMissing

No verified backup for more than 26 hours. Run one now (`docker compose --profile backup run --rm --entrypoint
sofra-backup backup`, or `ops/backup.sh` on the host) and read its error: usually a full disk, a changed password, or the
database not reachable.

## Push notifications

Staff turn notifications on per device in **Settings › Notifications**: new orders (kitchen), ready plates and guests
calling (waiters). They are Web Push (RFC 8291 encryption, RFC 8292 VAPID), so they work in Chrome, Edge and Firefox,
and on an iPhone or iPad once Sofra is added to the home screen (iOS 16.4 or later).

- **Keys.** The server makes its VAPID key pair on first use and keeps it in the database (`push_keys`; the private
  key sealed with `TOTP_ENCRYPTION_KEY` when it is set). To manage them yourself, set `PUSH_VAPID_PUBLIC_KEY` and
  `PUSH_VAPID_PRIVATE_KEY` (raw P-256 keys, base64url). Changing them makes every device subscribe again.
- **Contact.** `PUSH_CONTACT` (default `https://sofra.app`) is the address push services see in the signature; set it
  to a page or `mailto:` of whoever runs the server.
- **Network.** The server must reach the browsers' push services over HTTPS: `fcm.googleapis.com`,
  `updates.push.services.mozilla.com`, `*.push.apple.com`, `*.notify.windows.com`. Sofra sends to no other address.
- **Clean-up.** A device the push service no longer knows (404, 410) is dropped at once; one that fails five times in a
  row is dropped too, and has to be turned on again.
- **Store apps.** Inside the Android and iOS apps (Capacitor, `mobile/`) browsers' Web Push does not exist; the apps
  use Firebase Cloud Messaging instead. To turn it on:
  1. Create a Firebase project; add the Android app (`app.sofra.restaurant`) and put its `google-services.json` in
     `mobile/android/app/`; add the iOS app and put `GoogleService-Info.plist` in `mobile/ios/App/App/`.
  2. For iOS, upload an APNs key (Apple Developer › Keys) in Firebase › Project settings › Cloud Messaging.
  3. Give the server the project's service account: `FCM_SERVICE_ACCOUNT` holds its JSON, or the path of the file
     (Firebase › Project settings › Service accounts › Generate new private key). Keep it as secret as a password.
  4. The server must reach `oauth2.googleapis.com` and `fcm.googleapis.com`.
  Without it the apps say notifications are not set up on this server, and browsers keep working as before.

## Backups

[`ops/backup.sh`](../ops/backup.sh) makes a compressed `pg_dump`, checks that `pg_restore` can read all of it and
that it is Sofra's database, writes a SHA-256 checksum beside it, and deletes dumps older than `KEEP_DAYS` (14).
Only then does it count: it updates `sofra_backup_last_success_timestamp_seconds` in `BACKUP_METRICS_FILE`, if set,
for the node exporter's textfile collector.

With Docker Compose, start the nightly service (03:00 UTC unless `BACKUP_HOUR` is set):

```sh
docker compose --profile backup up -d
docker compose --profile backup run --rm --entrypoint sofra-backup backup   # one backup now
```

Dumps land in the `backups` volume. **Copy them off this machine every day** (rsync, restic, object storage): a backup
on the same disk dies with it. Keep the TOTP encryption key (`TOTP_ENCRYPTION_KEY`) with them, safely apart: without it
managers' two-factor secrets in a restored database cannot be read (see [SECURITY.md](../SECURITY.md)).

## Restoring

[`ops/restore.sh`](../ops/restore.sh) checks the checksum first and refuses a file without one or a file that changed.
It restores into an **empty** database unless `--replace` is given, all in one transaction, then prints the schema
version and row counts to compare with what the restaurant expects.

```sh
# into a spare database, to rehearse
createdb sofra_check
PGHOST=… PGUSER=sofra PGPASSWORD=… ops/restore.sh backups/sofra-sofra-20261110T030000Z.dump sofra_check

# replacing the live database after a loss: stop the app first
docker compose stop app
PGHOST=… ops/restore.sh backups/sofra-sofra-….dump sofra --replace
docker compose start app
```

**Rehearse a restore once a month** into a spare database and check the counts. A backup nobody has restored is only
a hope. The scripts were tested end to end against PostgreSQL 16: the restored database had the same row counts and
the app started on it.
