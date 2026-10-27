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

### Abuse and availability

| Control | Details |
| --- | --- |
| API rate limit | Token bucket ([Bucket4j](https://github.com/bucket4j/bucket4j)), 600 requests per minute per client address. Over budget: `429` with `Retry-After` |
| Request size | API bodies over 64 KB get `413`. A declared `Content-Length` is checked before reading, and chunked bodies are cut off while reading |
| Slow clients | Tomcat drops connections that do not send their request within 10 s, and idle keep-alive connections after 20 s |
| Live connections | At most 10 event streams per user and 500 in total. They are closed on shutdown, so graceful shutdown is not held up |
| Timeouts | 15 s per transaction, 10 s per query, 5 s to get a pooled database connection. The web client abandons requests after 15 s |
| Bounded memory | Rate-limit buckets, login counters and the token denylist live in size-capped, self-expiring [Caffeine](https://github.com/ben-manes/caffeine) caches |
| Errors | RFC 9457 problem details with a stable `code`. No stack traces or exception messages are ever sent to the client |

### Browser

`Content-Security-Policy` (scripts from the app's own origin only; no inline scripts, objects or framing), `X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy` (camera, microphone, geolocation,
payment and similar off), `Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy: same-origin`,
`Cache-Control: no-store`, and HSTS over HTTPS. CORS is not enabled, so only the app's own pages can call the
API. The client builds the DOM from text nodes only, so guest names and notes can never inject markup.

### Payments

| Control | Details |
|---|---|
| Card data | Cards are only ever entered on the payment provider's hosted page (iyzico Checkout Form, 3-D Secure). Sofra never sees card numbers |
| Provider answers | The guest's return to the callback is never trusted. The result is fetched server to server, its HMAC-SHA256 signature checked in constant time, and its conversation id, checkout token and amount must match what Sofra started |
| Request signing | Calls to iyzico are signed with `IYZWSv2` (HMAC-SHA256 over a random key, path and body) |
| Payment links | 256-bit random tokens, stored only as SHA-256 hashes, valid for 2 hours and only while the bill is open. The guest page shows items and totals, nothing about staff or other guests |
| Double charging | Bills are changed under a row lock; payments taken at the till carry an `Idempotency-Key`, so a retried request returns the payment already taken |
| Test provider | The simulated provider marks payments as paid without charging anything, so the app refuses to start with it when demo mode is off unless `restaurant.payments.allow-simulated` is set |
| Roles | Waiters and managers take payments; only managers refund and close the day |

### Audit

Sign-ins, failures, lockouts and sign-outs are written to the `security.audit` logger. User-supplied values in
these entries are stripped of control characters so they cannot forge log lines. The events are also counted in
Micrometer as `sofra.security.login{outcome}`, `sofra.security.logout` and `sofra.security.rate_limited`, which
can be scraped by Prometheus at `/actuator/prometheus` (admin only).

### Supply chain and deployment

- **CodeQL** (`security-extended` queries) scans the Java and JavaScript code on every push and weekly.
- **Dependabot** opens weekly updates for Maven, npm, GitHub Actions and the Docker base images.
- **Dependency review** blocks pull requests that add dependencies with known high or critical vulnerabilities.
- **Trivy** scans the Docker image in CI and fails the build on fixable critical vulnerabilities.
- **The container** runs as a non-root user and exits on out-of-memory errors. `docker-compose.yml` adds a
  read-only root filesystem, drops all Linux capabilities, sets `no-new-privileges`, and caps memory, CPU and
  process counts. The database is not published to the host.

## Running in production

1. Generate a signing key and keep it out of the repository: `export JWT_SECRET=$(openssl rand -base64 48)`.
2. Set `DEMO_MODE=false` and create the first manager with `RESTAURANT_BOOTSTRAP_ADMIN_PASSWORD`.
3. Serve the app over HTTPS only, behind a TLS-terminating reverse proxy. Set
   `SERVER_FORWARD_HEADERS_STRATEGY=native` so that rate limits see the real client address. Forwarded headers are
   not trusted by default, because any client can forge them.
4. Keep `/actuator` (other than health) on a private network.
5. The rate limits and the token denylist are kept per instance. If you run several instances, put them in a
   shared store (Bucket4j and Caffeine both have Redis-backed equivalents).
