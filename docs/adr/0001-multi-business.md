# ADR 0001: Many businesses on one Sofra, and chains of branches

Status: accepted and implemented (November 2026): phase A (businesses, V24) and phase B (branches, V26) in S4.6.

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

**Phase B, branches:** a business has branches (`branches`; every business starts with one, "Merkez").

- What happens in a place belongs to a branch: tables, orders, bills, guest requests, printers and print jobs,
  tills, reservations, shifts, time entries, day closings and tip pools (`branch_id`, set once, never changed).
  The menu, stock, coupons, kitchen stations, service settings and staff accounts belong to the business.
- Uniqueness that was per business becomes per branch where it describes the place: table numbers, printer names,
  the dates of a day closing or tip pool. Two branches each have a table 1.
- A person works in one branch (`app_users.branch_id`, the token's `br` claim) or in all of them (none: an owner,
  a chain manager). Moving someone to another branch ends their sessions.
- **Which branch a request works in:** the token's `br` claim; for someone who works in all branches, the branch the
  device chose (`X-Branch`, checked to be an open branch of the same business), or none: all branches at once.
- **Reading:** a Hibernate filter (`branch_id = :branch`) is switched on in every transaction of a request that works
  in a branch, so queries only see that branch. Filters do not reach records fetched by id, so a `@PostLoad` check
  treats another branch's record as not found. Live events carry their branch and go only to that branch's screens
  (and to screens watching all branches).
- **Writing:** a `@PrePersist` listener stamps the branch from, in order, the thing it happens at (an order's table, a
  print job's printer), the request's branch, or the business's first open branch, which is all a single
  restaurant ever has.
- What is kept once per branch and day (the day report and closing, the tip pool) needs a branch: someone working
  in all branches of a chain picks one first (`BRANCH_REQUIRED`); a business with one open branch never has to.
- Only managers who work in all branches open, rename and close branches; a closed branch keeps its history and
  the last open branch cannot be closed. A branch manager sees and manages only their branch's people.
- Branches are a softer boundary than businesses: they share a menu and staff accounts by design, and a person who
  works in all branches sees all of them. A branch can sell a dish at its own price or not at all (Chain plan,
  `branch_menu_items`); legal documents belong to their bill's branch.
- **Second wall: PostgreSQL row-level security** (`RowLevelSecurity`). Every table with a `business_id` has a policy
  `sofra_business_isolation` (ENABLE and FORCE, so it holds for the owner the application connects as), applied after
  every migration, so a new table is covered without anyone remembering to. Each transaction sets
  `sofra.business_id` (`set_config(..., true)`, reset when it ends, so pooled connections never carry it over); a row
  of another business is then neither read nor written, even by native SQL past Hibernate. Work that names no
  business (migrations, start-up) and work across all businesses (`BusinessContext.ALL`, "0") pass, as they do in
  the application. A superuser or a role with BYPASSRLS ignores every policy, and the official PostgreSQL image makes
  `POSTGRES_USER` a superuser; when the application connects as such a user, the migration keeps a role
  `sofra_tenant` (no login, no bypass, only reading and writing rows) and each business's transaction runs as it
  (`SET LOCAL ROLE`, reset when it ends). Tested on PostgreSQL 16, as the tables' owner and as a superuser: another
  business's id reads nothing, changes nothing and cannot insert.

## Considered and not chosen

- **A database (or schema) per business:** the strongest separation, but every migration runs N times, connection
  pools multiply, and cross-branch reporting for chains gets harder. Kept as an option for a large chain that
  demands it.
- **Only a `WHERE business_id` in each repository method:** one forgotten condition leaks data. `@TenantId` makes
  the safe path the default.
- **PostgreSQL row-level security alone:** a good second wall (now in place, see above), but not a replacement for
  the first: H2 has none, and the tenant filter also stamps every insert.

## Consequences

- Isolation tests create two businesses (and, for branches, two branches of one business) and prove, endpoint by endpoint, that one cannot read or change the other's
  orders, tables, menu, staff, bills, reservations or live events.
- Integrations whose keys belong to the restaurant (iyzico merchant keys, e-Arşiv, delivery platforms) are
  per-business settings in Sofra Cloud (Settings › Online payment account, Settings › Connections); the server's own
  settings are never shared between businesses there. Self-hosted installations can keep using environment variables.
