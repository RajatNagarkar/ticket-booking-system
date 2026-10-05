# Write-up

A seat-reservation API that has to stay correct during an on-sale stampede. PostgreSQL is the single source of truth and the app is stateless. Every decision that could race is made by one guarded statement or one unique constraint inside the database, never by reading a value in Java and writing it back.

## The atomic decision

A reservation is one transaction, `ReservationService.reserveOnce`, run through `TransactionRetry.inTransaction`:

1. **Claim the idempotency key.** `INSERT INTO reservations … ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING *`. This row exists before any seat is touched, because `seats.reservation_id` references it.
2. **Decline early, without locks.** A plain `SELECT` of the requested seats (`SeatRepository.currentState`) rejects unknown seats and seats that are already confirmed. A confirmed seat stays taken until its owner cancels, so this read can only produce false "available" answers, never false "taken" ones. Most stampede requests end here, without waiting for a row lock or touching the quota.
3. **Per-user limit.** `INSERT INTO user_show_quota … ON CONFLICT (show_id, user_id) DO UPDATE SET held = held + n WHERE held + n <= limit`. Zero rows updated means `409 per_user_limit`.
4. **Take the seats.** One statement (`SeatRepository.lockAndConfirm`):

   ```sql
   WITH locked AS (
       SELECT seat_no, status FROM seats
       WHERE show_id = ? AND seat_no = ANY(?)
       ORDER BY seat_no
       FOR UPDATE
   ), taken AS (
       UPDATE seats s SET status = 'confirmed', reservation_id = ?, user_id = ?
       FROM locked l
       WHERE s.show_id = ? AND s.seat_no = l.seat_no
         AND l.status = 'available' AND s.status = 'available'
       RETURNING s.seat_no
   )
   SELECT count(*) FROM locked, count(*) FROM taken, …
   ```

   If fewer seats were confirmed than requested, the service throws `SeatTakenException`. The whole transaction rolls back: the reservation row, the quota increment and any seats already flipped.

**Why this is race-free.** `FOR UPDATE` serialises every transaction that wants a given seat. A transaction that waited for the lock re-reads the latest committed row version, so the `status = 'available'` guard is checked against the winner's commit. Of N concurrent requests for one seat, exactly one `UPDATE` matches. The rest see `confirmed` and get `409 seat_taken`. The schema backs this up: `CHECK ((status = 'available') = (reservation_id IS NULL) AND (reservation_id IS NULL) = (user_id IS NULL))` makes a half-assigned seat impossible.

**Multi-seat and deadlocks.** Requests are all-or-nothing. Seats are sorted before the query and locked `ORDER BY seat_no`. Every transaction takes its locks in the same order (idempotency key, then the user's quota row, then seats in seat order), and cancel follows the same quota-then-seats order. So two overlapping requests queue behind each other instead of deadlocking. If PostgreSQL still aborts a transaction (SQLSTATE `40P01` deadlock or `40001` serialization failure), `TransactionRetry` reruns it from scratch in a fresh transaction, up to 3 attempts, and counts it in `db_transaction_retries_total`. That counter has stayed at 0 in every burst.

**Cancel** is `UPDATE reservations SET status = 'cancelled' WHERE id = ? AND user_id = ? AND status = 'confirmed'`, then it releases the quota and runs `UPDATE seats … WHERE reservation_id = ? AND status = 'confirmed'`. It is keyed on the reservation id, not the seat numbers, so it can never free a seat that now belongs to someone else. A second cancel matches nothing and replays the first. Another user's reservation returns 404, exactly like a missing one.

## Idempotency

- **Where the key lives:** `reservations.idempotency_key`, with `UNIQUE (user_id, idempotency_key)`. It is taken from the `Idempotency-Key` header or the body's `idempotency_key`; both present and different is `400`. Keys are scoped per user, so one user can't collide with or probe another's.
- **How exactly-once is enforced:** the claim in step 1 is the first write of the transaction. A concurrent retry with the same key blocks on the unique index until the first transaction commits or rolls back. Then it either conflicts (and replays) or inserts (if the first one rolled back). Exactly one reservation can exist per (user, key).
- **Same key, same body:** `request_hash` is a SHA-256 of the show id and the sorted seat list. On conflict, a matching hash returns the original reservation as `201` with the same `reservation_id`. It doesn't touch the quota or the seats, and it's counted as `idempotent_replay`.
- **Same key, different body:** the hash differs, so `409 idempotency_key_reused`.
- **Declined attempts don't burn the key.** A `seat_taken` or `per_user_limit` rolls the whole transaction back, including the reservation row, so the key is free for a later retry.
- **Retry without the key.** A request for exactly the seats the user already holds in one confirmed reservation returns that reservation, whatever key it carries, rather than `seat_taken` for the user's own seats.

## Holds and expiry

Reserve confirms immediately; there is no payment step to wait for. Seats are released only by an explicit owner cancel. The schema already allows a `held` status, and `available + held + confirmed == total_seats` is checked everywhere, but nothing creates holds yet: `held` is always 0.

The time-boxed version (see *What I'd do next*) would make reserve create `held` with a `held_until`, and add `POST /reservations/{id}/confirm` guarded by `WHERE status = 'held' AND held_until > now()`. Expired holds would be reclaimed atomically by the reserve guard itself (`status = 'available' OR (status = 'held' AND held_until < now())`), so correctness doesn't depend on a background job. A sweeper would only keep the counts and metrics tidy.

## Consistency vs availability

I chose consistency. There is one PostgreSQL primary and the app holds no state. If the app can't reach the database, it can't decide anything, so it doesn't pretend to:

- **Readiness fails closed.** `/actuator/health/readiness` runs a real query through its own one-connection pool, with a 2s timeout, so it isn't starved by a saturated request pool. While it fails, the platform keeps traffic away and a new deploy never goes live.
- **No local fallbacks.** There is no cache of seat state and no "accept now, reconcile later". During a database partition, reservations fail rather than risk selling a seat twice. Selling the same seat twice is unrecoverable; a failed request can be retried.
- **Load beyond capacity queues.** Requests wait for one of 40 connections rather than fail fast. Under the Neon setup, waits beyond Hikari's 30s timeout surfaced as 500s (24 in one burst). Moving the database next to the app (Railway private network) cut the time each transaction holds a connection from 237ms to 19ms, and that burst went to zero 5xx. A fast `429` once the queue wait passes a budget is listed under next steps.

## Observability: what pages at 2am

Metrics (`/tbs/actuator/prometheus`, public; `/tbs/internal/metrics` is the same output behind Basic auth for Grafana Cloud's scraper):

- `reservations_confirmed_total`, `reservations_declined_total{reason}`, `reservations_cancelled_total`, all incremented only after the transaction commits.
- `seats{show,status}`, read from the database at scrape time, so it reconciles across instances and with `GET /shows/{id}`.
- `seats_invariant_violations`: shows where `available + held + confirmed != total_seats`.
- `db_transaction_retries_total{sqlstate}`, `http_server_requests_seconds` histograms, Hikari pool gauges, JVM.

Logs: one JSON line per request, with `request_id` (from `X-Request-Id`, or generated and echoed back), `user_id`, `show_id`, `reservation_id`, `outcome`, `status` and `duration_ms`. They go to stdout (Railway) and to Grafana Loki.

Dashboard and alerts are in Grafana Cloud. The dashboard is public, linked from the README; its source is `monitoring/grafana-dashboard.json`. Rules are in `monitoring/alerts.yml`.

| Alert | Severity | Why it pages |
|---|---|---|
| Any 5xx in 5 minutes | page | Every domain outcome is a 4xx by design, so a 5xx is a bug or an infrastructure failure. |
| `seats_invariant_violations > 0` | page | Should be impossible; means seats were lost or double-counted. Stop selling. |
| Scrape `up == 0` for 2m | page | Instance down or unreachable. |
| Reserve p99 > 2s for 5m | page | Users are timing out; usually pool saturation or a slow database. |
| Pool waiters > 0 for 5m | warn | Short spikes are expected during an on-sale; sustained queueing means undersized. |
| Transaction retries > 0 | warn | Lock ordering should prevent deadlocks; a retry means some path broke the order. |
| Seat gauge refresh failures | warn | The seat and invariant metrics are blind; usually a database problem. |

## Results

Live burst against the Railway deployment (`./burst.sh <live-url> --concurrency 500`, 20,000 requests): **all 20 checks pass**.

- 0 5xx and 0 transport errors.
- Exactly one winner per hot seat, 0 double-sold seats, and no user over the limit of 4.
- `0 + 0 + 1000 == 1000`, and every counter matches the API's responses.
- 903 req/s, p50 371ms, p99 5.3s.

At `--concurrency 2000` from the same laptop, the service still returned 0 5xx and every correctness check held. 121 requests (0.6%) failed to connect or finish the TLS handshake before reaching the app. The app's request count and an idle CPU point to the single client network, not the service. I haven't verified that from a better-connected machine yet.

## AI usage

> **To complete by the author.** The brief asks for "directed vs decided, be specific and honest". Only the author knows which calls were theirs, so this section is a factual list of what the AI assistant did in the final deploy and observability session, for the author to edit and extend with the earlier steps.

Done by the AI assistant (Claude Code) in the deploy and observability session, each change reviewed and committed by the author:

- **Railway setup:** `railway.json`; a Flyway `beforeMigrate` callback that creates the DML-only `app_runner` role from environment variables (no manual database setup); `PG*` fallbacks in `application.yaml`.
- **Bug diagnosis and fixes:**
  - `smoke.sh` failing under `pipefail`.
  - A test fixture that broke the seat invariant and made CI order-dependent.
  - Race tests resetting connections on macOS (128-connection listen backlog).
  - Flyway placeholders not substituted inside dollar-quoted blocks.
  - A CI break after the credentials were changed to `PG*` only.
- **Load analysis:** ran the live bursts and found that the 500s were Hikari connection timeouts caused by database latency on Neon. It recommended moving the database into the Railway project; the author made the call.
- **Grafana:** the Loki log shipping format, the Basic-auth scrape endpoint (Grafana Cloud refuses unauthenticated targets), and the dashboard changes for public sharing (no template variables).
- **Writing:** this write-up's first draft and the README sections.

**Decided by the author:** The stack, the schema and transaction design, the two-role database setup, Railway over the alternatives, what to accept or reject from the assistant's suggestions.

## What I'd do next

1. **Time-boxed holds with confirmation** (above). It's the real ticketing flow, and the schema already has room for it.
2. **Shed load as a 4xx.** Return `429 busy` when waiting for a database connection passes a small budget (say 2s), instead of a 500 at Hikari's 30s timeout. Clients then get a fast, honest "try again" during spikes beyond capacity.
3. **Find the real concurrency ceiling** by running the burst from a cloud VM near the Railway region at 2k, 5k and 10k concurrent. Then tune the pool size and database compute from the numbers.
4. **Hot-seat pre-filter.** A Redis set of sold seats, updated after commit, to turn away the bulk of a hot-seat storm before it reaches PostgreSQL. It stays advisory: the guarded `UPDATE` remains the only decision.
5. **Partition by show** (separate tables or databases for the biggest on-sales) if one show's stampede ever starts hurting the others.
