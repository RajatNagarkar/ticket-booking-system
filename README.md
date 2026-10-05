# Ticket Booking System

[![CI](https://github.com/RajatNagarkar/ticket-booking-system/actions/workflows/ci.yml/badge.svg)](https://github.com/RajatNagarkar/ticket-booking-system/actions/workflows/ci.yml)

A JSON API that sells assigned seats for a show and stays correct under an on-sale stampede: no seat is ever sold twice, no user exceeds their per-show limit, and a retried request never reserves twice.

Built with Java 21, Spring Boot 3.3 (JdbcTemplate, virtual threads), PostgreSQL and Flyway.

**Live:** https://ticket-booking-system-production-4127.up.railway.app/tbs (e.g. [`/actuator/health/readiness`](https://ticket-booking-system-production-4127.up.railway.app/tbs/actuator/health/readiness), [`/actuator/prometheus`](https://ticket-booking-system-production-4127.up.railway.app/tbs/actuator/prometheus)).

**Live dashboard and logs (no login):** [Grafana public dashboard](https://heftyraspberry2178.grafana.net/public-dashboards/4f2b840eafdc48208d23ac5522e6487d): 5xx, invariant violations, requests/s by status, reservation outcomes, reserve p50/p95/p99, seats per show, connection pool, JVM heap and the live log stream.

> **Status:** shows, reservations, idempotency, per-user limit, cancel, JWT auth, observability (health probes, Prometheus metrics, JSON logs), Docker, CI, the burst script and the Railway deployment are implemented.

## Quick start (Docker)

The only requirement is Docker. From a clean clone:

```bash
docker compose up --build
```

This starts PostgreSQL 16 and the app. The API is at `http://localhost:8085/tbs`, ready once `http://localhost:8085/tbs/actuator/health/readiness` returns 200 (`docker compose up --build --wait` blocks until it does).

Then run the end-to-end smoke test:

```bash
./scripts/smoke.sh http://localhost:8085/tbs
```

It checks readiness, gets tokens, creates a show, reserves a seat (201), tries the same seat again as another user (409), checks the counts add up, and checks metrics are exposed. Point it at any deployment to verify it.

Compose mirrors production's two database roles: Flyway migrates as the owner (`tbs_owner`) and the app connects as `app_runner`, which can only read and write rows. All compose credentials are for local use only.

### The image

- Multi-stage build: Maven compiles and packages on a JDK image; the runtime stage is `eclipse-temurin:21-jre` with only the extracted jar and its dependencies.
- Runs as a non-root `app` user.
- JVM sized for a 1 GB container: G1 GC, heap capped at 65% of memory, metaspace capped at 192 MB. Left to its defaults in under ~1.8 GB, the JVM picks single-threaded SerialGC with a 75% heap; under a 1,000-concurrency burst that measured 983 MB of 1 GB, one spike away from an OOM kill. With these flags the peak was 770–843 MB, and warm throughput went from ~1,050 to ~1,400 requests/s (p99 2.5 s → 1.4–2.0 s) on the same machine.
- **AppCDS:** the build does a training run of the app and stores a class-data archive in the image, cutting startup from about 1.6 s to 1.2 s locally (more on slow, shared CPUs, which is where cold starts hurt).
- Listens on `PORT` if the platform sets it, otherwise 8085.
- `GET /tbs/actuator/info` reports the version, build time and git commit (pass `--build-arg GIT_COMMIT=$(git rev-parse --short HEAD)`; on Railway, `RAILWAY_GIT_COMMIT_SHA` is picked up automatically).

## Burst test

`./burst.sh` reproduces the on-sale stampede against any running instance and then audits the result. It needs only bash, curl (7.84+) and awk.

```bash
./burst.sh http://localhost:8085/tbs
./burst.sh https://<live-url>/tbs
```

It creates a fresh show of 1,000 seats (limit 4 per user), mints tokens for 5,000 users, and fires about 20,000 reservations at once, about 2,000 in flight at a time:

| Scenario | Requests | What it exercises |
|---|---|---|
| Hot-seat storm | 5 seats × 500 users | Exactly one winner per seat |
| Random seats | 12,500 (1–2 seats each) | General contention, all-or-nothing |
| Same-key retries | 1,000 keys × 3, concurrently | Exactly-once per key |
| Same key, different seats | 500 keys × 2 | `idempotency_key_reused` |
| Limit abusers | 50 users × 20 in parallel | Per-user limit under concurrency |

It prints the outcome distribution, a per-scenario breakdown and latency, then checks, exiting 1 if any fails:

- zero 5xx and zero transport errors
- each reservation created once (every other 201 for it is a replay); no seat in two reservations
- exactly one winner per hot seat; no idempotency key produced two reservations; nobody over the limit
- `available + held + confirmed == 1000` in `GET /shows/{id}`, and confirmed seats equal the seats in the 201 responses
- the `reservations_*` counter deltas in `/actuator/prometheus` equal the response counts, the `seats` gauge equals `GET /shows/{id}`, and `seats_invariant_violations` is 0

Options: `--concurrency N --seats N --users N --random N --storm N --hot N --seed N`. Metrics checks assume one instance and no other traffic during the run.

**Against the local compose stack on macOS**, Docker Desktop / Colima port forwarding can't take thousands of simultaneous connections, so run the generator inside the compose network:

```bash
BURST_DOCKER_NETWORK=ticket-booking-system_default ./burst.sh http://app:8085/tbs
```

(`APP_PORT=18085 docker compose up` moves the published port if 8085 is taken.)

Local result (compose stack on a 2-CPU / 2 GB Colima VM shared by PostgreSQL, the app and the generator, so latency is not representative):

```
Done in 19.00s: 1053 requests/s

Outcomes
  201 confirmed                                836
  201 replayed (idempotent)                    331
  409 idempotency_key_reused                    54
  409 per_user_limit                            74
  409 seat_taken                             18705
  = total                                    20000
  = 4xx other than 409                           0
  = 5xx                                          0

Checks
  PASS  zero 5xx (0)
  PASS  every request got an HTTP response (0 transport errors)
  PASS  each reservation was created once; every other 201 for it was a replay (836 created, 836 distinct)
  PASS  no seat in two reservations (0 double-sold)
  PASS  hot seat A1: exactly 1 winner (1)
  ...   (A2–A5 likewise)
  PASS  no idempotency key produced two reservations (0)
  PASS  per-user limit 4 held (worst abuser 4 seats, 0 users over the limit)
  PASS  invariant: available 0 + held 0 + confirmed 1000 == 1000
  PASS  confirmed seats in GET /shows (1000) == seats in 201 responses (1000)
  PASS  reservations_confirmed_total +836 == 836 responses
  PASS  reservations_declined_total{reason="seat_taken"} +18705 == 18705 responses
  PASS  reservations_declined_total{reason="per_user_limit"} +74 == 74 responses
  PASS  reservations_declined_total{reason="idempotency_key_reused"} +54 == 54 responses
  PASS  reservations_declined_total{reason="idempotent_replay"} +331 == 331 responses
  PASS  seats gauge matches GET /shows (available 0, confirmed 1000)
  PASS  seats_invariant_violations == 0 (0)

BURST PASSED: all 20 checks hold
```

### Against the live URL

Run on 2026-10-05 from a single laptop (India) against the Railway deployment (`ac4091f`):

```text
$ ./burst.sh https://ticket-booking-system-production-4127.up.railway.app/tbs --concurrency 500
Burst against https://ticket-booking-system-production-4127.up.railway.app/tbs (run 618a6959, seed 636460882)

Setup: show efd2ee4a-d725-4fcc-a360-66b9c664f4c1 with 1000 seats (limit 4), 5000 user tokens in 38.2s
Firing 20000 requests (concurrency 500, 2 curl processes)...

Done in 22.16s: 903 requests/s

Outcomes
  201 confirmed                                842
  201 replayed (idempotent)                    308
  409 idempotency_key_reused                    52
  409 seat_taken                             18798
  = total                                    20000
  = 4xx other than 409                           0
  = 5xx                                          0

By scenario
  limit abusers                  1000 sent: confirmed=56 seat_taken=944
  random seats                  12500 sent: confirmed=570 seat_taken=11930
  hot-seat storm                 2500 sent: confirmed=5 seat_taken=2495
  same-key retries               3000 sent: replayed=306 confirmed=153 seat_taken=2541
  same key, different seats      1000 sent: replayed=2 confirmed=58 idempotency_key_reused=52 seat_taken=888

Latency  p50 371ms  p95 920ms  p99 5329ms  max 8239ms

Checks
  PASS  zero 5xx (0)
  PASS  every request got an HTTP response (0 transport errors)
  PASS  each reservation was created once; every other 201 for it was a replay (842 created, 842 distinct)
  PASS  no seat in two reservations (0 double-sold)
  PASS  hot seat A1: exactly 1 winner (1)
  PASS  hot seat A2: exactly 1 winner (1)
  PASS  hot seat A3: exactly 1 winner (1)
  PASS  hot seat A4: exactly 1 winner (1)
  PASS  hot seat A5: exactly 1 winner (1)
  PASS  no idempotency key produced two reservations (0)
  PASS  per-user limit 4 held (worst abuser 4 seats, 0 users over the limit)
  PASS  invariant: available 0 + held 0 + confirmed 1000 == 1000
  PASS  confirmed seats in GET /shows (1000) == seats in 201 responses (1000)
  PASS  reservations_confirmed_total +842 == 842 responses
  PASS  reservations_declined_total{reason="seat_taken"} +18798 == 18798 responses
  PASS  reservations_declined_total{reason="per_user_limit"} +0 == 0 responses
  PASS  reservations_declined_total{reason="idempotency_key_reused"} +52 == 52 responses
  PASS  reservations_declined_total{reason="idempotent_replay"} +308 == 308 responses
  PASS  seats gauge matches GET /shows (available 0, confirmed 1000)
  PASS  seats_invariant_violations == 0 (0)

BURST PASSED: all 20 checks hold
```

At `--concurrency 2000` from the same laptop the service still returned zero 5xx and every other check held, but about 0.6% of requests (121 of 20,000) failed before reaching it, with `curl exit 7` (connect) and `exit 35` (TLS handshake). The app's own request count matched the requests that got through, and the DB pool never timed out, so the limit was the single client network opening 2,000 TLS connections at once, not the service.

## Continuous integration

Every push to `main` and every pull request runs two GitHub Actions jobs:

1. **Build and test:** `./mvnw verify`, running the full unit and integration suite (including the concurrency tests) against a real PostgreSQL via Testcontainers.
2. **Docker image, smoke test and burst:** builds the image, starts the compose stack, waits until it is healthy, runs `scripts/smoke.sh`, then a reduced `./burst.sh` (500 concurrent, ~11.5k requests) with the same checks. This is the clean-clone path a reviewer takes.

## Deploy (Railway)

The app and PostgreSQL run as two services in one Railway project, talking over Railway's private network (no public hop between app and database). The app is built from the same `Dockerfile` as compose. `railway.json` sets the Dockerfile builder, points the deploy health check at `/tbs/actuator/health/readiness` (a deploy only goes live once the database answers), restarts on failure, and keeps the service always on (no sleeping, so no 502s while waking).

1. **Database.** In the project, add *Database → PostgreSQL*, in the same region as the app service.
2. **App service.** Create it from this GitHub repo. Under *Variables → Add Reference*, add `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD` from the Postgres service (they resolve to the private host and the owner role). Then set:

   | Variable | Value |
   |---|---|
   | `DB_USERNAME` / `DB_PASSWORD` | `app_runner` / `openssl rand -hex 24` |
   | `JWT_SECRET` | `openssl rand -base64 48` |
   | `APP_ENV` | `prod` |

   The JDBC URL is built from `PGHOST`/`PGPORT`/`PGDATABASE`, and Flyway migrates as `PGUSER`. `POSTGRES_DB_URL` and `FLYWAY_USER`/`FLYWAY_PASSWORD`, if set, take precedence.

   No manual database setup: on startup Flyway (as the owner) creates `app_runner` with this password, or updates the password if the role exists, and grants it row access only (`db/migration/beforeMigrate.sql`).

   Railway injects `PORT` and `RAILWAY_GIT_COMMIT_SHA` (shown at `/tbs/actuator/info`). Under *Settings → Networking*, generate a public domain.
3. **Verify.** `./scripts/smoke.sh https://<domain>/tbs`, then `./burst.sh https://<domain>/tbs`.

## Running locally

Requirements: JDK 21 and a PostgreSQL 14+ database. Maven is not needed; the repo includes the Maven wrapper (`./mvnw`).

The app connects as a role that can only read and write rows. Flyway runs migrations on startup as the schema owner.

| Variable | Example | Purpose |
|---|---|---|
| `POSTGRES_DB_URL` | `jdbc:postgresql://localhost:5432/tbs` | JDBC URL (include `?sslmode=require` for hosted Postgres). If unset, built from `PGHOST`, `PGPORT` (default 5432), `PGDATABASE` |
| `DB_USERNAME` / `DB_PASSWORD` | `app_runner` / … | App role: `SELECT, INSERT, UPDATE, DELETE` only |
| `FLYWAY_USER` / `FLYWAY_PASSWORD` | `postgres` / … | Schema owner, used only for migrations. Default to `PGUSER` / `PGPASSWORD` |
| `JWT_SECRET` | 32+ random bytes, e.g. `openssl rand -base64 48` | HS256 signing key. The app refuses to start without one |
| `JWT_TTL` | `PT12H` (default) | Lifetime of issued tokens (ISO-8601 duration) |
| `SEAT_GAUGE_WINDOW` | `PT24H` (default) | Only shows created within this window appear in the `seats` metric |
| `METRICS_USER` / `METRICS_PASSWORD` | `grafana` (default) / `openssl rand -hex 24` | Basic auth for `/tbs/internal/metrics`, the Grafana Cloud scrape endpoint. Without a password it rejects every request |
| `PORT` | `8085` (default) | HTTP port; most hosting platforms set this |
| `DB_POOL_SIZE` | `40` (default) | Database connections for request handling (fixed-size pool). Requests beyond it queue for a connection |

The app role needs no manual setup: before migrating, Flyway creates `DB_USERNAME` with `DB_PASSWORD` (or updates its password) and grants it `SELECT, INSERT, UPDATE, DELETE` only. `DB_PASSWORD` must not contain a single quote. When `DB_USERNAME` equals `FLYWAY_USER`, this step is skipped.

Start the app:

```bash
./mvnw spring-boot:run
```

The API is served on `http://localhost:8085/tbs`. Logs are JSON; add `-Dspring-boot.run.profiles=plain-logs` for human-readable output.

## Running the tests

The integration tests start a real PostgreSQL 16 in Docker via Testcontainers, so Docker must be running.

```bash
./mvnw test
```

With Colima, point Testcontainers at its socket first:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

The suite includes concurrency tests that fire hundreds of parallel requests at the API:

- 500 users racing for one seat: exactly one 201, 499 × 409.
- 300 overlapping multi-seat requests in random seat order: no deadlocks, no partial reservations.
- 50 parallel retries with one idempotency key: exactly one reservation.
- 10 parallel requests from one user on a limit-4 show: exactly 4 seats.
- Cancels racing reserves on the same seats and quota row: no deadlocks, quota always matches seats held.

Observability tests check that every counter and the seat gauge match the API state after a mix of confirms, declines, replays and cancels, and that each request writes one access-log line carrying its request id, user, show and outcome.

Auth tests cover missing, malformed, forged, expired and wrong-issuer tokens (all 401), a user creating a show (403), and a request body that tries to act as another user.

## API

All request and response bodies are JSON with `snake_case` fields. Money is always an integer number of paise.

### Authentication

Every request except `GET /tbs/shows/{id}`, `POST /tbs/auth/token` and `/tbs/actuator/*` needs a bearer token:

```
Authorization: Bearer <access_token>
```

The caller's identity is the token's subject. A `user_id` in a request body is ignored, so a request can only ever act as the token's user.

#### Get a token

`POST /tbs/auth/token`

```json
{ "user_id": "alice", "role": "user" }
```

`role` is `user` (default) or `admin`. Admin tokens can also reserve and cancel.

```json
{ "access_token": "eyJhbGciOiJIUzI1NiJ9…", "token_type": "Bearer", "expires_in": 43200 }
```

> This is a **development token issuer**: it mints a signed token for any user id and role, so anyone can obtain an admin token. It exists so the API can be exercised and load-tested without an identity provider. In production, tokens would come from a real IdP and this endpoint would be removed.

Tokens are HS256 JWTs with claims `sub` (user id), `roles` (`["USER"]` or `["ADMIN", "USER"]`), `iss` (`ticket-booking-system`), `iat` and `exp`. A token with a bad signature, another issuer, or a past expiry is rejected with 401.

### Create a show

`POST /tbs/shows` (admin token required)

```json
{ "name": "friday-night", "seats": ["A1", "A2", "A3"], "price_paise": 25000, "per_user_limit": 4 }
```

`per_user_limit` is optional and defaults to 4. Returns **201** with the show and every seat `available` (same shape as below).

### Get a show

`GET /tbs/shows/{id}`

```json
{
  "id": "6f1c…",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "total_seats": 3,
  "counts": { "available": 2, "held": 0, "confirmed": 1, "total": 3 },
  "seats": [
    { "seat_no": "A1", "status": "confirmed" },
    { "seat_no": "A2", "status": "available" },
    { "seat_no": "A3", "status": "available" }
  ]
}
```

`available + held + confirmed == total` always holds. All counts are read in a single statement, so they come from one consistent snapshot.

### Reserve seats

`POST /tbs/shows/{id}/reserve` (any token)

```json
{ "seats": ["A12", "A13"], "idempotency_key": "4b0e…" }
```

The idempotency key can go in the body (`idempotency_key`) or in an `Idempotency-Key` header. One of them is required; if both are sent they must match.

**201**, with header `Idempotent-Replayed: false` (or `true` for a retry):

```json
{
  "reservation_id": "9a2d…",
  "show_id": "6f1c…",
  "user_id": "alice",
  "seats": ["A12", "A13"],
  "amount_paise": 50000,
  "status": "confirmed"
}
```

### Cancel a reservation

`POST /tbs/reservations/{id}/cancel` (owner's token)

Returns **200** with the reservation, now `"status": "cancelled"`. Its seats become available again and the user's quota is returned.

### Errors

Every decline is a 4xx with a stable error code:

```json
{ "error": "seat_taken", "message": "Seats already taken: A12" }
```

| Status | `error` | When |
|---|---|---|
| 409 | `seat_taken` | Any requested seat is already held or confirmed |
| 409 | `per_user_limit` | The reservation would take the user over the show's limit |
| 409 | `idempotency_key_reused` | Same key, different show or seats |
| 400 | `unknown_seat` | A requested seat does not exist in the show |
| 400 | `duplicate_seats` | The same seat appears twice in one request |
| 400 | `missing_idempotency_key` | No key in the header or the body |
| 400 | `conflicting_idempotency_key` | `Idempotency-Key` header and body `idempotency_key` differ |
| 400 | `invalid_idempotency_key` | Key longer than 128 characters |
| 400 | `validation_failed` / `malformed_request` / `invalid_parameter` | Bad body or path parameter |
| 401 | `unauthenticated` | Missing, malformed, forged, expired or wrong-issuer token |
| 403 | `forbidden` | Valid token without the required role (e.g. a user creating a show) |
| 404 | `show_not_found` / `reservation_not_found` | Unknown id (or another user's reservation) |

## Observability

### Health

| Endpoint | Checks | Use for |
|---|---|---|
| `GET /tbs/actuator/health/liveness` | The process is up | Restart policy |
| `GET /tbs/actuator/health/readiness` | The process is up **and** PostgreSQL answers | Load balancer / platform health check |

Readiness fails closed: if the database is unreachable it returns **503** with `"database": {"status": "DOWN"}`, so the platform stops routing traffic instead of serving requests that cannot be decided correctly. It recovers on its own when the database comes back. Liveness does not check the database, so a database outage never causes restart loops.

The readiness check runs `SELECT 1` on its **own single-connection pool** with 2–3 second timeouts, not on the request pool:

- During a burst the request pool is fully busy and requests queue for connections (up to 30 s, by design, so they wait instead of failing). A probe sharing that pool would hang and report DOWN, and the platform would pull a perfectly healthy instance out of rotation mid-burst. A test holds every request-pool connection and checks readiness still answers 200 in under 2 s; on the shared pool it hung for 31 s and returned 503.
- If the database stops responding without refusing connections (a network blackhole), readiness still answers within about 2 s instead of waiting out the request pool's 30 s timeout.
- Probes that arrive together share one check (the result is reused for 1 s). With a single connection and a remote database, 20 simultaneous probes would otherwise queue behind each other, time out and report DOWN while the database is fine.

### Metrics

Prometheus format at `GET /tbs/actuator/prometheus` (public).

| Metric | Type | Meaning |
|---|---|---|
| `reservations_confirmed_total` | counter | Reservations committed |
| `reservations_declined_total{reason}` | counter | Requests that did not create a reservation. `reason` is the error code (`seat_taken`, `per_user_limit`, `idempotency_key_reused`, `unknown_seat`, `show_not_found`, …) or `idempotent_replay` for a retry that returned an existing reservation |
| `reservations_cancelled_total` | counter | Reservations cancelled (a repeated cancel is not counted again) |
| `seats{show, status}` | gauge | Seats per show and status, read from the database on every scrape. Only shows created within `SEAT_GAUGE_WINDOW` (24 h) |
| `seats_invariant_violations` | gauge | Number of shows (within the window) where `available + held + confirmed != total_seats`. Always 0 by construction; anything else should page someone |
| `seats_gauge_refresh_failures_total` | counter | Scrapes where the seat counts could not be read from the database |
| `http_server_requests_seconds_bucket{uri, status, …}` | histogram | Request latency, for p50/p99 by endpoint and status |
| `hikaricp_connections_active` / `_pending` | gauge | Connection-pool usage and queueing |
| `db_transaction_retries_total{sqlstate}` | counter | Transactions retried after a deadlock (`40P01`) or serialization failure (`40001`). Expected to stay at 0; a rising value means lock ordering has been broken somewhere |

How the numbers reconcile:

- Counters are incremented only after the reservation transaction has committed (or rolled back), so they never count a reservation that didn't happen.
- `seats` is not tracked in memory. It is a `GROUP BY` over the `seats` table at scrape time, so it always matches `GET /tbs/shows/{id}`, holds `available + held + confirmed == total_seats`, and stays correct across restarts and multiple instances.
- `seats` only covers shows created in the last 24 h (`SEAT_GAUGE_WINDOW`). Every show adds three series, so without a window the scrape query and the number of series would grow with every show ever created. Older shows are still available through `GET /tbs/shows/{id}`.
- `seats_invariant_violations` is computed from the same snapshot as `seats`, by comparing each show's seat rows with its declared `total_seats`. A test deletes a seat row behind the API's back and checks the gauge goes to 1.
- If the database can't be read during a scrape, the `seats` series are dropped and `seats_invariant_violations` reports `NaN` for that scrape (rather than stale numbers), and `seats_gauge_refresh_failures_total` goes up.
- `held` is always 0: reservations are confirmed immediately, so no seat is ever in a held state.
- Counters are per instance and reset on restart, as Prometheus counters do. Use `sum(increase(...))` across instances. Only `seats` is read from the database and is correct across instances without aggregation.

Reading the reservation counters against HTTP responses from `POST /shows/{id}/reserve`:

| HTTP response | Counter |
|---|---|
| 201, `Idempotent-Replayed: false` | `reservations_confirmed_total` |
| 201, `Idempotent-Replayed: true` | `reservations_declined_total{reason="idempotent_replay"}` |
| 409 / 400 / 404 with an `error` code | `reservations_declined_total{reason="<error code>"}` |
| 400 `validation_failed` / `malformed_request`, 401, 403, 5xx | not in the reservation counters. Only in `http_server_requests_seconds_count{uri="/shows/{showId}/reserve", status=…}` |

A replay returns 201 but creates nothing, which is why it is counted under `declined` (the reason name makes the distinction explicit). Requests rejected before reaching reservation logic (bad JSON, no token) are not domain decisions, so they appear only in the HTTP metrics.

> `/tbs/actuator/prometheus` is public so the metrics can be inspected and scraped without credentials. It exposes show ids and JVM / connection-pool internals. In production it would be restricted to a private network or protected with auth.

### Logs

Logs are JSON, one object per line. Every request writes one access-log line when it completes:

```json
{"ts":"2026-10-05T00:36:12.622+05:30","level":"INFO","message":"request completed",
 "request_id":"demo-req-1","user_id":"alice","show_id":"6f1c…","reservation_id":"9a2d…","outcome":"confirmed",
 "method":"POST","path":"/tbs/shows/6f1c…/reserve","status":201,"duration_ms":38}
```

- `request_id` is taken from the `X-Request-Id` request header if present (up to 64 characters of `A-Z a-z 0-9 . _ -`), otherwise generated. It is echoed back in the `X-Request-Id` response header and attached to every log line written while handling the request.
- `user_id` is the token's subject. `outcome` is `confirmed`, `idempotent_replay`, `cancelled`, `already_cancelled`, or the error code.
- Health and metrics requests are not logged.

### Grafana Cloud (dashboards, public logs, alerts)

Setup on a free Grafana Cloud stack:

1. **Logs.** *Connections → Loki* gives the URL and numeric user. Create an access-policy token with `logs:write`. On the Railway app service set `SPRING_PROFILES_ACTIVE=loki`, `LOKI_URL`, `LOKI_USER`, `LOKI_TOKEN`.
2. **Metrics.** Grafana Cloud only scrapes URLs that reject unauthenticated requests, so it uses `/tbs/internal/metrics`: the same output as `/tbs/actuator/prometheus` behind Basic auth. Set `METRICS_PASSWORD` (e.g. `openssl rand -hex 24`; user `METRICS_USER`, default `grafana`) on the app service. Then *Connections → Metrics Endpoint*: scrape job `ticket-booking-system`, URL `https://<live-url>/tbs/internal/metrics`, Basic auth with those credentials. Without `METRICS_PASSWORD` the endpoint rejects every request. `/tbs/actuator/prometheus` stays public.
3. **Dashboard.** *Dashboards → New → Import*, upload `monitoring/grafana-dashboard.json`, pick the stack's Prometheus and Loki data sources.
4. **Alerts.** Load the rules into the stack's ruler: `mimirtool rules load monitoring/alerts.yml --address=<prometheus-url-without-/api/prom> --id=<prometheus-user> --key=<token with rules:write>`.
5. **Public view.** On the dashboard, *Share → Share externally* to get a link anyone can open without a Grafana login.

**Logs.** With the `loki` profile the app also ships every log line to Grafana Loki, batched and sent off the request thread within a second, so a burst can be watched live. It's off by default (local runs and tests are unchanged):

| Variable | Example |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `loki` |
| `LOKI_URL` | `https://logs-prod-0XX.grafana.net` |
| `LOKI_USER` | numeric Loki user id |
| `LOKI_TOKEN` | Grafana Cloud access-policy token with `logs:write` |
| `APP_ENV` | `prod` (default) or `local`, stored as a label |

Streams are labelled only `app`, `env` and `level` (low cardinality); `request_id`, `user_id`, `show_id` and `outcome` stay in the JSON body. Example queries: `{app="ticket-booking-system"} | json | outcome="seat_taken"`, `{app="ticket-booking-system"} |= "<request id>"`.

**Metrics.** Grafana Cloud's *Metrics Endpoint* integration scrapes `https://<live-url>/tbs/internal/metrics` (Basic auth) on a schedule; no agent runs alongside the app.

**Dashboard.** Public: [https://heftyraspberry2178.grafana.net/public-dashboards/4f2b840eafdc48208d23ac5522e6487d](https://heftyraspberry2178.grafana.net/public-dashboards/4f2b840eafdc48208d23ac5522e6487d). Source: `monitoring/grafana-dashboard.json` (import it and pick the Prometheus and Loki data sources). It uses no template variables, because public dashboards don't support them: 5xx, invariant violations, transaction retries, pool waiters, requests/s by status, reservation outcomes by reason, reserve p50/p95/p99, seats by status for every show created in the last 24 hours, connection pool, JVM heap, and a live log panel.

**Alerts.** `monitoring/alerts.yml` has the paging rules: any 5xx, seat invariant violated, instance down, reserve p99 above 2 s, sustained pool queueing, transaction retries, and seat-metric refresh failures.

## Behaviour

### Reservations are all-or-nothing

A request for `["A12", "A13"]` either gets both seats or neither. If any seat is taken, the whole request is declined with 409 `seat_taken` and nothing changes.

### Idempotency

The **client** generates the key, typically a random UUID, once per booking attempt, and resends the same key when it retries. It has to come from the client: if a response is lost, only the client knows the next request is a retry of the same attempt rather than a new booking. The **server** enforces it: the key is stored with a unique constraint, so the same key can never create a second reservation.

- The key goes in an `Idempotency-Key` header or in the body as `idempotency_key`.
- The key is scoped per user: two users can use the same key independently.
- A retry with the same key and the same seats (in any order) returns the original reservation with 201 and `Idempotent-Replayed: true`. It does not count against the limit again.
- The same key with different seats, or for a different show, is rejected with 409 `idempotency_key_reused`.
- Only a successful reservation claims a key. A declined attempt (`seat_taken`, `per_user_limit`, `unknown_seat`) stores nothing, so the key can be reused.
- After a reservation is cancelled its key stays claimed: retrying it returns the cancelled reservation. Use a new key to book again.
- **Retries without the original key are also safe.** If a user asks for exactly the seats they already hold in one confirmed reservation, they get that reservation back (201, `Idempotent-Replayed: true`), whatever key they send, instead of `seat_taken` for their own seats. The attempt is rolled back, so the new key isn't claimed and no quota is used, and it works even for a user already at their limit. A request that only partly overlaps the user's own seats is still `seat_taken`. This is a best-effort safety net for clients that lose their key; two keyless requests racing each other are still decided by the seat locks, with one 201 and one 409.

### Per-user limit

- The limit counts every seat a user currently holds for the show, across all their reservations.
- Cancelling returns the seats to the user's quota.
- If a request's seats are taken and the user is also at their limit, `seat_taken` is reported: seats are checked first, before the limit is touched.

### Cancel

- Only the owner can cancel. Another user's reservation returns 404, so its existence is not revealed.
- Cancelling twice is safe: the second call returns the cancelled reservation unchanged.
- A cancel only frees seats owned by that reservation. A late cancel can never release a seat that has since been sold to someone else.

## How correctness is enforced

Every guarantee is enforced by PostgreSQL inside a single transaction per reservation. The app keeps no state, so any number of instances can run.

1. **Idempotency.** `INSERT INTO reservations … ON CONFLICT (user_id, idempotency_key) DO NOTHING`. A concurrent retry with the same key waits on the unique index until the first transaction finishes, then either replays the committed reservation or, if the first rolled back, proceeds itself.
2. **Early decline.** One lock-free read of the requested seats' committed state. If any seat doesn't exist (400 `unknown_seat`) or is already confirmed (409 `seat_taken`, or the user's own reservation back, see natural idempotency), the transaction ends here, without touching the quota or waiting for seat locks. A confirmed seat stays taken until its owner cancels, so declining from committed state is safe; nothing is ever *granted* from this read. In a stampede most requests end at this step.
3. **Per-user limit.** A single upsert on `user_show_quota` adds the requested seats only `WHERE held + n <= per_user_limit`. Requests from the same user serialize on that row and re-check the guard against the latest committed count.
4. **Seats.** One statement locks the requested seats in `seat_no` order (`SELECT … ORDER BY seat_no FOR UPDATE` in a CTE) and takes the ones still available (`UPDATE seats … WHERE status = 'available'`). If fewer seats are confirmed than requested, the transaction rolls back. Under READ COMMITTED, a transaction that waited for a row lock re-evaluates `status = 'available'` against the winner's committed row, so when 500 requests race for one seat exactly one update succeeds.

A successful reservation costs 6 database round trips (show, key insert, seat read, quota, lock + confirm, commit); a decline costs 3. Declines are the vast majority of an on-sale burst, and each round trip to a managed database is several milliseconds.

Locks are always taken in the same order: idempotency key, then the user's quota row, then seats sorted by `seat_no`. Cancel follows the same quota-then-seats order. Because no two transactions ever wait on each other in opposite orders, overlapping requests queue instead of deadlocking.

As a safety net, if PostgreSQL still aborts a reserve or cancel transaction with a deadlock (`40P01`) or serialization failure (`40001`), the whole transaction is retried from scratch, up to 3 attempts with a short jittered pause. Each attempt is a new transaction, so a retry never sees the failed attempt's writes. Domain declines and other errors are never retried. The transaction boundary is explicit in code (`TransactionRetry` wraps a `TransactionTemplate`) rather than an annotation, so the retry is guaranteed to sit outside the transaction. A test provokes a real deadlock between two transactions locking rows in opposite order and checks both still succeed.

The schema backs this up: each seat is one row with one status, so the counts add up to the total by construction, and a check constraint forbids a seat that is taken without an owner or available with one.
