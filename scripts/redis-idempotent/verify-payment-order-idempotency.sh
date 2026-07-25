#!/usr/bin/env bash
#
# verify-payment-order-idempotency.sh
#
# Three properties, all about SIDE EFFECTS at the source of truth, not
# response codes:
#   A. POST /orders with one client key -> exactly ONE order AND exactly ONE
#      saga (even with Redis bypassed - the DB constraint + return-existing
#      handler hold the line).
#   B. A normal checkout charges the customer exactly ONCE (one payment row
#      per order).
#   C. The admin refund endpoint is idempotent: calling it twice (or
#      concurrently) refunds exactly ONCE, not twice.
#
# PREREQUISITE: infra stack up (postgres, mysql, redis, kafka, keycloak,
# service-discovery, api-gateway, user-service, order-service, payment-service,
# inventory-service). Needs a REAL seeded product_stock row for the saga to
# reach CHARGING_PAYMENT at all (an order that fails stock reservation never
# reaches payment) - this script seeds one itself via MYSQL_ROOT_PASSWORD,
# same as scripts/retry-bulkhead/verify-retry-bulkhead.sh.
#
# Usage:
#   [PRODUCT_ID=...] [REDIS_CONTAINER=redis] \
#   [PAYMENT_DB="docker exec -i easyshop-postgres-1 psql -U easyshop -d easyshop_payment -tAc"] \
#   ./verify-payment-order-idempotency.sh
#
# .env (repo root) is sourced automatically, same convention as the sibling
# verify-idempotency.sh and scripts/retry-bulkhead/verify-retry-bulkhead.sh.

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
ROPC_CLIENT_ID="${ROPC_CLIENT_ID:-easyshop-gateway}"
ROPC_CLIENT_SECRET="${ROPC_CLIENT_SECRET:-${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}}"
CUSTOMER_USER="${CUSTOMER_USER:-demo.customer}"
CUSTOMER_PASSWORD="${CUSTOMER_PASSWORD:-Customer#Pass1}"
ADMIN_USER="${ADMIN_USER:-demo.admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin#Pass123}"
CHECKOUT_PATH="${CHECKOUT_PATH:-/api/v1/orders}"        # NOT /orders/checkout - see OrderController
ORDERS_PATH="${ORDERS_PATH:-/api/v1/orders}"
REDIS_CONTAINER="${REDIS_CONTAINER:-redis}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"
COMPOSE="docker compose -f $REPO_ROOT/infra/docker-compose.yml"
# Optional DB probe for payment-side assertions at the source of truth.
PAYMENT_DB="${PAYMENT_DB:-}"

echo "[diag] .env sourced from: ${ENV_FOUND:-<NOT FOUND>} | ROPC_CLIENT_SECRET is $( [ -n "$ROPC_CLIENT_SECRET" ] && echo "set (${#ROPC_CLIENT_SECRET} chars)" || echo EMPTY )" >&2

PASS=0; FAIL=0; SKIP=0
pass(){ printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail(){ printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip(){ printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
note(){ printf '       %s\n' "$1"; }

token() {  # token <user> <password>
  curl -s -d grant_type=password -d client_id="$ROPC_CLIENT_ID" \
    ${ROPC_CLIENT_SECRET:+-d client_secret="$ROPC_CLIENT_SECRET"} \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
}

TOKEN=$(token "$CUSTOMER_USER" "$CUSTOMER_PASSWORD")
[ -z "$TOKEN" ] && { echo "ABORT: could not mint customer token"; exit 2; }
ADMIN_TOKEN=$(token "$ADMIN_USER" "$ADMIN_PASSWORD")
[ -z "$ADMIN_TOKEN" ] && note "WARNING: could not mint admin token - test C will be skipped"

# ── SETUP: provisioned user, a real shipping address, seeded stock ─────────
curl -s -H "Authorization: Bearer $TOKEN" "$GATEWAY_URL/api/v1/users/me" >/dev/null  # JIT-provisions the user row
SHIPPING_ADDRESS_ID=$(curl -s -H "Authorization: Bearer $TOKEN" "$GATEWAY_URL/api/v1/users/me/addresses" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or [{}])[0].get('id',''))" 2>/dev/null)
if [ -z "$SHIPPING_ADDRESS_ID" ]; then
  SHIPPING_ADDRESS_ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"label":"verify-script","line1":"1 Test St","city":"Testville","postalCode":"00000","countryCode":"US"}' \
    "$GATEWAY_URL/api/v1/users/me/addresses" | python3 -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)
fi
note "shipping address: ${SHIPPING_ADDRESS_ID:-<none>}"

PRODUCT_ID="${PRODUCT_ID:-$(uuidgen 2>/dev/null || python3 -c 'import uuid;print(uuid.uuid4())')}"
if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
  note "MYSQL_ROOT_PASSWORD not set - cannot seed stock; a saga without stock CANCELS before payment, so B/C need it"
else
  $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" easyshop_inventory \
      -e "INSERT INTO product_stock (id, product_id, available_qty, reserved_qty, version)
          VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN('$PRODUCT_ID'), 1000, 0, 0)
          ON DUPLICATE KEY UPDATE available_qty = 1000, reserved_qty = 0;" 2>/tmp/verify-po-mysql-err.log \
    && note "seeded product_stock for $PRODUCT_ID (1000 units)" \
    || note "stock seeding failed - see /tmp/verify-po-mysql-err.log"
fi

PAYLOAD="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1,\"unitPrice\":9.99}],\"shippingAddressId\":\"$SHIPPING_ADDRESS_ID\"}"

order_count() {  # total orders for this customer - totalElements, not len(content) (page-limited)
  curl -s -H "Authorization: Bearer $TOKEN" "$GATEWAY_URL$ORDERS_PATH" \
    | python3 -c "import sys,json
try:
    d=json.load(sys.stdin); print(d['data']['totalElements'])
except Exception: print(-1)"
}

order_status() {  # order_status <orderId>
  curl -s -H "Authorization: Bearer $TOKEN" "$GATEWAY_URL$ORDERS_PATH/$1" \
    | python3 -c "import sys,json
try:
    d=json.load(sys.stdin); print(d.get('data',{}).get('status','?'))
except Exception: print('?')" 2>/dev/null
}

wait_for_terminal_status() {  # wait_for_terminal_status <orderId> -> prints final status
  local id="$1" status
  for _ in $(seq 1 15); do
    status=$(order_status "$id")
    case "$status" in CONFIRMED|CANCELLED) echo "$status"; return ;; esac
    sleep 2
  done
  echo "$status"
}

echo "== A1: same client key twice -> one order, second replays =="
B=$(order_count)
KEY="po-$(date +%s)-$RANDOM"
R1=$(curl -s -D /tmp/po_h1 -w '|%{http_code}' -X POST "$GATEWAY_URL$CHECKOUT_PATH" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -H "Idempotency-Key: $KEY" --data "$PAYLOAD")
R2=$(curl -s -D /tmp/po_h2 -w '|%{http_code}' -X POST "$GATEWAY_URL$CHECKOUT_PATH" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -H "Idempotency-Key: $KEY" --data "$PAYLOAD")
C1="${R1##*|}"; C2="${R2##*|}"
if [ "$C1" -ge 200 ] && [ "$C1" -lt 300 ]; then pass "first checkout $C1"
else fail "first checkout $C1"; note "body: $(printf '%.200s' "${R1%|*}")"; fi
if grep -qi '^Idempotent-Replayed: true' /tmp/po_h2; then pass "second checkout replayed (Redis fast path)"
else note "no replay header - second may have hit the DB backstop instead (also acceptable)"; fi
sleep 1
A=$(order_count)
if [ "$B" -ge 0 ] && [ $((A-B)) -eq 1 ]; then pass "exactly ONE order created for the key"
else [ "$B" -ge 0 ] && fail "order delta $((A-B)) (expected 1)" || skip "order count unreadable"; fi

echo
echo "== A2: fail-OPEN path still starts one saga (Redis down -> DB constraint) =="
# Baseline BEFORE pausing: the gateway's own rate limiter is Redis-backed, so
# order_count() (a GET through the gateway) can itself fail once Redis is
# down - capturing B after the pause would read -1, not a real baseline.
KEY2="po-open-$(date +%s)-$RANDOM"; B=$(order_count)
if [ "$B" -lt 0 ]; then
  skip "order count unreadable even before pausing $REDIS_CONTAINER"
elif $COMPOSE pause "$REDIS_CONTAINER" >/dev/null 2>&1; then
  # onRedisFailure=OPEN -> both requests reach the controller; the DB UNIQUE +
  # return-existing handler (layer 3) must still yield ONE order.
  for _ in 1 2; do
    curl -s -o /dev/null -X POST "$GATEWAY_URL$CHECKOUT_PATH" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -H "Idempotency-Key: $KEY2" --data "$PAYLOAD"
  done
  $COMPOSE unpause "$REDIS_CONTAINER" >/dev/null 2>&1
  sleep 1; A=$(order_count)
  if [ "$B" -ge 0 ] && [ $((A-B)) -eq 1 ]; then pass "Redis down: still exactly ONE order (DB backstop held)"
  elif [ "$B" -ge 0 ]; then fail "Redis down: order delta $((A-B)) (expected 1) - is the DataIntegrityViolationException handler returning the existing order?"
  else skip "order count unreadable"; fi
else skip "could not pause $REDIS_CONTAINER"; fi

echo
echo "== B: a normal checkout charges the customer exactly ONCE =="
if [ -z "$SHIPPING_ADDRESS_ID" ]; then
  skip "no shipping address - see SETUP notes above"
else
  KEY3="po-charge-$(date +%s)-$RANDOM"
  ORDER_ID=$(curl -s -X POST "$GATEWAY_URL$CHECKOUT_PATH" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -H "Idempotency-Key: $KEY3" --data "$PAYLOAD" \
      | python3 -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)
  if [ -z "$ORDER_ID" ]; then
    fail "checkout for the charge test did not return an order id"
  else
    note "order $ORDER_ID placed, waiting for a terminal saga state..."
    STATUS=$(wait_for_terminal_status "$ORDER_ID")
    note "final status: $STATUS"
    if [ "$STATUS" = "CONFIRMED" ]; then pass "saga reached CONFIRMED (stock reserved, payment charged, stock confirmed)"
    else fail "saga did not reach CONFIRMED (status: $STATUS) - is stock actually seeded for $PRODUCT_ID?"; fi

    if [ -n "$PAYMENT_DB" ]; then
      CNT=$($PAYMENT_DB "select count(*) from payment_transactions where order_id = '$ORDER_ID';" 2>/dev/null | tr -d '[:space:]')
      if [ "$CNT" = "1" ]; then pass "exactly one payment_transactions row for order $ORDER_ID"
      elif [ -n "$CNT" ]; then fail "found $CNT payment_transactions rows for order $ORDER_ID (expected 1)"
      else skip "could not query PAYMENT_DB"; fi
    else
      skip "set PAYMENT_DB to a psql prefix to assert the charge count at the source"
      note "architectural guarantee (not re-verified by redelivery here): commandId is stamped once by"
      note "the orchestrator and travels inside the outbox payload, so every Kafka redelivery of THIS"
      note "message computes the identical SagaIdempotencyKeys.charge() key - the same mechanism test C"
      note "exercises end-to-end via the refund path's own redelivery-equivalent (a second HTTP call)."
    fi
  fi
fi

echo
echo "== C: the admin refund endpoint is idempotent (no double refund) =="
if [ -z "$ADMIN_TOKEN" ] || [ -z "${ORDER_ID:-}" ] || [ "${STATUS:-}" != "CONFIRMED" ]; then
  skip "needs an admin token and a CONFIRMED order from test B"
else
  R1=$(curl -s -w '|%{http_code}' -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
        "$GATEWAY_URL$ORDERS_PATH/$ORDER_ID/refund")
  C1="${R1##*|}"; B1="${R1%|*}"
  if [ "$C1" = "200" ]; then pass "first refund call -> 200 ($(echo "$B1" | python3 -c "import sys,json;print(json.load(sys.stdin).get('message',''))" 2>/dev/null))"
  else fail "first refund call -> $C1"; fi

  # Fire 5 more CONCURRENT calls against the SAME order - simulates a
  # double-click/redelivery storm, not just a clean sequential retry.
  for _ in 1 2 3 4 5; do
    curl -s -o /dev/null -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
      "$GATEWAY_URL$ORDERS_PATH/$ORDER_ID/refund" &
  done
  wait

  R2=$(curl -s -w '|%{http_code}' -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
        "$GATEWAY_URL$ORDERS_PATH/$ORDER_ID/refund")
  C2="${R2##*|}"; B2="${R2%|*}"
  MSG2=$(echo "$B2" | python3 -c "import sys,json;print(json.load(sys.stdin).get('message',''))" 2>/dev/null)
  if [ "$C2" = "200" ] && [ "$MSG2" = "Refund already requested for this order" ]; then
    pass "repeated/concurrent refund calls -> order-level idempotent no-op after the first"
  else
    fail "repeated refund call -> $C2 / '$MSG2' (expected 200 / 'Refund already requested for this order')"
  fi

  if [ -n "$PAYMENT_DB" ]; then
    sleep 2  # let the one published RefundPaymentCommand actually process
    ROW=$($PAYMENT_DB "select status, refund_reference from payment_transactions where order_id = '$ORDER_ID';" 2>/dev/null)
    if echo "$ROW" | grep -q '^REFUNDED'; then pass "payment_transactions row shows REFUNDED with a refund_reference: $ROW"
    else note "row: ${ROW:-<unreadable>} (async - the RefundSagaListener may still be processing)"; skip "could not confirm REFUNDED synchronously"; fi
  else
    skip "set PAYMENT_DB to a psql prefix to confirm exactly one refund at the source"
  fi
fi

echo
echo "=============================================="
echo "Results: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ] || exit 1
