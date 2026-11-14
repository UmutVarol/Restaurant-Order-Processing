# Load test: a busy evening

`ops/loadtest/evening.mjs` plays a busy evening against a running Sofra: **40 tables, 12 waiters, 3 kitchen
screens and guests ordering from every table's QR code, all at once**. Waiters take orders, accept guests' carts,
wait for the kitchen, serve, take a second round, take cash and card payments with tips and clear tables; their
floor plan and inbox refresh every few seconds like on a phone. Kitchen screens keep the live event stream open,
read their board every two seconds and move tickets on. Guests open the menu, send carts and call the waiter.

It runs **10× faster than a real evening** by default (people think and eat ten times faster), so five minutes of
test hold more traffic than a real peak hour.

## Targets

| | Target |
|---|---|
| Errors | ≤ 0.1% of requests |
| Response time | p95 ≤ 300 ms on every endpoint |

## Results (November 2026)

Packaged app on PostgreSQL 16, one 4-core machine for the app, the database and the test, default pool of 10
database connections.

| Run | Requests | Rate | Errors | Worst p95 | |
|---|---|---|---|---|---|
| **10× an evening, 5 min** | 3,931 | 11.9/s | **0** | **191 ms** (place order) | **pass** |
| 40× an evening, 2 min | 2,483 | 20.7/s | 0 | 780 ms (place order, first minute) | errors none, slower |

At 10× nearly every endpoint answers in under 35 ms at p95; placing an order (pricing, stock, kitchen tickets,
printing) and taking a payment (fiscal document, audit) are the heaviest at under 200 ms. Database connections
waiting: 0 throughout. At 40× nothing fails; the slowest calls queue briefly for a database connection.

## What it found

The first run failed completely: after a few seconds every request waited 30–60 s and then failed with
"Connection is not available". The cause was a **deadlock between virtual threads and the connection pool**:
`SessionGuard` (which ends sessions when an account changes) loaded the account inside `Cache.get(key, loader)`.
That runs in a synchronized section, and on Java 21 a virtual thread waiting inside one stays pinned to its carrier
thread. Under load every carrier was pinned by a request waiting for a connection, so the requests that held the
connections never got a carrier to finish on. The account is now loaded outside the cache's lock; a run with
`-Djdk.tracePinnedThreads=short` shows no pinned threads left.

## Running it

Never against a restaurant's database: it creates staff accounts and tables.

```sh
docker run -d --name sofra-loadpg -e POSTGRES_DB=sofra -e POSTGRES_USER=sofra -e POSTGRES_PASSWORD=sofra -p 5433:5432 postgres:16-alpine
./mvnw -DskipTests package
java -jar target/restaurant-order-processing.jar --spring.profiles.active=postgres \
  --spring.datasource.url=jdbc:postgresql://localhost:5433/sofra --server.port=8090 \
  --restaurant.security.rate-limit.api-requests-per-minute=1000000 \
  --restaurant.security.rate-limit.public-requests-per-minute=1000000 \
  --restaurant.security.rate-limit.login-requests-per-minute=100000
BASE_URL=http://localhost:8090 DURATION=300 node ops/loadtest/evening.mjs
```

All simulated devices share one address, hence the lifted rate limits. `TABLES`, `WAITERS`, `KITCHENS`, `SPEED`,
`TARGET_P95_MS` and `TARGET_ERROR_RATE` change the scenario and the targets; the script exits non-zero when a
target is missed.

## For Sofra Cloud

One restaurant uses a small fraction of one instance. For many businesses on shared servers (S4.6), the same test
runs per tenant in parallel; the pool size and the number of app instances are then sized from it.
