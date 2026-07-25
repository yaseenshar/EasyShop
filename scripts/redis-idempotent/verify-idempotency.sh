#!/usr/bin/env bash
#
# verify-idempotency.sh
#
# The assertion that matters: not "the second call returned something" but
# "the SIDE EFFECT happened exactly once AND the second caller got the SAME
# response as the first." An idempotency engine that returns 200 twice but
# creates two orders has failed silently.
#
# Exercises the REAL @Idempotent-guarded checkout endpoint (order-service):
# POST /api/v1/orders — there is no /orders/checkout sub-path (see
# OrderController's javadoc). The engine is common-lib's Redis SET-NX-EX
# primitive (IdempotencyStore/IdempotencyInterceptor), wired via
# @Idempotent(required = true) on OrderController.createOrder. It sits IN
# FRONT of the pre-existing orderRepository.findByIdempotencyKey DB check,
# which remains as the correctness backstop for a Redis miss.
#
# Usage:
#   [CUSTOMER_PASSWORD=...] [ROPC_CLIENT_ID=...] [ROPC_CLIENT_SECRET=...] \
#   [CHECKOUT_PATH=/api/v1/orders] [PRODUCT_ID=...] [SHIPPING_ADDRESS_ID=...] \
#   [REDIS_CONTAINER=redis] ./verify-idempotency.sh
#
# .env (repo root) is sourced automatically for KEYCLOAK_GATEWAY_CLIENT_SECRET
# and REDIS_PASSWORD, same convention as scripts/integration-verification/verify-e2e.sh.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FOUND=""
for E in "$SCRIPT_DIR/.env" "$REPO_ROOT/.env" "./.env"; do
  if [ -f "$E" ]; then set -a; source "$E"; set +a; ENV_FOUND="$E"; break; fi
done

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
REALM="${REALM:-easyshop}"
# easyshop-gateway is the only client in the realm with directAccessGrantsEnabled
# (ROPC) - see infra/docker/keycloak/realms/easyshop-realm.json.
ROPC_CLIENT_ID="${ROPC_CLIENT_ID:-easyshop-gateway}"
ROPC_CLIENT_SECRET="${ROPC_CLIENT_SECRET:-${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}}"
CUSTOMER_USER="${CUSTOMER_USER:-demo.customer}"
CUSTOMER_PASSWORD="${CUSTOMER_PASSWORD:-Customer#Pass1}"
CHECKOUT_PATH="${CHECKOUT_PATH:-/api/v1/orders}"
ORDERS_PATH="${ORDERS_PATH:-/api/v1/orders}"
PRODUCT_ID="${PRODUCT_ID:-11111111-1111-1111-1111-111111111111}"
# Not existence-checked by order-service (see OrderDtos.CreateOrderRequest) -
# any well-formed UUID satisfies @NotNull.
SHIPPING_ADDRESS_ID="${SHIPPING_ADDRESS_ID:-$(uuidgen 2>/dev/null || python3 -c 'import uuid;print(uuid.uuid4())')}"
REDIS_CONTAINER="${REDIS_CONTAINER:-redis}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
KEY_HEADER="${KEY_HEADER:-Idempotency-Key}"

echo "[diag] .env sourced from: ${ENV_FOUND:-<NOT FOUND>} | ROPC_CLIENT_SECRET is $( [ -n "$ROPC_CLIENT_SECRET" ] && echo "set (${#ROPC_CLIENT_SECRET} chars)" || echo EMPTY )" >&2

PASS=0; FAIL=0; SKIP=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
note() { printf '       %s\n' "$1"; }

redis_cli() {  # redis_cli <args...> — auth-aware wrapper (Redis runs with --requirepass)
  if [ -n "$REDIS_PASSWORD" ]; then
    docker exec "$REDIS_CONTAINER" redis-cli --no-auth-warning -a "$REDIS_PASSWORD" "$@" 2>/dev/null
  else
    docker exec "$REDIS_CONTAINER" redis-cli "$@" 2>/dev/null
  fi
}

TOKEN=$(curl -s -d grant_type=password -d client_id="$ROPC_CLIENT_ID" \
  ${ROPC_CLIENT_SECRET:+-d client_secret="$ROPC_CLIENT_SECRET"} \
  --data-urlencode "username=$CUSTOMER_USER" --data-urlencode "password=$CUSTOMER_PASSWORD" \
  "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
[ -z "$TOKEN" ] && { echo "ABORT: could not mint customer token"; exit 2; }

order_count() {  # total orders visible to this customer (totalElements, not
                 # len(content) - content is page-limited, totalElements isn't,
                 # which matters once this customer has racked up >1 page of
                 # orders across repeated script runs).
  curl -s -H "Authorization: Bearer $TOKEN" "$GATEWAY_URL$ORDERS_PATH" \
    | python3 -c "import sys,json
try:
    d=json.load(sys.stdin)
    print(d['data']['totalElements'])
except Exception: print(-1)"
}

post() {  # $1 idem-key  $2 body  -> sets CODE, BODY, REPLAYED
  local out
  out=$(curl -s -w '\n%{http_code}' -X POST "$GATEWAY_URL$CHECKOUT_PATH" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        ${1:+-H "$KEY_HEADER: $1"} \
        -D /tmp/idem_hdrs \
        --data "$2")
  CODE=$(printf '%s' "$out" | tail -n1)
  BODY=$(printf '%s' "$out" | sed '$d')
  REPLAYED=$(grep -i '^Idempotent-Replayed:' /tmp/idem_hdrs | tr -d '\r' | awk '{print $2}')
}

KEY="test-$(date +%s)-$RANDOM"
PAYLOAD="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1,\"unitPrice\":9.99}],\"shippingAddressId\":\"$SHIPPING_ADDRESS_ID\"}"

echo "== [1] first call with a fresh key succeeds =="
BEFORE=$(order_count)
post "$KEY" "$PAYLOAD"
FIRST_CODE=$CODE; FIRST_BODY=$BODY
# 202 ACCEPTED, not 200/201 - checkout kicks off the saga async (see
# OrderController javadoc). Still 2xx, so the range check covers it.
if [ "$CODE" -ge 200 ] && [ "$CODE" -lt 300 ]; then pass "first checkout -> $CODE"
else fail "first checkout -> $CODE"; note "body: $(printf '%.200s' "$BODY")"; fi
if [ -z "$REPLAYED" ]; then pass "first response NOT marked replayed"; else fail "first response wrongly marked replayed"; fi

echo
echo "== [2] retry with SAME key + SAME body replays the SAME response =="
post "$KEY" "$PAYLOAD"
if [ "$CODE" = "$FIRST_CODE" ] && [ "$BODY" = "$FIRST_BODY" ]; then
  pass "identical status+body returned on retry"
else
  fail "retry differed: first=$FIRST_CODE now=$CODE"
fi
if [ "$REPLAYED" = "true" ]; then pass "Idempotent-Replayed: true header present"
else fail "replay header missing — response may have been re-computed, not replayed"; fi

echo
echo "== [3] THE key assertion: the side effect happened exactly ONCE =="
sleep 1
AFTER=$(order_count)
if [ "$BEFORE" -ge 0 ] && [ "$AFTER" -ge 0 ]; then
  DIFF=$((AFTER - BEFORE))
  if [ "$DIFF" -eq 1 ]; then pass "order count rose by exactly 1 across two identical calls"
  else fail "order count rose by $DIFF (expected 1) — duplicate side effect not prevented"; fi
else
  skip "could not read order count (align ORDERS_PATH / response shape)"
fi

echo
echo "== [4] concurrent duplicates: exactly one processes =="
KEY2="conc-$(date +%s)-$RANDOM"
BEFORE=$(order_count)
seq 1 10 | xargs -P 10 -I{} curl -s -o /dev/null -X POST "$GATEWAY_URL$CHECKOUT_PATH" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "$KEY_HEADER: $KEY2" --data "$PAYLOAD"
sleep 2
AFTER=$(order_count)
if [ "$BEFORE" -ge 0 ]; then
  DIFF=$((AFTER - BEFORE))
  if [ "$DIFF" -eq 1 ]; then pass "10 concurrent identical calls -> exactly 1 order"
  else fail "10 concurrent calls -> $DIFF orders (expected 1)"; fi
else skip "order count unreadable"; fi

echo
echo "== [5] same key, DIFFERENT body -> 422 (client bug caught, not silently wrong) =="
post "$KEY" "{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":99,\"unitPrice\":9.99}],\"shippingAddressId\":\"$SHIPPING_ADDRESS_ID\"}"
if [ "$CODE" = "422" ]; then pass "fingerprint mismatch -> 422"
else fail "reused key with new body -> $CODE (expected 422) — engine would return the WRONG cached result"; fi

echo
echo "== [6] missing key on a required endpoint -> 400 =="
post "" "$PAYLOAD"
if [ "$CODE" = "400" ]; then
  pass "missing Idempotency-Key -> 400"
  echo "$BODY" | grep -q "IDEMPOTENCY_KEY_REQUIRED" \
    && note "confirmed: rejected by the @Idempotent interceptor (IDEMPOTENCY_KEY_REQUIRED), not just Spring's own header binding" \
    || note "400 came from somewhere other than the interceptor's own check - verify @Idempotent(required=true) is still in effect"
else fail "missing key -> $CODE (expected 400)"; fi

echo
echo "== [7] in-progress marker uses a SHORT TTL (crash self-heal) =="
KEY3="ttl-$(date +%s)-$RANDOM"
post "$KEY3" "$PAYLOAD" >/dev/null 2>&1 &   # kick a request
sleep 0.05
# Find the in-progress key's TTL directly in Redis; it must be small (< a few
# minutes), NOT the 24h completed TTL. Namespace/sub make the exact key hard to
# guess, so scan. idem:{namespace}:{sub}:{key} - namespace defaults to
# spring.application.name ("order-service"), see IdempotencyAutoConfiguration.
FOUND_KEY=$(redis_cli --scan --pattern 'idem:*' | head -1)
TTL=$([ -n "$FOUND_KEY" ] && redis_cli ttl "$FOUND_KEY" | tr -d '\r')
wait 2>/dev/null
if [ -n "$TTL" ] && [ "$TTL" -gt 0 ] 2>/dev/null && [ "$TTL" -le 300 ]; then
  pass "an idem key had TTL ${TTL}s (short, self-healing)"
elif [ -n "$TTL" ] && [ "$TTL" -gt 300 ] 2>/dev/null; then
  note "observed TTL ${TTL}s — likely the COMPLETED record (long TTL is correct there)"
  skip "could not isolate the in-progress marker; check manually mid-flight"
else
  skip "could not read a TTL from Redis (container name? auth? REDIS_PASSWORD set?)"
fi

echo
echo "=============================================="
echo "Results: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ] || exit 1
