#!/usr/bin/env bash
#
# verify-circuit-breakers.sh
#
# THE ASSERTION THAT MATTERS: a fallback response does NOT prove the breaker
# opened — any error path produces a fallback. The breaker's signature is
# FAST FAILURE. So this script asserts LATENCY and ACTUATOR STATE, not just
# response bodies.
#
# Usage:
#   [GATEWAY_URL=...] [TARGET_SERVICE=catalog-service] [CB_NAME=catalogCb] \
#     ./verify-circuit-breakers.sh

set -uo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
TARGET_SERVICE="${TARGET_SERVICE:-easyshop-catalog-service-1}"
TARGET_PATH="${TARGET_PATH:-/api/v1/products}"
CB_NAME="${CB_NAME:-catalogCb}"
CB_ACTUATOR="${CB_ACTUATOR:-$GATEWAY_URL/actuator/circuitbreakers}"
TRIP_CALLS="${TRIP_CALLS:-15}"          # > minimumNumberOfCalls (10)
WAIT_OPEN="${WAIT_OPEN:-12}"            # > waitDurationInOpenState (10s)
FAST_FAIL_MS="${FAST_FAIL_MS:-300}"     # open-state responses must beat this

PASS=0; FAIL=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
note() { printf '       %s\n' "$1"; }

# code + elapsed-ms in one shot
call() {
  local out
  out=$(curl -s -o /tmp/cb_body -w '%{http_code} %{time_total}' "$GATEWAY_URL$TARGET_PATH")
  CODE=$(echo "$out" | cut -d' ' -f1)
  MS=$(python3 -c "print(int(float('$(echo "$out" | cut -d' ' -f2)')*1000))")
}

cb_state() {
  curl -s "$CB_ACTUATOR" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    cbs = d.get('circuitBreakers', d)
    e = cbs.get('$CB_NAME')
    print(e.get('state','?') if isinstance(e, dict) else '?')
except Exception:
    print('UNREADABLE')"
}

echo "== [1] Baseline: healthy call =="
call
if [ "$CODE" = "200" ]; then pass "healthy -> 200 in ${MS}ms"; else fail "healthy -> $CODE (fix before continuing)"; exit 1; fi
BASE_MS=$MS

echo
echo "== [2] Breaker starts CLOSED =="
S=$(cb_state)
if [ "$S" = "CLOSED" ]; then pass "state=CLOSED"
elif [ "$S" = "UNREADABLE" ]; then fail "cannot read $CB_ACTUATOR — expose 'circuitbreakers' in management.endpoints.web.exposure.include"; exit 1
else fail "state=$S (expected CLOSED)"; fi

echo
echo "== [3] Induce a HANG (docker pause) — this is what tests the timeout =="
docker pause "$TARGET_SERVICE" >/dev/null 2>&1 || { fail "could not pause $TARGET_SERVICE"; exit 1; }
note "paused $TARGET_SERVICE; sending $TRIP_CALLS calls"
SLOW_MS=0
for i in $(seq 1 "$TRIP_CALLS"); do
  call
  [ "$i" = "1" ] && SLOW_MS=$MS
done
note "first failing call took ${SLOW_MS}ms (should be ~the TimeLimiter timeout)"

echo
echo "== [4] Breaker is OPEN =="
S=$(cb_state)
if [ "$S" = "OPEN" ]; then pass "state=OPEN"
else
  fail "state=$S after $TRIP_CALLS failures"
  note "most likely: minimumNumberOfCalls still 100 (library default) — the"
  note "breaker is obeying config you never set. Or the instance name in"
  note "resilience4j.circuitbreaker.instances does not match the filter's name arg."
fi

echo
echo "== [5] FAST FAIL — the real proof =="
call
if [ "$MS" -lt "$FAST_FAIL_MS" ]; then
  pass "open-state call returned in ${MS}ms (<${FAST_FAIL_MS}ms) vs ${SLOW_MS}ms while closed"
else
  fail "open-state call took ${MS}ms — not fast-failing; breaker likely never opened"
fi
if [ "$CODE" = "503" ]; then pass "fallback -> 503"
else fail "fallback -> $CODE (expected 503; a 200 fallback teaches clients that degradation is success)"; fi
if grep -q "errorCode" /tmp/cb_body 2>/dev/null; then pass "fallback body carries ApiResponse errorCode"
else fail "fallback body missing errorCode envelope"; note "body: $(head -c 200 /tmp/cb_body)"; fi

echo
echo "== [6] Health indicator does not take the gateway down =="
H=$(curl -s "$GATEWAY_URL/actuator/health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null)
if [ "$H" = "DOWN" ]; then
  fail "aggregate health=DOWN while a DOWNSTREAM is sick"
  note "Eureka may deregister and Docker/K8s may restart a healthy gateway."
  note "Fix: allowHealthIndicatorToFail: false on the circuitbreaker config."
else
  pass "aggregate health=$H (gateway stays in rotation)"
fi

echo
echo "== [7] Auth path is NOT breaker-wrapped =="
AUTH_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY_URL/oauth2/authorization/keycloak")
if [ "$AUTH_CODE" = "302" ] || [ "$AUTH_CODE" = "303" ]; then pass "login redirect still works ($AUTH_CODE) during a downstream outage"
else fail "login returned $AUTH_CODE — a breaker on /oauth2/** breaks BFF login rather than degrading it"; fi

echo
echo "== [8] Recovery: HALF_OPEN -> CLOSED =="
docker unpause "$TARGET_SERVICE" >/dev/null 2>&1
note "unpaused; waiting ${WAIT_OPEN}s for waitDurationInOpenState"
sleep "$WAIT_OPEN"
for i in 1 2 3 4; do call; done
S=$(cb_state)
if [ "$S" = "CLOSED" ]; then pass "state=CLOSED (recovered)"
else fail "state=$S after recovery — check permittedNumberOfCallsInHalfOpenState"; fi

echo
echo "== [9] NEGATIVE: a 404 must NOT count as a downstream failure =="
BEFORE=$(cb_state)
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  curl -s -o /dev/null "$GATEWAY_URL$TARGET_PATH/00000000-0000-0000-0000-000000000000"
done
AFTER=$(cb_state)
if [ "$AFTER" = "CLOSED" ]; then pass "12x 404 left the breaker CLOSED (ignoreExceptions working)"
else fail "breaker moved $BEFORE -> $AFTER on 404s — 4xx is being recorded as failure"; note "one bad client pattern would open the breaker for every user"; fi

echo
echo "=============================================="
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1