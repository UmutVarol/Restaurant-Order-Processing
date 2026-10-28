# Security

## Reporting a vulnerability

Please report security problems privately through
[GitHub's private vulnerability reporting](https://github.com/UmutVarol/Restaurant-Order-Processing/security/advisories/new)
rather than in a public issue. Include the steps to reproduce and the version or commit you tested. You should
get an answer within a week.

## How Sofra is protected

The controls below follow the OWASP Cheat Sheet Series (Authentication, JSON Web Token, REST Security, Denial of
Service, HTTP Headers, Logging and Docker Security) and RFC 8725 (JWT Best Current Practices). Each one is
covered by an automated test.

### Authentication and sessions

| Control | Details |
| --- | --- |
| Password storage | Spring Security's delegating encoder (bcrypt by default) |
| Generic sign-in errors | Wrong password, unknown user and disabled account all return the same `401 BAD_CREDENTIALS`, and an unknown user is checked against a dummy hash so response times match |
| Account lockout | 5 failures within 15 minutes lock the account for 30 s. Each further lockout doubles, up to 15 minutes. Counted per username (whether or not it exists), so spreading guesses over many IP addresses does not help, and short lockouts cannot be used to keep a real user out for long |
| Sign-in rate limit | 60 attempts per minute per client address, on top of the general API limit |
| Access tokens | HS256 only (the algorithm is fixed by the server, never taken from the token), explicitly typed `at+jwt`. `iss`, `aud`, `exp` and `jti` are all required and checked, with 30 s of clock skew allowed |
| Sign-out | `POST /api/v1/auth/logout` puts the token's `jti` on a denylist until it would have expired |
| Signing key | Must be at least 256 bits. With demo mode off, the app refuses to start on the development key that ships in `application.yml` |
| Authorisation | Role checks on every endpoint (`ADMIN`, `WAITER`, `KITCHEN`). Anything not explicitly allowed is denied |
| Staff PINs | 4 to 6 digits, bcrypt-hashed, runs and repeats (`1234`, `1111`, `1212`) refused, and setting one needs the password. A PIN works only on a till a manager registered: the till holds a 256-bit device token (stored as SHA-256, revocable at any time) sent as `X-Device-Token` |
| PIN guessing | Failed PINs count against their own lockout key, so a PIN attack cannot lock out the password; after three lockouts in a row the PIN is cleared and must be set again with the password. The PIN sign-in shares the 60-per-minute sign-in budget |
| How a session signed in | Tokens carry an RFC 8176 `amr` claim (`pwd` or `pin`). Step-up actions refuse PIN sessions, so four digits watched over a shoulder never move money or register a till |
| Idle tills | A registered till returns to the staff list after a few idle minutes (configurable per till; kitchen screens stay signed in) |

### Abuse and availability

| Control | Details |
| --- | --- |
| API rate limit | Token bucket ([Bucket4j](https://github.com/bucket4j/bucket4j)), 600 requests per minute per client address. Over budget: `429` with `Retry-After` |
| Public endpoints | What needs no sign-in (guest payment pages, provider callbacks and notifications, browser reports) has its own budget of 120 requests per minute per address |
| Request size | API bodies over 64 KB get `413`. A declared `Content-Length` is checked before reading, and chunked bodies are cut off while reading |
| Slow clients | Tomcat drops connections that do not send their request within 10 s, and idle keep-alive connections after 20 s |
| Live connections | At most 10 event streams per user and 500 in total. They are closed on shutdown, so graceful shutdown is not held up |
| Timeouts | 15 s per transaction, 10 s per query, 5 s to get a pooled database connection. The web client abandons requests after 15 s |
| Bounded memory | Rate-limit buckets, login counters and the token denylist live in size-capped, self-expiring [Caffeine](https://github.com/ben-manes/caffeine) caches |
| Errors | RFC 9457 problem details with a stable `code`. No stack traces or exception messages are ever sent to the client |

### Browser

| Control | Details |
|---|---|
| Content Security Policy | Scripts, styles, fonts and connections from the app's own origin only: no third party at all, no inline script or style, no `eval`, objects or framing |
| Trusted Types | `require-trusted-types-for 'script'` with no policies allowed. The client builds the DOM from elements and text nodes (icons included), never from HTML strings, so the browser itself blocks any attempt to inject markup or script through `innerHTML` and similar sinks: DOM-based XSS and payment-page skimmers fail even if a bug let one in |
| Violation reports | Browsers report CSP violations to `/api/v1/csp-reports` (`report-uri` and the Reporting API). Each is logged to `security.csp` and counted in `sofra.security.csp_violation`, so a tampered page raises an alert. This is the change and tamper detection PCI DSS v4 asks for on pages that lead to a payment page (6.4.3, 11.6.1) |
| Self-hosted fonts | Inter and Fraunces are served by the app ([Fontsource](https://fontsource.org) builds, SIL OFL), so no third party learns who opens the app and nothing outside the origin is allowed |
| Other headers | `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy` (camera, microphone, geolocation, payment and similar off), `Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy: same-origin`, `Cache-Control: no-store`, and HSTS over HTTPS. CORS is not enabled |
| API docs | Swagger UI needs inline styles, so it gets its own looser policy. Turn it off in production with `SPRINGDOC_SWAGGER_UI_ENABLED=false` |
| Security contact | [`/.well-known/security.txt`](src/main/resources/static/.well-known/security.txt) (RFC 9116) |

### Payments

| Control | Details |
|---|---|
| Card data | Cards are only ever entered on the payment provider's hosted page (iyzico Checkout Form, 3-D Secure). Sofra never sees card numbers |
| Provider answers | The guest's return to the callback is never trusted. The result is fetched server to server, its HMAC-SHA256 signature checked in constant time, and its conversation id, checkout token and amount must match what Sofra started |
| Request signing | Calls to iyzico are signed with `IYZWSv2` (HMAC-SHA256 over a random key, path and body) |
| Payment links | 256-bit random tokens, stored only as SHA-256 hashes, valid for 2 hours and only while the bill is open. The guest page shows items and totals, nothing about staff or other guests |
| Fraud review | A payment iyzico holds for fraud review (`fraudStatus` 0) is neither confirmed nor given up until iyzico decides; one it rejects (`-1`) is failed. Answers in another currency than TRY are refused |
| Double charging | Bills are changed under a row lock; payments taken at the till carry an `Idempotency-Key`, so a retried request returns the payment already taken |
| Holding a bill hostage | A payment link holds at most one unfinished checkout, and a new one is refused for a minute after the last, so whoever has the QR code cannot tie up more than one share. Staff can release an abandoned checkout; the provider is asked first, so a payment that did go through is kept |
| Tokens in URLs | The provider's return address carries no link token (it would be seen by the provider and could end up in logs). The guest's phone remembers which link it came from |
| Lost returns | Unfinished checkouts are checked with iyzico after 2 minutes, and iyzico's notifications (webhooks) trigger the same check, so a guest who paid and closed the browser is still recorded. Notification bodies are never trusted, only the server-to-server answer, so a forged one does nothing |
| Refunds sent once | A refund is claimed in the database (an atomic conditional update) before it is sent to the provider, so the immediate attempt, the retry job, or a second server can never send it twice |
| Step-up authentication | Refunds and closing the day need a sign-in from the last 5 minutes (OWASP Transaction Authorization). Otherwise the API answers `403 REAUTH_REQUIRED` and the till asks for the password again, under the usual lockout rules |
| Secrets | iyzico keys come from the environment and are masked whenever the settings are printed |
| Test provider | The simulated provider marks payments as paid without charging anything, so the app refuses to start with it when demo mode is off unless `restaurant.payments.allow-simulated` is set |
| Roles | Waiters and managers take payments and release unfinished ones; only managers refund and close the day |

#### Threat model

| Threat | How it is stopped |
|---|---|
| Faking a successful payment (forged callback or webhook, replayed token) | Results only come from iyzico, server to server, signed and bound to our conversation id, token, amount and currency |
| Paying less than the bill | The amount charged must equal what Sofra asked for; a mismatch is failed and raised as `amount_mismatch` |
| Card skimming script on the guest page | Same-origin-only CSP, Trusted Types, no third-party scripts or fonts, violation reports |
| Guessing payment links | 256 bits of randomness, hashed at rest, expiring, tighter rate limit on public endpoints |
| Charging twice | Row lock per bill, idempotency keys, one checkout per link, refunds claimed before sending |
| Insider refunds or a till left signed in | Manager role, fresh sign-in within 5 minutes, every refund in the `payment.audit` log and counted in `sofra.refunds` |
| Provider down or slow | Calls outside database transactions with 5 s / 20 s timeouts, retries for refunds, checks for unfinished checkouts |


### Tables, guests and printers

| Control | Details |
|---|---|
| Voids, comps and discounts | A waiter needs a manager to approve with their PIN at the till (except voiding before the kitchen starts); a wrong PIN counts against that manager's PIN lockout. Each change is repriced like a new order and refused (`ADJUSTMENT_BELOW_PAID`) when more has been paid, or is being paid online, than the bill would come to, so a paid bill is never quietly made cheaper. Every change is kept on the order and written to `order.audit` with who asked and who approved |
| Moving and merging tables | A seating only moves to a free table; a bill that has taken any money is never merged into another, and the emptied bill is voided so its QR payment links stop working. Both bills are locked in id order |
| Table QR codes | 256-bit tokens stored as SHA-256 hashes, replaceable per table at any time; a code that has been replaced opens nothing |
| Guest carts | Never reach the kitchen on their own: a waiter accepts each one, and it is then placed and priced exactly like an order from the till. Smaller limits than the till (20 lines, 20 each), at most five open requests per table, 15 s between carts and 30 s between calls, repeated calls fold into the one already waiting, request ids are random UUIDs bound to the table's token, and the public rate limit applies. Everything is written to `guest.audit` |
| Printers (SSRF) | The server connects to printer addresses staff type in, so only private-network addresses are allowed (RFC 1918, CGNAT, loopback, IPv6 unique-local; never link-local, so not the cloud metadata service) and only ports 9100–9109 by default. The address is checked when a printer is saved and again, on the resolved IP, before every connection (DNS rebinding). Connections have connect and write timeouts and run outside database transactions |

#### Threat model: tills and tables

| Threat | How it is stopped |
|---|---|
| A waiter giving away food or discounts | Manager PIN approval, audit trail with requester and approver, metrics `sofra.orders.adjusted` |
| A stolen or photographed PIN | Works only on a registered till, never for refunds or closing the day, lockout and clearing after repeated failures |
| A lost tablet | Revoke the device in Settings: PIN sign-in stops on it at once |
| Spam orders from a photographed QR code | Waiter acceptance, per-table limits and pauses, public rate limit, replace the code |
| Using the printer setting to probe the network or cloud metadata | Private addresses and printer ports only, re-checked on the resolved address |

### Audit

Payments, refunds, payment links, released checkouts and day closings are written to the `payment.audit` logger,
together with anything suspicious: unknown checkouts, unverifiable provider answers, amount mismatches and repeated
checkouts. They are counted in `sofra.payments`, `sofra.refunds` and `sofra.payments.suspicious{reason}`; alert on
the last one. Route `payment.audit` and `security.audit` to append-only storage in production.

Voids, comps, discounts, table moves and merges go to `order.audit`; guest carts, calls and QR codes to
`guest.audit`.

Sign-ins (password and PIN), failures, lockouts, sign-outs, PIN changes and till registrations are written to the
`security.audit` logger. User-supplied values in
these entries are stripped of control characters so they cannot forge log lines. The events are also counted in
Micrometer as `sofra.security.login{outcome}`, `sofra.security.logout` and `sofra.security.rate_limited`, which
can be scraped by Prometheus at `/actuator/prometheus` (admin only).

### Supply chain and deployment

- **CodeQL** (`security-extended` queries) scans the Java and JavaScript code on every push and weekly.
- **Dependabot** opens weekly updates for Maven, npm, GitHub Actions and the Docker base images.
- **Dependency review** blocks pull requests that add dependencies with known high or critical vulnerabilities.
- **Trivy** scans the Docker image in CI and fails the build on fixable critical vulnerabilities.
- **OWASP ZAP** runs a baseline scan (passive DAST) against the running container in CI, and fails the build if
  the security headers it checks go missing. The HTML report is kept as a build artifact.
- **Gitleaks** scans the whole git history for committed secrets on every push. The only allowed matches are the
  throwaway keys listed in [`.gitleaks.toml`](.gitleaks.toml).
- **The container** runs as a non-root user and exits on out-of-memory errors. `docker-compose.yml` adds a
  read-only root filesystem, drops all Linux capabilities, sets `no-new-privileges`, and caps memory, CPU and
  process counts. The database is not published to the host.

## Running in production

1. Generate a signing key and keep it out of the repository: `export JWT_SECRET=$(openssl rand -base64 48)`.
2. Set `DEMO_MODE=false` and create the first manager with `RESTAURANT_BOOTSTRAP_ADMIN_PASSWORD`.
3. Serve the app over HTTPS only, behind a TLS-terminating reverse proxy. Set
   `SERVER_FORWARD_HEADERS_STRATEGY=native` so that rate limits see the real client address. Forwarded headers are
   not trusted by default, because any client can forge them.
4. Keep `/actuator` (other than health) on a private network, and switch off the API docs:
   `SPRINGDOC_SWAGGER_UI_ENABLED=false`, `SPRINGDOC_API_DOCS_ENABLED=false`.
5. The rate limits and the token denylist are kept per instance. If you run several instances, put them in a
   shared store (Bucket4j and Caffeine both have Redis-backed equivalents).
