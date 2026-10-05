#!/usr/bin/env bash
# On-sale stampede against a running instance, then a correctness audit of the outcome.
#
#   ./burst.sh <BASE_URL> [--concurrency N] [--seats N] [--users N] [--random N] [--storm N] [--hot N] [--seed N]
#   ./burst.sh http://localhost:8085/tbs
#   ./burst.sh https://<your-app>/tbs --concurrency 2000
#
# Creates a fresh show, mints tokens for thousands of users, fires ~20k reservations at once
# (hot-seat storm, random seats, same-key retries, same key with different seats, per-user-limit
# abusers), prints the outcome distribution and latency, then checks every guarantee from the
# API's own responses, GET /shows/{id} and /actuator/prometheus. Exits 1 if any check fails.
#
# Needs only bash, curl 7.84+ and awk.
#
# Local compose stack on macOS: Docker Desktop / Colima port forwarding can't take thousands of
# simultaneous connections, so run the generator inside the compose network instead:
#   BURST_DOCKER_NETWORK=ticket-booking-system_default ./burst.sh http://app:8085/tbs
set -euo pipefail

if [ -n "${BURST_DOCKER_NETWORK:-}" ] && [ -z "${BURST_IN_DOCKER:-}" ]; then
  dir="$(cd "$(dirname "$0")" && pwd)"
  exec docker run --rm --network "$BURST_DOCKER_NETWORK" -e BURST_IN_DOCKER=1 -v "$dir/burst.sh:/burst.sh:ro" \
    alpine:3.20 sh -c 'apk add --no-cache -q bash curl >/dev/null && ulimit -n 65536 && bash /burst.sh "$@"' -- "$@"
fi

# ---- configuration ----------------------------------------------------------------------------
BASE_URL="${1:-}"
[ -n "$BASE_URL" ] && [ "${BASE_URL#--}" = "$BASE_URL" ] || {
  echo "usage: $0 <BASE_URL, e.g. http://localhost:8085/tbs> [--concurrency N] [--seats N] [--users N] [--random N] [--storm N] [--hot N] [--seed N]" >&2
  exit 2
}
BASE_URL="${BASE_URL%/}"
shift
SEATS=1000 USERS=5000 LIMIT=4
HOT=5 STORM=500 RANDOM_REQS=12500
RETRY_KEYS=1000 RETRY_COPIES=3 MISMATCH_KEYS=500
ABUSERS=50 ABUSER_REQS=20
CONCURRENCY=2000 SEED=""
while [ $# -ge 2 ]; do
  case "$1" in
    --concurrency) CONCURRENCY=$2 ;; --seats) SEATS=$2 ;; --users) USERS=$2 ;; --random) RANDOM_REQS=$2 ;;
    --storm) STORM=$2 ;; --hot) HOT=$2 ;; --seed) SEED=$2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift 2
done
[ "$SEATS" -le 2600 ] || { echo "at most 2600 seats" >&2; exit 2; }

PER_PROCESS=250                                   # curl --parallel-max per process
PROCESSES=$(( (CONCURRENCY + PER_PROCESS - 1) / PER_PROCESS ))
ulimit -n "$(ulimit -Hn | sed 's/unlimited/65536/')" 2>/dev/null || ulimit -n 10240 2>/dev/null || true

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/tokens" "$WORK/out"
RUN="$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
[ -n "$SEED" ] || SEED=$(( 0x$RUN % 1000000007 ))

die() { echo "ERROR: $*" >&2; exit 2; }
now() {
  perl -MTime::HiRes=time -e 'printf "%.3f", time' 2>/dev/null && return
  t=$(date +%s.%N); case "$t" in *N*) date +%s ;; *) echo "$t" ;; esac
}
field() { sed -nE "s/.*\"$1\":\"([^\"]*)\".*/\1/p"; }
count() { sed -nE "s/.*\"$1\":([0-9]+).*/\1/p"; }

curl --version | awk 'NR==1 { split($2, v, "."); if (v[1] < 7 || (v[1] == 7 && v[2] < 84)) exit 1 }' \
  || die "curl 7.84+ required (found $(curl --version | head -1))"

echo "Burst against $BASE_URL (run $RUN, seed $SEED)"
echo

ready=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health/readiness" || true)
[ "$ready" = 200 ] || die "readiness returned $ready"

# ---- setup: show, users, tokens ---------------------------------------------------------------
token() {
  curl -fsS -X POST "$BASE_URL/auth/token" -H 'Content-Type: application/json' -d "$1" | field access_token
}
admin=$(token "{\"user_id\":\"burst-$RUN-admin\",\"role\":\"admin\"}")
[ -n "$admin" ] || die "could not get an admin token"

seat_list=$(awk -v n="$SEATS" 'BEGIN { for (i = 0; i < n; i++) printf "%s\"%c%d\"", (i ? "," : ""), 65 + int(i / 100), i % 100 + 1 }')
show=$(curl -fsS -X POST "$BASE_URL/shows" -H "Authorization: Bearer $admin" -H 'Content-Type: application/json' \
  -d "{\"name\":\"burst-$RUN\",\"price_paise\":25000,\"per_user_limit\":$LIMIT,\"seats\":[$seat_list]}" | field id)
[ -n "$show" ] || die "could not create the show"

t0=$(now)
awk -v users="$USERS" -v run="$RUN" -v base="$BASE_URL" -v dir="$WORK/tokens" 'BEGIN {
  for (i = 0; i < users; i++) {
    if (i) print "next"
    printf "url = \"%s/auth/token\"\nheader = \"Content-Type: application/json\"\n", base
    printf "data = \"{\\\"user_id\\\":\\\"burst-%s-u%d\\\"}\"\noutput = \"%s/u%d.json\"\n", run, i, dir, i
  }
}' > "$WORK/tokens.cfg"
curl -s --parallel --parallel-max 100 -K "$WORK/tokens.cfg" || true
# Retry any user whose token is missing (one at a time; there should be none or very few).
for i in $(seq 0 $(( USERS - 1 ))); do
  grep -q access_token "$WORK/tokens/u$i.json" 2>/dev/null || \
    curl -s -X POST "$BASE_URL/auth/token" -H 'Content-Type: application/json' \
      -d "{\"user_id\":\"burst-$RUN-u$i\"}" -o "$WORK/tokens/u$i.json" || true
done
find "$WORK/tokens" -name '*.json' | xargs awk '
  match($0, /"access_token":"[^"]*"/) {
    f = FILENAME; sub(/.*\//, "", f); sub(/\.json$/, "", f)
    print f "|" substr($0, RSTART + 16, RLENGTH - 17)
  }' > "$WORK/tokens.txt"
minted=$(wc -l < "$WORK/tokens.txt" | tr -d ' ')
[ "$minted" -eq "$USERS" ] || die "only minted $minted of $USERS tokens"
printf 'Setup: show %s with %d seats (limit %d), %d user tokens in %.1fs\n' "$show" "$SEATS" "$LIMIT" "$USERS" \
  "$(awk -v a="$t0" -v b="$(now)" 'BEGIN { print b - a }')"

# ---- workload: idx|scenario|user|seats|key, shuffled ------------------------------------------
awk -v seats="$SEATS" -v users="$USERS" -v abusers="$ABUSERS" -v hot="$HOT" -v storm="$STORM" \
    -v random="$RANDOM_REQS" -v rkeys="$RETRY_KEYS" -v rcopies="$RETRY_COPIES" -v mkeys="$MISMATCH_KEYS" \
    -v areqs="$ABUSER_REQS" -v seed="$SEED" -v run="$RUN" '
  function seat(i) { return sprintf("%c%d", 65 + int(i / 100), i % 100 + 1) }
  function cold()  { return seat(hot + int(rand() * (seats - hot))) }
  function user()  { return "u" int(rand() * regular) }
  function emit(scenario, u, s, key) { printf "%d|%d|%s|%s|%s|%s\n", rand() * 1e9, n, scenario, u, s, key; n++ }
  BEGIN {
    srand(seed); regular = users - abusers
    for (h = 0; h < hot; h++)                                   # hot-seat storm, distinct users per seat
      for (i = 0; i < storm; i++) emit("hot-seat storm", "u" ((h * storm + i) % regular), seat(h), run "-s" n)
    for (i = 0; i < random; i++) {                              # random seats, 1 or 2 per request
      s = cold(); if (rand() < 0.5) { do t = cold(); while (t == s); s = s "," t }
      emit("random seats", user(), s, run "-r" n)
    }
    for (k = 0; k < rkeys; k++) {                               # same key and body, sent concurrently
      u = user(); s = cold(); for (c = 0; c < rcopies; c++) emit("same-key retries", u, s, run "-k" k)
    }
    for (k = 0; k < mkeys; k++) {                               # same key, different seats
      u = user(); s = cold(); do t = cold(); while (t == s)
      emit("same key, different seats", u, s, run "-m" k); emit("same key, different seats", u, t, run "-m" k)
    }
    for (a = 0; a < abusers; a++)                               # way over the per-user limit, in parallel
      for (i = 0; i < areqs; i++) emit("limit abusers", "u" (regular + a), cold(), run "-a" n)
  }' | sort -n | cut -d'|' -f2- > "$WORK/work.txt"
total=$(wc -l < "$WORK/work.txt" | tr -d ' ')

# One curl config per process; each request writes its body to out/<idx>.json and one status line to stdout.
awk -F'|' -v procs="$PROCESSES" -v base="$BASE_URL" -v show="$show" -v out="$WORK/out" -v cfg="$WORK/req" '
  NR == FNR { token[$1] = $2; next }
  {
    split($4, s, ","); body = ""
    for (i = 1; i in s; i++) body = body (i > 1 ? "," : "") "\\\"" s[i] "\\\""
    f = cfg ((FNR - 1) % procs) ".cfg"
    if (f in started) print "next" > f; started[f] = 1
    printf "url = \"%s/shows/%s/reserve\"\n", base, show > f
    printf "header = \"Authorization: Bearer %s\"\nheader = \"Content-Type: application/json\"\n", token[$3] > f
    printf "data = \"{\\\"seats\\\":[%s],\\\"idempotency_key\\\":\\\"%s\\\"}\"\n", body, $5 > f
    printf "output = \"%s/%d.json\"\nmax-time = 120\n", out, $1 > f
    printf "write-out = \"%d|%%{http_code}|%%header{idempotent-replayed}|%%{time_total}|%%{exitcode}\\n\"\n", $1 > f
  }' "$WORK/tokens.txt" "$WORK/work.txt"

metrics() { curl -s "$BASE_URL/actuator/prometheus" || true; }
metrics > "$WORK/metrics.before"

# ---- fire -------------------------------------------------------------------------------------
echo "Firing $total requests (concurrency $(( PROCESSES * PER_PROCESS )), $PROCESSES curl processes)..."
fire_start=$(now)
for p in $(seq 0 $(( PROCESSES - 1 ))); do
  curl -s --parallel --parallel-immediate --parallel-max "$PER_PROCESS" -K "$WORK/req$p.cfg" > "$WORK/status$p.txt" &
done
wait
wall=$(awk -v a="$fire_start" -v b="$(now)" 'BEGIN { w = b - a; print (w > 0 ? w : 0.001) }')
cat "$WORK"/status*.txt > "$WORK/status.txt"

# Response bodies -> idx|reservation_id|error|seats
find "$WORK/out" -name '*.json' | xargs awk '
  {
    f = FILENAME; sub(/.*\//, "", f); sub(/\.json$/, "", f)
    rid = ""; err = ""; seats = ""
    if (match($0, /"reservation_id":"[^"]*"/)) rid = substr($0, RSTART + 18, RLENGTH - 19)
    if (match($0, /"error":"[^"]*"/)) err = substr($0, RSTART + 9, RLENGTH - 10)
    if (match($0, /"seats":\[[^]]*\]/)) { seats = substr($0, RSTART + 9, RLENGTH - 10); gsub(/"/, "", seats) }
    print f "|" rid "|" err "|" seats
  }' > "$WORK/bodies.txt"

curl -fsS "$BASE_URL/shows/$show" > "$WORK/show.json"
metrics > "$WORK/metrics.after"

# ---- report and audit -------------------------------------------------------------------------
# results: idx|scenario|user|key|code|replayed|time|exitcode|rid|error|seats
awk -F'|' -v OFS='|' '
  FILENAME ~ /work.txt$/   { sc[$1] = $2; us[$1] = $3; ky[$1] = $5; next }
  FILENAME ~ /status.txt$/ { st[$1] = $2 "|" $3 "|" $4 "|" $5; next }
  FILENAME ~ /bodies.txt$/ { bd[$1] = $2 "|" $3 "|" $4; next }
  END {
    for (i in sc) {
      s = (i in st) ? st[i] : "000||0|-1"
      b = (i in bd) ? bd[i] : "||"
      print i, sc[i], us[i], ky[i], s, b
    }
  }' "$WORK/work.txt" "$WORK/status.txt" "$WORK/bodies.txt" > "$WORK/results.txt"

cut -d'|' -f7 "$WORK/results.txt" | sort -n > "$WORK/times.txt"

awk -F'|' -v wall="$wall" -v total="$total" -v hot="$HOT" -v limit="$LIMIT" -v users="$USERS" -v abusers="$ABUSERS" \
    -v seats_total="$SEATS" -v times="$WORK/times.txt" -v show_file="$WORK/show.json" \
    -v before="$WORK/metrics.before" -v after="$WORK/metrics.after" -v show="$show" '
  function outcome(r) {
    if (code[r] == "000") return "transport error (curl exit " exitc[r] ")"
    if (code[r] == "201") return replayed[r] == "true" ? "201 replayed (idempotent)" : "201 confirmed"
    return code[r] " " (err[r] == "" ? "(no error code)" : err[r])
  }
  function short(r) {
    if (code[r] == "000") return "transport_error"
    if (code[r] == "201") return replayed[r] == "true" ? "replayed" : "confirmed"
    return err[r]
  }
  function check(ok, msg) { nchecks++; if (!ok) failures++; printf "  %s  %s\n", (ok ? "PASS" : "FAIL"), msg }
  function seatname(i) { return sprintf("%c%d", 65 + int(i / 100), i % 100 + 1) }
  function metric(file, name, label,    line, total, v) {
    total = 0
    while ((getline line < file) > 0) {
      if (index(line, name "{") != 1) continue
      if (label != "" && index(line, label) == 0) continue
      v = line; sub(/.*[ \t]/, "", v); total += v
    }
    close(file); return total
  }
  {
    r = $1; scenario[r] = $2; user[r] = $3; key[r] = $4; code[r] = $5; replayed[r] = $6
    exitc[r] = $8; rid[r] = $9; err[r] = $10; rseats[r] = $11
    out[outcome(r)]++
    sc_total[$2]++; sc_out[$2 SUBSEP short(r)]++; scen[$2] = 1; outs[short(r)] = 1
    if ($5 ~ /^5/) fivexx++
    if ($5 == "000") transport++
    if ($5 ~ /^4/ && $5 != "409") other4xx++
    if ($5 == "201") {
      if ($6 == "true") replays++; else created++
      res_seats[$9] = $11
      k = $3 SUBSEP $4; if (!((k SUBSEP $9) in key_rid)) { key_rid[k SUBSEP $9] = 1; key_count[k]++ }
      ur = $3 SUBSEP $9; if (!(ur in user_res)) { user_res[ur] = 1; user_seats[$3] += split($11, tmp, ",") }
    }
    if ($10 != "") decl[$10]++
  }
  END {
    printf "\nDone in %.2fs: %.0f requests/s\n\nOutcomes\n", wall, total / wall
    cmd = "sort"; for (o in out) printf "  %-40s %7d\n", o, out[o] | cmd; close(cmd)
    printf "  %-40s %7d\n  %-40s %7d\n  %-40s %7d\n", "= total", total, "= 4xx other than 409", other4xx, "= 5xx", fivexx

    print "\nBy scenario"
    for (s in scen) {
      line = sprintf("  %-28s %6d sent:", s, sc_total[s])
      for (o in outs) if ((s SUBSEP o) in sc_out) line = line " " o "=" sc_out[s SUBSEP o]
      print line
    }

    n = 0; while ((getline t < times) > 0) lat[++n] = t; close(times)
    printf "\nLatency  p50 %.0fms  p95 %.0fms  p99 %.0fms  max %.0fms\n", \
      lat[int(n * 0.50 + 0.999)] * 1000, lat[int(n * 0.95 + 0.999)] * 1000, lat[int(n * 0.99 + 0.999)] * 1000, lat[n] * 1000

    print "\nChecks"
    check(fivexx == 0, "zero 5xx (" fivexx + 0 ")")
    check(transport == 0, "every request got an HTTP response (" transport + 0 " transport errors)")

    distinct = 0; double_sold = 0; sold = 0
    for (id in res_seats) {
      distinct++; m = split(res_seats[id], ss, ",")
      for (j = 1; j <= m; j++) { sold++; if (ss[j] in owner && owner[ss[j]] != id) double_sold++; owner[ss[j]] = id }
    }
    check(created == distinct, "each reservation was created once; every other 201 for it was a replay (" created + 0 " created, " distinct " distinct)")
    check(double_sold == 0, "no seat in two reservations (" double_sold " double-sold)")

    for (h = 0; h < hot; h++) {
      seat = seatname(h); winners = 0
      for (id in res_seats) { m = split(res_seats[id], ss, ","); for (j = 1; j <= m; j++) if (ss[j] == seat) winners++ }
      check(winners == 1, "hot seat " seat ": exactly 1 winner (" winners ")")
    }

    two = 0; for (k in key_count) if (key_count[k] > 1) two++
    check(two == 0, "no idempotency key produced two reservations (" two ")")

    worst = 0; over = 0
    for (u in user_seats) { if (user_seats[u] > limit) over++ }
    for (a = 0; a < abusers; a++) { u = "u" (users - abusers + a); if (user_seats[u] > worst) worst = user_seats[u] }
    check(worst <= limit && over == 0, "per-user limit " limit " held (worst abuser " worst + 0 " seats, " over + 0 " users over the limit)")

    getline showjson < show_file; close(show_file)
    available = held = confirmed = ctotal = -1
    if (match(showjson, /"available":[0-9]+/)) available = substr(showjson, RSTART + 12, RLENGTH - 12) + 0
    if (match(showjson, /"held":[0-9]+/)) held = substr(showjson, RSTART + 7, RLENGTH - 7) + 0
    if (match(showjson, /"confirmed":[0-9]+/)) confirmed = substr(showjson, RSTART + 12, RLENGTH - 12) + 0
    if (match(showjson, /"total":[0-9]+/)) ctotal = substr(showjson, RSTART + 8, RLENGTH - 8) + 0
    check(available + held + confirmed == seats_total && ctotal == seats_total, \
      "invariant: available " available " + held " held " + confirmed " confirmed " == " seats_total)
    check(confirmed == sold, "confirmed seats in GET /shows (" confirmed ") == seats in 201 responses (" sold ")")

    d = metric(after, "reservations_confirmed_total", "") - metric(before, "reservations_confirmed_total", "")
    check(d == created, "reservations_confirmed_total +" d " == " created + 0 " responses")
    split("seat_taken per_user_limit idempotency_key_reused", reasons, " ")
    for (i = 1; i <= 3; i++) {
      lbl = "reason=\"" reasons[i] "\""
      d = metric(after, "reservations_declined_total", lbl) - metric(before, "reservations_declined_total", lbl)
      check(d == decl[reasons[i]] + 0, "reservations_declined_total{" lbl "} +" d " == " decl[reasons[i]] + 0 " responses")
    }
    lbl = "reason=\"idempotent_replay\""
    d = metric(after, "reservations_declined_total", lbl) - metric(before, "reservations_declined_total", lbl)
    check(d == replays + 0, "reservations_declined_total{" lbl "} +" d " == " replays + 0 " responses")
    ga = metric(after, "seats", "show=\"" show "\",status=\"available\"")
    gc = metric(after, "seats", "show=\"" show "\",status=\"confirmed\"")
    check(ga == available && gc == confirmed, "seats gauge matches GET /shows (available " ga ", confirmed " gc ")")
    iv = metric(after, "seats_invariant_violations", "")
    check(iv == 0, "seats_invariant_violations == 0 (" iv ")")

    print ""
    if (failures == 0) { print "BURST PASSED: all " nchecks " checks hold"; exit 0 }
    print "BURST FAILED: " failures " of " nchecks " checks failed"; exit 1
  }' "$WORK/results.txt"
