#!/usr/bin/env bash
# End-to-end smoke test against a running instance.
#   ./scripts/smoke.sh http://localhost:8085/tbs
# Checks readiness, auth, create show, reserve (201), the same seat again (409),
# the reconciliation invariant, and that metrics are exposed. Exits non-zero on any failure.
set -euo pipefail

BASE_URL="${1:?usage: smoke.sh <BASE_URL, e.g. http://localhost:8085/tbs>}"
BASE_URL="${BASE_URL%/}"
fail() { echo "FAIL: $*" >&2; exit 1; }
json_field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p"; }

status=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health/readiness")
[ "$status" = 200 ] || fail "readiness returned $status"
echo "ok   readiness 200"

token() {
  curl -fsS -X POST "$BASE_URL/auth/token" -H 'Content-Type: application/json' -d "$1" | json_field access_token
}
admin=$(token '{"user_id":"smoke-admin","role":"admin"}')
alice=$(token '{"user_id":"smoke-alice"}')
bob=$(token '{"user_id":"smoke-bob"}')
[ -n "$admin" ] && [ -n "$alice" ] && [ -n "$bob" ] || fail "could not obtain tokens"
echo "ok   tokens issued"

show=$(curl -fsS -X POST "$BASE_URL/shows" -H "Authorization: Bearer $admin" -H 'Content-Type: application/json' \
  -d '{"name":"smoke","seats":["A1","A2","A3"],"price_paise":25000}' | json_field id)
[ -n "$show" ] || fail "could not create show"
echo "ok   show created $show"

reserve() {
  curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/shows/$show/reserve" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"seats\":[\"A1\"],\"idempotency_key\":\"$2\"}"
}
[ "$(reserve "$alice" "smoke-$RANDOM-1")" = 201 ] || fail "first reserve was not 201"
echo "ok   reserve A1 -> 201"
[ "$(reserve "$bob" "smoke-$RANDOM-2")" = 409 ] || fail "second reserve of A1 was not 409"
echo "ok   reserve A1 again -> 409"

counts=$(curl -fsS "$BASE_URL/shows/$show")
available=$(echo "$counts" | sed -nE 's/.*"available":([0-9]+).*/\1/p')
held=$(echo "$counts" | sed -nE 's/.*"held":([0-9]+).*/\1/p')
confirmed=$(echo "$counts" | sed -nE 's/.*"confirmed":([0-9]+).*/\1/p')
[ "$available" = 2 ] && [ "$held" = 0 ] && [ "$confirmed" = 1 ] \
  || fail "unexpected counts available=$available held=$held confirmed=$confirmed"
echo "ok   invariant 2 + 0 + 1 == 3"

# Fetch first: piping into grep -q closes the pipe early, so curl fails (23) under pipefail.
metrics=$(curl -fsS "$BASE_URL/actuator/prometheus") || fail "metrics endpoint unreachable"
grep -q '^reservations_confirmed_total' <<<"$metrics" || fail "metrics not exposed"
echo "ok   metrics exposed"
echo "SMOKE PASSED"
