#!/usr/bin/env bash
# =============================================================================
# Keycloak setup verification v2
# Run from the project root: ./verify-keycloak.sh
# Secrets are auto-loaded from ./.env - no inline vars needed.
#
# v2 fixes (v1 bugs, all mine):
#   - auto-sources .env instead of expecting inline env vars
#   - missing secrets -> clear message + section skip, not bash crashes
#   - CONNECTIVITY PREFLIGHT: if Keycloak isn't reachable, say exactly that
#     and stop - instead of producing 8 misleading downstream failures
#   - negative tests now require a REAL http 400/401 from Keycloak;
#     curl code 000 (no connection) is a FAIL, never a PASS
# =============================================================================
set -o pipefail

KC="http://localhost:8090"
MGMT="http://localhost:9000"
REALM="$KC/realms/easyshop"
TOKEN_URL="$REALM/protocol/openid-connect/token"
PASS=0; FAIL=0
ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }

# ── Load .env (script dir, then cwd) ─────────────────────────────────────────
for ENVF in "$(dirname "$0")/.env" "./.env"; do
  if [ -f "$ENVF" ]; then
    set -a; # export everything sourced
    # shellcheck disable=SC1090
    source "$ENVF"
    set +a
    echo "Loaded secrets from $ENVF"
    break
  fi
done
GW_SECRET="${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}"
RS_SECRET="${KEYCLOAK_REVIEW_SERVICE_CLIENT_SECRET:-}"

decode() { echo "$1" | cut -d. -f2 | tr '_-' '/+' | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' | base64 -d 2>/dev/null; }

# ── PREFLIGHT: is Keycloak even reachable? ───────────────────────────────────
echo "=== Preflight: connectivity ==========================================="
BASE_CODE=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 3 "$KC" || true)
if [ "$BASE_CODE" = "000" ]; then
  echo "  FATAL: nothing is answering on $KC (curl code 000 = no connection)."
  echo ""
  echo "  Diagnose in this order:"
  echo "    1. docker compose ps keycloak"
  echo "         - not listed / Exited / Restarting => it never came up or is"
  echo "           crash-looping; go to step 2"
  echo "         - Up but this preflight fails => port mapping missing; check"
  echo "           the ports: block has \"8090:8080\" (and \"9000:9000\")"
  echo "    2. docker compose logs --tail=100 keycloak"
  echo "         look for, in rough likelihood order:"
  echo "         - realm import error mentioning the service-account user"
  echo "           (the flagged fragile block - remove it from the JSON and"
  echo "           assign the SERVICE role via kcadm instead)"
  echo "         - 'Unrecognized configuration' / bootstrap admin warnings"
  echo "           (KC 26 renamed KEYCLOAK_ADMIN -> KC_BOOTSTRAP_ADMIN_USERNAME)"
  echo "         - DB errors (did you DROP/CREATE the keycloak database for"
  echo "           the clean 24->26 path?)"
  echo "         - \${env.VAR} substitution failures (both client secrets must"
  echo "           be in the KEYCLOAK container's environment: block)"
  exit 1
fi
ok "Keycloak answering on $KC (http $BASE_CODE)"

MGMT_CODE=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 3 "$MGMT/health/ready" || true)
if [ "$MGMT_CODE" = "000" ]; then
  bad "management port 9000 unreachable - add \"9000:9000\" to ports: and KC_HEALTH_ENABLED=true"
fi

echo ""
echo "=== Ticket 1: deployment health ======================================="
if [ "$MGMT_CODE" != "000" ]; then
  curl -sf "$MGMT/health/ready" | jq -e '.status == "UP"' >/dev/null \
    && ok "health/ready UP on management port 9000" \
    || bad "health/ready reachable but not UP (http $MGMT_CODE)"
fi

DISCO=$(curl -sf "$REALM/.well-known/openid-configuration" || true)
if echo "$DISCO" | jq -e '.issuer' >/dev/null 2>&1; then
  ok "OIDC discovery served (issuer: $(echo "$DISCO" | jq -r .issuer))"
  curl -sf "$(echo "$DISCO" | jq -r .jwks_uri)" | jq -e '.keys | length > 0' >/dev/null \
    && ok "JWKS published" || bad "JWKS empty"
else
  bad "no OIDC discovery for realm 'easyshop' - realm not imported; check logs for 'Imported realm' vs import errors"
fi

echo ""
echo "=== Ticket 2: users, roles, clients ==================================="
if [ -z "$GW_SECRET" ]; then
  bad "KEYCLOAK_GATEWAY_CLIENT_SECRET not found in .env - skipping user-token tests"
else
  for U in "demo.customer:Customer#Pass1:CUSTOMER" \
           "demo.vendor:Vendor#Pass12:VENDOR" \
           "demo.admin:Admin#Pass123:ADMIN"; do
    IFS=: read -r USER PW ROLE <<< "$U"
    RESP=$(curl -s -X POST "$TOKEN_URL" \
        -d "client_id=easyshop-gateway" -d "client_secret=${GW_SECRET}" \
        -d "grant_type=password" -d "username=$USER" -d "password=$PW")
    T=$(echo "$RESP" | jq -r '.access_token')
    if [ "$T" = "null" ] || [ -z "$T" ]; then
      bad "token for $USER -> $(echo "$RESP" | jq -r '"\(.error // "no-response"): \(.error_description // "")"')"
      continue
    fi
    ok "token issued for $USER"
    decode "$T" | jq -e --arg r "$ROLE" '.roles | index($r)' >/dev/null \
      && ok "  'roles' claim contains $ROLE" \
      || bad "  'roles' claim missing $ROLE - check easyshop-roles client scope"
  done

  # Negative: wrong password must yield invalid_grant SPECIFICALLY.
  # A bare 401 is ambiguous: invalid_client (broken client secret) also
  # 401s, which previously let a misconfigured client masquerade as a
  # passing user-auth test. Assert the error CODE, not just the status.
  RESP=$(curl -s -X POST "$TOKEN_URL" \
    -d "client_id=easyshop-gateway" -d "client_secret=${GW_SECRET}" \
    -d "grant_type=password" -d "username=demo.customer" -d "password=wrong")
  ERR=$(echo "$RESP" | jq -r '.error // "none"')
  case "$ERR" in
    invalid_grant)  ok "wrong password rejected (invalid_grant - user auth working, client auth working)" ;;
    invalid_client|unauthorized_client)
                    bad "client auth itself is failing ($ERR) - gateway secret mismatch: check \${env.} substitution + realm re-import" ;;
    *)              bad "wrong-password test: unexpected error '$ERR'" ;;
  esac
fi

if [ -z "$RS_SECRET" ]; then
  bad "KEYCLOAK_REVIEW_SERVICE_CLIENT_SECRET not found in .env - skipping M2M test"
else
  MRESP=$(curl -s -X POST "$TOKEN_URL" \
      -d "client_id=easyshop-review-service" -d "client_secret=${RS_SECRET}" \
      -d "grant_type=client_credentials")
  MT=$(echo "$MRESP" | jq -r '.access_token')
  if [ "$MT" != "null" ] && [ -n "$MT" ]; then
    ok "client_credentials grant (M2M)"
    decode "$MT" | jq -e '.roles | index("SERVICE")' >/dev/null \
      && ok "  service-account token carries SERVICE role" \
      || bad "  SERVICE role missing - assign via kcadm (see setup notes)"
  else
    bad "client_credentials -> $(echo "$MRESP" | jq -r '"\(.error // "no-response"): \(.error_description // "")"') (client imported? secret in KC container env?)"
  fi
fi

# Negative: SPA must reject password grant with a REAL 4xx (not 000/200)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$TOKEN_URL" \
  -d "client_id=easyshop-angular" -d "grant_type=password" \
  -d "username=demo.customer" -d "password=Customer#Pass1" || true)
case "$CODE" in
  400|401|403) ok "SPA client rejects password grant ($CODE) - ROPC correctly disabled" ;;
  000)         bad "SPA negative test: no connection (000) - inconclusive, NOT a pass" ;;
  200)         bad "SPA client ACCEPTED password grant - security misconfiguration!" ;;
  *)           bad "SPA negative test: unexpected http $CODE" ;;
esac

echo ""
echo "======================================================================="
echo "  RESULT: $PASS passed, $FAIL failed"
echo "======================================================================="
exit $FAIL