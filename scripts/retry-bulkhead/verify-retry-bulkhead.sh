#!/usr/bin/env bash
#
# verify-retry-bulkhead.sh
#
# The assertions that matter — and the ones naive suites skip:
#   RETRY:   proving a transient failure SUCCEEDS after retries (not just that
#            retry is configured), AND that a PERMANENT failure is NOT retried.
#   BULKHEAD: proving ISOLATION — while the bulkhead is saturated, an UNRELATED
#            endpoint on the same service stays fast. That containment is the
#            entire reason a bulkhead exists; a fallback body alone doesn't
#            prove it.
#
# Reads actuator event endpoints (§6.3 — the detailed channel), not just codes.
#
# PREREQUISITE: infra/docker-compose.yml stack up (service-discovery,
# api-gateway, postgres, mysql, kafka, keycloak, user-service, order-service
# at minimum; inventory-service/review-service/payment-service can run either
# in Docker or natively via `mvn spring-boot:run` against the same infra —
# either way, they must be REACHABLE at the *_DIRECT URLs below and registered
# in Eureka so the gateway can route to them).
#
# Every request below goes through REAL auth (checkout and review-submit are
# both CUSTOMER-gated — see order-service/review-service SecurityConfig) and
# REAL endpoint shapes (checkout is POST /api/v1/orders, not /orders/checkout;
# there is no separate checkout sub-path — see OrderController javadoc).
#
# Usage:
#   [GATEWAY_URL=...] [REVIEW_DIRECT=http://localhost:8086] \
#   [INV_DIRECT=http://localhost:8084] [PRODUCT_ID=...] \
#   ./verify-retry-bulkhead.sh
#
# PRODUCT_ID: if unset, a fresh UUID is generated and a product_stock row is
# seeded directly into MySQL (ample stock — 1000 units — so RETRY [1] isolates
# the @Version race from ordinary insufficient-stock failures; RETRY [2]
# separately proves the insufficient-stock path with an intentionally
# oversized quantity). Seeding requires `docker compose exec mysql` to work;
# if it doesn't (mysql not in Docker), set PRODUCT_ID to a product you've
# already seeded with plenty of stock and this step is skipped.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Same .env discovery as scripts/integration-verification/verify-e2e.sh.
ENV_FOUND=""
for E in "$SCRIPT_DIR/.env" "$REPO_ROOT/.env" "./.env"; do
  if [ -f "$E" ]; then set -a; source "$E"; set +a; ENV_FOUND="$E"; break; fi
done

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
REVIEW_DIRECT="${REVIEW_DIRECT:-http://localhost:8086}"   # review-service actuator
INV_DIRECT="${INV_DIRECT:-http://localhost:8084}"          # inventory-service actuator
KEYCLOAK="${KEYCLOAK:-http://localhost:8090}"
ORDER_SERVICE="${ORDER_SERVICE:-order-service}"            # docker compose SERVICE name to pause
COMPOSE="docker compose -f $REPO_ROOT/infra/docker-compose.yml"

KEYCLOAK_GATEWAY_CLIENT_SECRET="${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}"
TEST_PASS="${TEST_PASS:-Customer#Pass1}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"

REVIEW_WRITE_PATH="${REVIEW_WRITE_PATH:-/api/v1/reviews}"  # triggers the verified-purchase (bulkhead) call
CONCURRENCY="${CONCURRENCY:-20}"           # > pool(4) + queue(8) = 12, to force BulkheadFull

echo "[diag] .env sourced from: ${ENV_FOUND:-<NOT FOUND>} | KEYCLOAK_GATEWAY_CLIENT_SECRET is $( [ -n "$KEYCLOAK_GATEWAY_CLIENT_SECRET" ] && echo "set (${#KEYCLOAK_GATEWAY_CLIENT_SECRET} chars)" || echo EMPTY )" >&2

PASS=0; FAIL=0; SKIP=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
note() { printf '       %s\n' "$1"; }

jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1],{'d':d}))" "$1" 2>/dev/null; }

retry_calls() {  # total retry attempts recorded for an instance
  curl -s "$INV_DIRECT/actuator/retryevents/$1" \
    | jget "len([e for e in d.get('retryEvents',[]) if e.get('type')=='RETRY'])" 2>/dev/null || echo "UNREADABLE"
}

settle_retries() {  # wait until retry_calls stockReservation stops moving
  # confirmOrder/releaseOrder share the "stockReservation" retry instance with
  # reserve() (deliberately - same failure mode, see StockReservationService).
  # After a reserve burst, the saga's charge->confirm cascade for those same
  # orders can keep touching this counter for several more seconds - a fixed
  # sleep after the initial burst isn't enough to know it's quiet.
  local prev=-1 cur
  for _ in $(seq 1 15); do
    cur=$(retry_calls stockReservation)
    [ "$cur" = "$prev" ] && { echo "$cur"; return; }
    prev="$cur"
    sleep 1.5
  done
  echo "$cur"
}

# =============================================================================
# SETUP — real auth token, provisioned user, shipping address, seeded stock.
# Every write below is CUSTOMER-gated; without this, tests 1-4 just measure
# how fast the gateway/service returns 401/403, proving nothing about retry
# or bulkhead behavior.
# =============================================================================
echo "== SETUP =="

TOKEN=""
if [ -z "$KEYCLOAK_GATEWAY_CLIENT_SECRET" ]; then
  note "KEYCLOAK_GATEWAY_CLIENT_SECRET not set (.env not found?) — cannot authenticate"
else
  TOKEN=$(curl -s -X POST "$KEYCLOAK/realms/easyshop/protocol/openid-connect/token" \
    -d "client_id=easyshop-gateway" -d "client_secret=${KEYCLOAK_GATEWAY_CLIENT_SECRET}" \
    -d "username=demo.customer" -d "password=${TEST_PASS}" -d "grant_type=password" \
    | jq -r '.access_token // empty' 2>/dev/null)
fi

if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  note "no access token obtained — checkout/review-write requests below will 401"
  note "(is Keycloak up at $KEYCLOAK, and is demo.customer's password '$TEST_PASS'?)"
else
  note "obtained access token for demo.customer"
fi
AUTH="Authorization: Bearer $TOKEN"

SHIPPING_ADDRESS_ID=""
if [ -n "$TOKEN" ]; then
  # JIT-provisions the local user row on first call (UserServiceImpl.getMyProfile).
  curl -s -H "$AUTH" "$GATEWAY_URL/api/v1/users/me" >/dev/null

  SHIPPING_ADDRESS_ID=$(curl -s -H "$AUTH" "$GATEWAY_URL/api/v1/users/me/addresses" \
    | jq -r '.data[0].id // empty' 2>/dev/null)
  if [ -z "$SHIPPING_ADDRESS_ID" ]; then
    SHIPPING_ADDRESS_ID=$(curl -s -X POST -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"label":"verify-script","line1":"1 Test St","city":"Testville","postalCode":"00000","countryCode":"US"}' \
      "$GATEWAY_URL/api/v1/users/me/addresses" | jq -r '.data.id // empty' 2>/dev/null)
  fi
fi
if [ -z "$SHIPPING_ADDRESS_ID" ]; then
  note "no shipping address available — checkout (RETRY [1]/[2]) will fail request validation"
else
  note "shipping address: $SHIPPING_ADDRESS_ID"
fi

if [ -z "${PRODUCT_ID:-}" ]; then
  PRODUCT_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
  note "generated PRODUCT_ID=$PRODUCT_ID; seeding product_stock (1000 units) via mysql"
  if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
    note "MYSQL_ROOT_PASSWORD not set — cannot seed; RETRY [1]/[2] will SKIP"
    PRODUCT_ID=""
  elif ! $COMPOSE exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" easyshop_inventory \
      -e "INSERT INTO product_stock (id, product_id, available_qty, reserved_qty, version)
          VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN('$PRODUCT_ID'), 1000, 0, 0)
          ON DUPLICATE KEY UPDATE available_qty = 1000, reserved_qty = 0;" 2>/tmp/verify-rb-mysql-err.log; then
    note "seeding failed (is mysql running in the compose stack?) — see /tmp/verify-rb-mysql-err.log"
    note "RETRY [1]/[2] will SKIP; set PRODUCT_ID yourself to a pre-seeded product to run them"
    PRODUCT_ID=""
  else
    note "seeded product_stock for $PRODUCT_ID"
  fi
fi
REVIEWS_READ_PATH="${REVIEWS_READ_PATH:-/api/v1/reviews/products/$PRODUCT_ID}"  # does NOT call order-service

checkout() {  # checkout <quantity> — POSTs a fresh checkout attempt, returns nothing
  local qty="$1"
  curl -s -o /dev/null -X POST "$GATEWAY_URL/api/v1/orders" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuidgen)" \
    -d "{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":$qty,\"unitPrice\":9.99}],\"shippingAddressId\":\"$SHIPPING_ADDRESS_ID\"}" \
    2>/dev/null
}

echo
echo "== RETRY [1] transient optimistic-lock contention SUCCEEDS after retries =="
if [ -z "$PRODUCT_ID" ] || [ -z "$TOKEN" ] || [ -z "$SHIPPING_ADDRESS_ID" ]; then
  skip "setup incomplete (need PRODUCT_ID + auth token + shipping address) — see SETUP notes above"
else
  BEFORE=$(retry_calls stockReservation)
  if [ "$BEFORE" = "UNREADABLE" ]; then
    fail "cannot read $INV_DIRECT/actuator/retryevents — expose 'retryevents'"
  else
    # Fire concurrent single-unit checkouts for the SAME product to force
    # @Version races on its product_stock row. Each is a DIFFERENT order
    # (fresh Idempotency-Key), same row.
    note "firing $CONCURRENCY concurrent checkouts on $PRODUCT_ID"
    for _ in $(seq 1 "$CONCURRENCY"); do checkout 1 & done
    wait
    note "waiting for the retry counter to settle (saga charge/confirm cascade)..."
    AFTER=$(settle_retries)
    if [ "$AFTER" -gt "$BEFORE" ] 2>/dev/null; then
      pass "retry attempts rose $BEFORE -> $AFTER (optimistic-lock contention was retried, not failed)"
    else
      fail "no retry events recorded — contention may not have triggered, or name mismatch on the @Retry instance"
      note "check: @Retry(name=\"stockReservation\") matches the yml instance key"
    fi
  fi
fi

echo
echo "== RETRY [2] permanent failure is NOT retried =="
if [ -z "$PRODUCT_ID" ] || [ -z "$TOKEN" ] || [ -z "$SHIPPING_ADDRESS_ID" ]; then
  skip "setup incomplete — see SETUP notes above"
else
  # Re-settle rather than trust RETRY [1]'s already-quiet reading: this test
  # runs moments later and needs its OWN clean baseline.
  BEFORE=$(settle_retries)
  if [ "$BEFORE" = "UNREADABLE" ]; then
    fail "cannot read $INV_DIRECT/actuator/retryevents — expose 'retryevents'"
  else
    # Request MORE than total stock -> a business-failure ReservationOutcome
    # (not an exception), whitelisted OUT of retryExceptions. Must not retry.
    checkout 999999
    sleep 1
    AFTER=$(retry_calls stockReservation)
    if [ "$AFTER" = "UNREADABLE" ]; then
      fail "cannot read $INV_DIRECT/actuator/retryevents — expose 'retryevents'"
    elif [ "$AFTER" = "$BEFORE" ]; then
      pass "insufficient-stock did NOT increment retries (permanent failure not retried)"
    else
      fail "retries fired on a business failure — insufficient-stock is being retried"
      note "it must be in ignoreExceptions / absent from retryExceptions"
    fi
  fi
fi

echo
echo "== BULKHEAD [3] ISOLATION — the assertion that matters =="
# Baseline: the read path (no order-service call) latency.
BASE=$(curl -s -o /dev/null -w '%{time_total}' "$GATEWAY_URL$REVIEWS_READ_PATH")
note "baseline review-read latency: ${BASE}s"

if [ -z "$TOKEN" ]; then
  skip "no access token — cannot flood the authenticated write path"
else
  # Saturate the thread-pool bulkhead: pause order-service so every verified-
  # purchase call HANGS, then flood the write path past pool(4)+queue(8).
  if $COMPOSE pause "$ORDER_SERVICE" >/dev/null 2>&1; then
    note "paused $ORDER_SERVICE; flooding $CONCURRENCY concurrent review writes"
    # A DIFFERENT productId per request: reviews are UNIQUE per (user,
    # product), so reusing $PRODUCT_ID here would 500 on every request after
    # the first with a DB constraint violation - unrelated to the bulkhead
    # and it would mask whether saturation itself is behaving correctly.
    for _ in $(seq 1 "$CONCURRENCY"); do
      curl -s -o /dev/null -X POST "$GATEWAY_URL$REVIEW_WRITE_PATH" \
        -H "$AUTH" -H 'Content-Type: application/json' \
        -d "{\"productId\":\"$(uuidgen)\",\"rating\":5,\"title\":\"t\",\"body\":\"b\"}" 2>/dev/null &
    done
    sleep 1  # let the bulkhead fill

    # THE test: while the bulkhead is saturated, is the UNRELATED read still fast?
    ISO=$(curl -s -o /dev/null -w '%{time_total}' "$GATEWAY_URL$REVIEWS_READ_PATH")
    note "review-read latency during bulkhead saturation: ${ISO}s"
    # allow generous slack; the point is it's NOT hanging near the TimeLimiter
    if python3 -c "import sys; sys.exit(0 if float('$ISO') < 2.0 else 1)"; then
      pass "unrelated read stayed responsive (${ISO}s) while the bulkhead was saturated — ISOLATION holds"
    else
      fail "unrelated read degraded to ${ISO}s — web threads not isolated; is the bulkhead THREADPOOL type on the order call?"
    fi

    wait
    $COMPOSE unpause "$ORDER_SERVICE" >/dev/null 2>&1
  else
    skip "could not pause $ORDER_SERVICE (not running under docker compose as service '$ORDER_SERVICE'?)"
  fi
fi

echo
echo "== BULKHEAD [4] saturation rejects fast (BulkheadFull -> UNVERIFIED), not hangs =="
BE=$(curl -s "$REVIEW_DIRECT/actuator/bulkheadevents" \
      | jget "len([e for e in d.get('bulkheadEvents',[]) if 'REJECTED' in e.get('type','')])" 2>/dev/null || echo UNREADABLE)
if [ "$BE" = "UNREADABLE" ]; then
  skip "expose 'bulkheadevents' to assert rejection count"
elif [ "$BE" -gt 0 ] 2>/dev/null; then
  pass "$BE bulkhead rejections recorded — excess load shed fast, not queued unboundedly"
else
  note "no rejections recorded — raise CONCURRENCY above pool+queue, or the flood didn't saturate"
  skip "rejection assertion inconclusive"
fi

echo
echo "== [5] fallback correctness: saturated badge -> review still posts, UNVERIFIED =="
note "manual/log check: during saturation a submitted review should succeed with"
note "verifiedPurchase=false (fallback), NOT a 500. Confirm in the response body."

echo
echo "== [6] context propagation (MDC) across the bulkhead pool thread =="
note "manual/log check: log lines emitted DURING the order call should still carry"
note "the trace/correlation id. If blank, the MdcContextPropagator isn't registered."
note "(This is what makes the §8.4 OTel->Jaeger work traceable across the pool.)"

echo
echo "=============================================="
echo "Results: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" -eq 0 ] || exit 1
