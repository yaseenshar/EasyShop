#!/usr/bin/env bash
# =============================================================================
# EasyShop end-to-end verification
# Run AFTER: docker compose up + all 8 services started.
# Requires: curl, jq  (sudo apt install jq / brew install jq)
# chmod +x verify-e2e.sh
# Usage: REDIS_PASSWORD=change-me-in-production KEYCLOAK_GATEWAY_CLIENT_SECRET=change-me-in-production ./verify-e2e.sh
# =============================================================================
set -uo pipefail

GATEWAY="http://localhost:8080"
EUREKA="http://localhost:8761"
KEYCLOAK="http://localhost:8090"
PASS=0; FAIL=0

check() { # check <description> <command...>
  local desc="$1"; shift
  if "$@" > /dev/null 2>&1; then
    echo "  PASS  $desc"; PASS=$((PASS+1))
  else
    echo "  FAIL  $desc"; FAIL=$((FAIL+1))
  fi
}

echo "=== 1. Eureka: every service registered ==============================="
# Eureka's /eureka/apps returns XML by default; Accept: application/json flips it.
REGISTRY=$(curl -s -H "Accept: application/json" "$EUREKA/eureka/apps")

for SVC in API-GATEWAY USER-SERVICE ORDER-SERVICE PAYMENT-SERVICE \
           INVENTORY-SERVICE CATALOG-SERVICE NOTIFICATION-SERVICE \
           REVIEW-SERVICE CART-SERVICE; do
  check "registered: $SVC" \
    bash -c "echo '$REGISTRY' | jq -e '.applications.application[] | select(.name == \"$SVC\")'"
done

echo ""
echo "=== 2. Gateway is up and sees its routes =============================="
check "gateway health" curl -sf "$GATEWAY/actuator/health"
# /actuator/gateway/routes lists the live routing table - confirms the
# corrected config (5 routes; payment/inventory/notification ABSENT).
ROUTES=$(curl -s "$GATEWAY/actuator/gateway/routes")
for R in user-service catalog-service order-service cart-service review-service; do
  check "route present: $R" bash -c "echo '$ROUTES' | jq -e '.[] | select(.route_id == \"$R\")'"
done
check "payment-service route ABSENT (deliberate)" \
  bash -c "! echo '$ROUTES' | jq -e '.[] | select(.route_id == \"payment-service\")' > /dev/null"

echo ""
echo "=== 3. Auth: token from Keycloak ======================================"
TOKEN=$(curl -s -X POST \
  "$KEYCLOAK/realms/easyshop/protocol/openid-connect/token" \
  -d "client_id=easyshop-gateway" \
  -d "client_secret=${KEYCLOAK_GATEWAY_CLIENT_SECRET}" \
  -d "username=demo.customer" -d "password=Passw0rd!" \
  -d "grant_type=password" | jq -r '.access_token')

[ "$TOKEN" != "null" ] && [ -n "$TOKEN" ] \
  && { echo "  PASS  obtained access token"; PASS=$((PASS+1)); } \
  || { echo "  FAIL  no token - check Keycloak realm + client secret"; FAIL=$((FAIL+1)); }
AUTH="Authorization: Bearer $TOKEN"

echo ""
echo "=== 4. Routing through the gateway, service by service ================"
# Unauthenticated request to a protected route must be REJECTED at the gateway
check "gateway rejects unauthenticated /cart (401)" \
  bash -c "[ \$(curl -s -o /dev/null -w '%{http_code}' $GATEWAY/api/v1/cart) = 401 ]"

# Public catalog read needs no token
check "catalog public read via gateway" \
  bash -c "curl -sf '$GATEWAY/api/v1/products?categoryId=00000000-0000-0000-0000-000000000000&page=0&size=1'"

# Public review summary needs no token (any product id - empty summary is a 200)
check "review public summary via gateway" \
  curl -sf "$GATEWAY/api/v1/reviews/products/00000000-0000-0000-0000-000000000000/summary"

# Authenticated user profile (register demo user first if fresh DB - see note below)
check "user /me via gateway (authed)" \
  curl -sf -H "$AUTH" "$GATEWAY/api/v1/users/me"

echo ""
echo "=== 5. Cart round-trip via gateway ===================================="
PRODUCT_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
check "add item to cart" \
  curl -sf -X POST -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"productId\":\"$PRODUCT_ID\",\"name\":\"Test Widget\",\"price\":19.99,\"quantity\":2}" \
    "$GATEWAY/api/v1/cart/items"
check "cart contains the item" \
  bash -c "curl -s -H '$AUTH' $GATEWAY/api/v1/cart | jq -e '.data.totalItems >= 2'"
check "clear cart" \
  curl -sf -X DELETE -H "$AUTH" "$GATEWAY/api/v1/cart"

echo ""
echo "=== 6. Saga smoke test: order through the full pipeline ==============="
# Requires seeded product stock in inventory (see note below). The order is
# ACCEPTED (202) immediately; the saga then runs async through Kafka:
# reserve -> charge -> confirm. We poll for a terminal state.
IDEMPOTENCY_KEY=$(uuidgen)
ORDER_RESPONSE=$(curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1,\"unitPrice\":19.99}],
       \"shippingAddressId\":\"$(uuidgen)\"}" \
  "$GATEWAY/api/v1/orders")
ORDER_ID=$(echo "$ORDER_RESPONSE" | jq -r '.data.id')

if [ "$ORDER_ID" != "null" ] && [ -n "$ORDER_ID" ]; then
  echo "  PASS  order accepted: $ORDER_ID"; PASS=$((PASS+1))
  echo "  ....  polling for terminal saga state (max 30s)"
  for i in $(seq 1 15); do
    STATUS=$(curl -s -H "$AUTH" "$GATEWAY/api/v1/orders/$ORDER_ID" | jq -r '.data.status')
    if [ "$STATUS" = "CONFIRMED" ] || [ "$STATUS" = "CANCELLED" ]; then break; fi
    sleep 2
  done
  echo "  INFO  final order status: $STATUS"
  # CANCELLED is a VALID terminal outcome here if the product has no stock
  # row (reservation fails -> saga compensates -> cancelled + notification).
  # CONFIRMED requires seeded stock. Either proves the pipeline end to end;
  # an order stuck in RESERVING_STOCK/CHARGING_PAYMENT means a consumer or
  # the outbox publisher isn't running - check Kafka UI consumer groups.
  [ "$STATUS" = "CONFIRMED" ] || [ "$STATUS" = "CANCELLED" ] \
    && { echo "  PASS  saga reached terminal state"; PASS=$((PASS+1)); } \
    || { echo "  FAIL  saga stuck in: $STATUS"; FAIL=$((FAIL+1)); }
  # Idempotency replay: same key must return the SAME order, not a new one
  REPLAY_ID=$(curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
    -d "{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1,\"unitPrice\":19.99}],
         \"shippingAddressId\":\"$(uuidgen)\"}" \
    "$GATEWAY/api/v1/orders" | jq -r '.data.id')
  [ "$REPLAY_ID" = "$ORDER_ID" ] \
    && { echo "  PASS  idempotency: replay returned same order"; PASS=$((PASS+1)); } \
    || { echo "  FAIL  idempotency: replay created NEW order $REPLAY_ID"; FAIL=$((FAIL+1)); }
else
  echo "  FAIL  order not accepted: $ORDER_RESPONSE"; FAIL=$((FAIL+1))
fi

echo ""
echo "======================================================================="
echo "  RESULT: $PASS passed, $FAIL failed"
echo "======================================================================="
# Notes for first run on a fresh environment:
#  - user /me requires the demo user registered in user-service
#    (POST /api/v1/users/register with the Keycloak sub - Phase 2 step 7).
#  - CONFIRMED saga outcome requires a product_stock row in
#    easyshop_inventory for the product; without it expect CANCELLED,
#    which still proves reserve->fail->compensate->notify end to end.
#  - Check notification-service logs for the [EMAIL]/[SMS] lines after
#    the saga terminates - that closes the loop on the choreography leg.
exit $FAIL
