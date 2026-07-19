#!/usr/bin/env bash
#
# verify-rbac.sh — adversarial verification of the CUSTOMER / VENDOR / ADMIN /
# SERVICE authorization matrix.
#
# House rules encoded here (§6.3):
#   - 401, 403, 404, 500 are FOUR different diagnoses; we assert exact codes.
#     401 = authentication failed. 403 = authenticated, not authorized.
#     500 on a denial = GlobalExceptionHandler swallowed AccessDeniedException.
#   - On any failure the response body is printed — read the detailed channel.
#   - curl code 000 = no connection was established, not a rejection.
#
# Technique for positive tests on WRITE endpoints: send an empty JSON body and
# accept any non-{401,403,500} answer. A 400 validation error PROVES the request
# got past security into the application — which is all an authorization test
# should claim. No fixture payloads needed.
#
# Requirements: curl, python3, running stack, dev ROPC client (§7 dev-only).
# ALIGN THE *_PATH VARIABLES with your actual controllers before trusting results.
#
# Usage:
#   CUSTOMER_PASSWORD=... VENDOR_PASSWORD=... ADMIN_PASSWORD=... \
#   ROPC_CLIENT_ID=... [ROPC_CLIENT_SECRET=...] \
#   [M2M_CLIENT_ID=... M2M_CLIENT_SECRET=...] \
#   [INTERNAL_PATH=/internal/... OTHER_CUSTOMERS_ORDER_ID=<uuid>] \
#   ./verify-rbac.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FOUND=""
for E in "$SCRIPT_DIR/.env" "$SCRIPT_DIR/../.env" "$SCRIPT_DIR/../../.env" "./.env"; do
  if [ -f "$E" ]; then set -a; source "$E"; set +a; ENV_FOUND="$E"; break; fi
done

# Guard against copy-pasting this file's own README usage example verbatim
# (angle brackets and all) into a shell that then stays open - see the
# identical guard in scripts/resource-server-hardening/verify-token-relay.sh and
# verify-e2e.sh for the multi-turn debugging story that justified it.
for _V in ROPC_CLIENT_ID ROPC_CLIENT_SECRET M2M_CLIENT_ID M2M_CLIENT_SECRET \
          CUSTOMER_PASSWORD VENDOR_PASSWORD ADMIN_PASSWORD \
          INTERNAL_PATH OTHER_CUSTOMERS_ORDER_ID; do
  case "${!_V:-}" in
    \<*\>)
      echo "[diag] WARNING: \$$_V is set to a literal placeholder ('${!_V}') - unsetting it so the real default applies." >&2
      unset "$_V"
      ;;
  esac
done

# ---------------------------------------------------------------- configuration
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
REALM="${REALM:-easyshop}"

# Defaults below match this repo's local-dev realm seed (docker/keycloak/realms/
# easyshop-realm.json ships all three personas' passwords in plaintext - not
# real secrets) and the existing Keycloak clients: easyshop-gateway already has
# directAccessGrantsEnabled for ROPC, and easyshop-review-service's service
# account already carries the SERVICE role needed for the M2M persona.
ROPC_CLIENT_ID="${ROPC_CLIENT_ID:-easyshop-gateway}"
ROPC_CLIENT_SECRET="${ROPC_CLIENT_SECRET:-${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}}"
M2M_CLIENT_ID="${M2M_CLIENT_ID:-easyshop-review-service}"
M2M_CLIENT_SECRET="${M2M_CLIENT_SECRET:-${KEYCLOAK_REVIEW_SERVICE_CLIENT_SECRET:-}}"

CUSTOMER_USER="${CUSTOMER_USER:-demo.customer}"
VENDOR_USER="${VENDOR_USER:-demo.vendor}"
ADMIN_USER="${ADMIN_USER:-demo.admin}"
CUSTOMER_PASSWORD="${CUSTOMER_PASSWORD:-Customer#Pass1}"
VENDOR_PASSWORD="${VENDOR_PASSWORD:-Vendor#Pass12}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin#Pass123}"

echo "[diag] script: ${BASH_SOURCE[0]:-<none, not running under bash>} | .env sourced from: ${ENV_FOUND:-<NOT FOUND>} | ROPC_CLIENT_ID=$ROPC_CLIENT_ID | ROPC_CLIENT_SECRET is $( [ -n "$ROPC_CLIENT_SECRET" ] && echo 'set' || echo 'EMPTY' ) | M2M_CLIENT_ID=$M2M_CLIENT_ID | M2M_CLIENT_SECRET is $( [ -n "$M2M_CLIENT_SECRET" ] && echo 'set' || echo 'EMPTY' )" >&2

# Paths — align with your controllers (defaults are best guesses, not gospel)
ZERO_ID="00000000-0000-0000-0000-000000000000"
ME_PATH="${ME_PATH:-/api/v1/users/me}"
USERS_BY_ID_PATH="${USERS_BY_ID_PATH:-/api/v1/users/$ZERO_ID}"
PRODUCTS_PATH="${PRODUCTS_PATH:-/api/v1/products}"
PRODUCT_BY_ID_PATH="${PRODUCT_BY_ID_PATH:-$PRODUCTS_PATH/$ZERO_ID}"
CATALOG_PUBLIC="${CATALOG_PUBLIC:-true}"      # mirror your Option A/B decision
CART_PATH="${CART_PATH:-/api/v1/cart}"
CHECKOUT_PATH="${CHECKOUT_PATH:-/api/v1/orders}"  # POST here IS checkout - see OrderController javadoc
ORDERS_PATH="${ORDERS_PATH:-/api/v1/orders}"
REVIEWS_PATH="${REVIEWS_PATH:-/api/v1/reviews}"
MODERATION_PATH="${MODERATION_PATH:-}"        # e.g. $REVIEWS_PATH/<id>/moderation — empty = skip
ORDER_SERVICE_DIRECT="${ORDER_SERVICE_DIRECT:-http://localhost:8082}"
INTERNAL_PATH="${INTERNAL_PATH:-}"            # e.g. /internal/orders/<id>/purchase-check — empty = skip
OTHER_CUSTOMERS_ORDER_ID="${OTHER_CUSTOMERS_ORDER_ID:-}"  # order NOT owned by CUSTOMER_USER — empty = skip

# ------------------------------------------------------------------- reporting
PASS=0; FAIL=0; SKIPPED=0
pass()  { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
note()  { printf '       %s\n' "$1"; }
skipt() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIPPED=$((SKIPPED+1)); }

# -------------------------------------------------------------------- plumbing
json_field() {
  python3 -c 'import sys, json
try:
    print(json.load(sys.stdin).get(sys.argv[1], ""))
except Exception:
    print("")' "$1"
}

mint_user() { # $1 username, $2 password -> access_token on stdout ('' on failure)
  curl -s \
    -d grant_type=password \
    -d client_id="$ROPC_CLIENT_ID" \
    ${ROPC_CLIENT_SECRET:+-d client_secret="$ROPC_CLIENT_SECRET"} \
    --data-urlencode "username=$1" \
    --data-urlencode "password=$2" \
    "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" | json_field access_token
}

mint_service() {
  curl -s \
    -d grant_type=client_credentials \
    -d client_id="$M2M_CLIENT_ID" \
    -d client_secret="$M2M_CLIENT_SECRET" \
    "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" | json_field access_token
}

LAST_CODE=""; LAST_BODY=""
request() { # $1 token ('' = none), $2 method, $3 url, $4 optional JSON body
  local out
  out=$(curl -s -w '\n%{http_code}' -X "$2" \
        ${1:+-H "Authorization: Bearer $1"} \
        -H 'Accept: application/json' \
        -H 'Content-Type: application/json' \
        ${4:+--data "$4"} \
        "$3")
  LAST_CODE=$(printf '%s' "$out" | tail -n1)
  LAST_BODY=$(printf '%s' "$out" | sed '$d')
}

diagnose() { # context-aware hints — the diagnostic signatures of this ticket
  case "$LAST_CODE" in
    500) note "hint: 500 where a 403 belongs = GlobalExceptionHandler swallowed AccessDeniedException — install the explicit handler (common-lib additions)";;
    401) note "hint: 401 WITH a token = authentication (iss/aud/exp), not authorization — run oidc-container-fix/diagnose-401.sh";;
    403) note "hint: unexpected 403 = converter prefix / claim location / role case mismatch — run decode-token.sh and compare roles vs rules";;
    000) note "hint: 000 = no connection established (§6.3) — is the target reachable from the host?";;
  esac
  [ -n "$LAST_BODY" ] && note "body: $(printf '%.300s' "$LAST_BODY")"
}

expect() { # $1 desc, $2 token, $3 method, $4 url, $5 exact expected code, $6 optional body
  request "$2" "$3" "$4" "${6:-}"
  if [ "$LAST_CODE" = "$5" ]; then
    pass "$1 -> $LAST_CODE"
  else
    fail "$1 -> got $LAST_CODE, wanted $5"
    diagnose
  fi
}

expect_authorized() { # $1 desc, $2 token, $3 method, $4 url, $5 optional body
  # Asserts the request got PAST security: anything except 401/403/500/000.
  request "$2" "$3" "$4" "${5:-}"
  case "$LAST_CODE" in
    401|403|500|000)
      fail "$1 -> $LAST_CODE (blocked or broken)"
      diagnose
      ;;
    *)
      pass "$1 -> $LAST_CODE (past security; 400/404 acceptable here)"
      ;;
  esac
}

expect_denied_or_hidden() { # $1 desc, $2 token, $3 method, $4 url
  # 403 or 404 both acceptable — the existence-leak stance is yours (OrderAccess
  # javadoc); 200 is a breach, 500 is the handler trap, 401 is misconfiguration.
  request "$2" "$3" "$4"
  case "$LAST_CODE" in
    403|404) pass "$1 -> $LAST_CODE" ;;
    *)       fail "$1 -> got $LAST_CODE, wanted 403 or 404"; diagnose ;;
  esac
}

# ------------------------------------------------------------------ mint phase
echo "== Minting persona tokens =="
T_CUSTOMER=$(mint_user "$CUSTOMER_USER" "$CUSTOMER_PASSWORD")
T_VENDOR=$(mint_user "$VENDOR_USER" "$VENDOR_PASSWORD")
T_ADMIN=$(mint_user "$ADMIN_USER" "$ADMIN_PASSWORD")
for pair in "CUSTOMER:$T_CUSTOMER" "VENDOR:$T_VENDOR" "ADMIN:$T_ADMIN"; do
  [ -z "${pair#*:}" ] && { echo "ABORT: could not mint ${pair%%:*} token — check ROPC client + credentials (decode-token.sh prints error_description)"; exit 2; }
done
echo "  three persona tokens minted"

T_SERVICE=""
if [ -n "$M2M_CLIENT_ID" ]; then
  T_SERVICE=$(mint_service)
  [ -z "$T_SERVICE" ] && echo "  WARN: SERVICE token mint failed — SERVICE assertions will be skipped"
else
  echo "  NOTE: M2M_CLIENT_ID unset — SERVICE assertions will be skipped"
fi

# ------------------------------------------------------------------ the matrix
echo
echo "== [1] user-service: /users/me — named audience, not authenticated() =="
expect "no token   GET $ME_PATH" ""            GET "$GATEWAY_URL$ME_PATH" 401
expect "CUSTOMER   GET $ME_PATH" "$T_CUSTOMER" GET "$GATEWAY_URL$ME_PATH" 200
expect "VENDOR     GET $ME_PATH" "$T_VENDOR"   GET "$GATEWAY_URL$ME_PATH" 200
expect "ADMIN      GET $ME_PATH" "$T_ADMIN"    GET "$GATEWAY_URL$ME_PATH" 200
if [ -n "$T_SERVICE" ]; then
  expect "SERVICE    GET $ME_PATH (machine tokens are not people)" "$T_SERVICE" GET "$GATEWAY_URL$ME_PATH" 403
else
  skipt "SERVICE persona on $ME_PATH"
fi

echo
echo "== [2] user-service: by-id is an admin surface =="
expect            "CUSTOMER   GET users/{id}" "$T_CUSTOMER" GET "$GATEWAY_URL$USERS_BY_ID_PATH" 403
expect            "VENDOR     GET users/{id}" "$T_VENDOR"   GET "$GATEWAY_URL$USERS_BY_ID_PATH" 403
expect_authorized "ADMIN      GET users/{id}" "$T_ADMIN"    GET "$GATEWAY_URL$USERS_BY_ID_PATH"

echo
echo "== [3] catalog reads — mirrors the Option A/B product decision (CATALOG_PUBLIC=$CATALOG_PUBLIC) =="
if [ "$CATALOG_PUBLIC" = "true" ]; then
  expect "anonymous  GET $PRODUCTS_PATH (needs gateway permitAll too)" "" GET "$GATEWAY_URL$PRODUCTS_PATH" 200
else
  expect "anonymous  GET $PRODUCTS_PATH" "" GET "$GATEWAY_URL$PRODUCTS_PATH" 401
  expect "CUSTOMER   GET $PRODUCTS_PATH" "$T_CUSTOMER" GET "$GATEWAY_URL$PRODUCTS_PATH" 200
fi

echo
echo "== [4] catalog writes: VENDOR/ADMIN create+update, ADMIN-only delete =="
expect            "no token   POST products"  ""            POST "$GATEWAY_URL$PRODUCTS_PATH" 401 '{}'
expect            "CUSTOMER   POST products"  "$T_CUSTOMER" POST "$GATEWAY_URL$PRODUCTS_PATH" 403 '{}'
expect_authorized "VENDOR     POST products (empty body; 400 = past security)" "$T_VENDOR" POST "$GATEWAY_URL$PRODUCTS_PATH" '{}'
expect_authorized "ADMIN      POST products"  "$T_ADMIN"    POST "$GATEWAY_URL$PRODUCTS_PATH" '{}'
expect            "VENDOR     DELETE product (ADMIN-only until vendor_id ownership)" "$T_VENDOR" DELETE "$GATEWAY_URL$PRODUCT_BY_ID_PATH" 403
expect_authorized "ADMIN      DELETE product" "$T_ADMIN"    DELETE "$GATEWAY_URL$PRODUCT_BY_ID_PATH"
if [ -n "$T_SERVICE" ]; then
  expect "SERVICE    POST products" "$T_SERVICE" POST "$GATEWAY_URL$PRODUCTS_PATH" 403 '{}'
fi

echo
echo "== [5] cart: CUSTOMER-only, everyone else is a stranger here =="
expect_authorized "CUSTOMER   GET $CART_PATH" "$T_CUSTOMER" GET "$GATEWAY_URL$CART_PATH"
expect            "VENDOR     GET $CART_PATH" "$T_VENDOR"   GET "$GATEWAY_URL$CART_PATH" 403
expect            "ADMIN      GET $CART_PATH" "$T_ADMIN"    GET "$GATEWAY_URL$CART_PATH" 403
if [ -n "$T_SERVICE" ]; then
  expect "SERVICE    GET $CART_PATH" "$T_SERVICE" GET "$GATEWAY_URL$CART_PATH" 403
fi

echo
echo "== [6] orders: checkout is a CUSTOMER act =="
expect            "no token   POST checkout" ""            POST "$GATEWAY_URL$CHECKOUT_PATH" 401 '{}'
expect            "VENDOR     POST checkout" "$T_VENDOR"   POST "$GATEWAY_URL$CHECKOUT_PATH" 403 '{}'
expect            "ADMIN      POST checkout (admins do not buy as ADMIN)" "$T_ADMIN" POST "$GATEWAY_URL$CHECKOUT_PATH" 403 '{}'
expect_authorized "CUSTOMER   POST checkout (empty body; 400 = past security — idempotency key etc. missing)" "$T_CUSTOMER" POST "$GATEWAY_URL$CHECKOUT_PATH" '{}'
expect_authorized "CUSTOMER   GET  $ORDERS_PATH" "$T_CUSTOMER" GET "$GATEWAY_URL$ORDERS_PATH"
expect            "VENDOR     GET  $ORDERS_PATH" "$T_VENDOR"   GET "$GATEWAY_URL$ORDERS_PATH" 403

echo
echo "== [7] ownership: customer A must not read customer B's order =="
if [ -n "$OTHER_CUSTOMERS_ORDER_ID" ]; then
  expect_denied_or_hidden "CUSTOMER   GET someone else's order" "$T_CUSTOMER" GET "$GATEWAY_URL$ORDERS_PATH/$OTHER_CUSTOMERS_ORDER_ID"
  expect_authorized       "ADMIN      GET the same order (admin bypass)" "$T_ADMIN" GET "$GATEWAY_URL$ORDERS_PATH/$OTHER_CUSTOMERS_ORDER_ID"
else
  skipt "ownership negative — set OTHER_CUSTOMERS_ORDER_ID to an order not owned by $CUSTOMER_USER"
fi

echo
echo "== [8] reviews: customers write, admins moderate =="
expect_authorized "CUSTOMER   POST review (empty body; 400 = past security)" "$T_CUSTOMER" POST "$GATEWAY_URL$REVIEWS_PATH" '{}'
expect            "VENDOR     POST review (reviewing is a customer act)" "$T_VENDOR" POST "$GATEWAY_URL$REVIEWS_PATH" 403 '{}'
if [ -n "$MODERATION_PATH" ]; then
  expect            "CUSTOMER   POST moderation" "$T_CUSTOMER" POST "$GATEWAY_URL$MODERATION_PATH" 403 '{}'
  expect            "VENDOR     POST moderation" "$T_VENDOR"   POST "$GATEWAY_URL$MODERATION_PATH" 403 '{}'
  expect_authorized "ADMIN      POST moderation" "$T_ADMIN"    POST "$GATEWAY_URL$MODERATION_PATH" '{}'
else
  skipt "moderation assertions — set MODERATION_PATH to a real transition endpoint"
fi

echo
echo "== [9] /internal/** — SERVICE-only, tested DIRECT (the gateway never routes it) =="
if [ -n "$INTERNAL_PATH" ]; then
  # Reachability first — §6.3: 000 means no connection, not a rejection.
  request "" GET "$ORDER_SERVICE_DIRECT$INTERNAL_PATH"
  if [ "$LAST_CODE" = "000" ]; then
    skipt "order-service not reachable at $ORDER_SERVICE_DIRECT from the host (port not published?) — run these inside the compose network instead"
  else
    expect "no token   GET internal" ""            GET "$ORDER_SERVICE_DIRECT$INTERNAL_PATH" 401
    expect "CUSTOMER   GET internal" "$T_CUSTOMER" GET "$ORDER_SERVICE_DIRECT$INTERNAL_PATH" 403
    expect "ADMIN      GET internal (admin tokens are not skeleton keys)" "$T_ADMIN" GET "$ORDER_SERVICE_DIRECT$INTERNAL_PATH" 403
    if [ -n "$T_SERVICE" ]; then
      expect_authorized "SERVICE    GET internal" "$T_SERVICE" GET "$ORDER_SERVICE_DIRECT$INTERNAL_PATH"
    else
      skipt "SERVICE positive on internal — set M2M_CLIENT_ID/SECRET"
    fi
    note "⚠ sequencing: until review-service PROPAGATES its M2M token, its purchase-check"
    note "  calls now fail into the §4.12 fallback — new reviews land UNVERIFIED with zero"
    note "  errors anywhere. After installing, post a review from a real purchaser and"
    note "  assert it can become VERIFIED — that is the test for this silent failure."
  fi
else
  skipt "internal-endpoint assertions — set INTERNAL_PATH (e.g. /internal/orders/<id>/purchase-check)"
fi

# --------------------------------------------------------------------- summary
echo
echo "=============================================="
echo "Results: $PASS passed, $FAIL failed, $SKIPPED skipped"
if [ "$FAIL" -eq 0 ]; then
  echo "RBAC matrix holds. Re-run scripts/integration-verification/verify-e2e.sh and"
  echo "scripts/resource-server-hardening/verify-resource-server.sh for the full green baseline."
else
  echo "Failures above — each carries a diagnostic hint. Fix root causes fleet-wide, not locally."
  exit 1
fi