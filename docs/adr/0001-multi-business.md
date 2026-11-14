# ADR 0001: Many businesses on one Sofra, and chains of branches

Status: accepted (November 2026). Phase A (businesses) in S4.6; phase B (branches) right after.

## Context

Sofra was built for one restaurant per installation. Sofra Cloud (docs/SOFRA_CLOUD_PLAN.md) serves many paying
businesses from shared servers, and a chain runs several branches under one account. A business must never see,
change or even learn about another's data, through any endpoint, background job, live event or cache.

## Decision

**Shared database, shared schema, a `business_id` on every business-owned row.**

- `businesses`: name, slug (its address, `slug.sofra.app`), time zone, plan and subscription status. The data of
  an existing installation becomes business 1, so a self-hosted Sofra keeps working unchanged.
- Every entity carries Hibernate's `@TenantId` business id. Hibernate then adds `business_id = ?` to every query
  and sets it on every insert, so isolation does not depend on each repository method remembering it. Sofra has
  no native SQL in production code; a test keeps it that way.
- Uniqueness becomes per business: usernames, dish names, table numbers, coupon codes, printers, stations, the
  dates of a day closing or tip pool, delivery-platform references. Random tokens (QR, payment links, idempotency
  keys) stay globally unique.

**Which business a request is for**

| Request | Business from |
|---|---|
| Signed in | the token's `bid` claim (the token is signed, so it cannot be changed) |
| Sign-in | the address (`slug.sofra.app`), else the business code the app sends (`X-Business`), else the only business there is |
| Guest QR, payment link, provider callback | the token itself, looked up across businesses once, then the request runs as that business |
| Background jobs | each job runs once per business, as that business |

A request whose business cannot be determined is refused; nothing ever falls back to "all businesses".

**What else becomes per business:** the name and time zone (today's report, opening hours), service settings,
the live event stream (an event goes only to screens of its own business), and every cache keyed by a username
(session guard, sign-in lockout).

**Sign-up** (Sofra Cloud only, `restaurant.cloud.signup-enabled`): a business name, an address, the owner's
account; a 14-day trial with sample tables and dishes. Rate-limited per client.

**Phase B, branches:** a business has branches; tables, orders, bills, kitchen stations, printers, tills, time
entries and reservations belong to a branch; the menu belongs to the business (with per-branch availability and
prices later). Staff are allowed into one or more branches; a chain owner sees all.

## Considered and not chosen

- **A database (or schema) per business:** the strongest separation, but every migration runs N times, connection
  pools multiply, and cross-branch reporting for chains gets harder. Kept as an option for a large chain that
  demands it.
- **Only a `WHERE business_id` in each repository method:** one forgotten condition leaks data. `@TenantId` makes
  the safe path the default.
- **PostgreSQL row-level security alone:** a good second wall, planned once phase A is in place (a policy on
  `current_setting('sofra.business_id')` set per transaction), but not a replacement for the first.

## Consequences

- Isolation tests create two businesses and prove, endpoint by endpoint, that one cannot read or change the other's
  orders, tables, menu, staff, bills, reservations or live events.
- Integrations whose keys belong to the restaurant (iyzico merchant keys, e-Arşiv, delivery platforms) must move from
  server settings to per-business settings before they are offered in Sofra Cloud; until then the cloud runs
  without online payments and integrations, and self-hosted installations keep using environment variables.
