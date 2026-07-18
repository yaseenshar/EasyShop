#!/usr/bin/env bash
# =============================================================================
# Resource server verification - proves user-service validates JWTs correctly,
# including the ADVERSARIAL cases (the ones that matter for a security ticket).
# Run AFTER provision-audience.sh and after enabling the audiences property +
# restarting user-service.
#
# Tests go through the GATEWAY (:8080) so we exercise the real request path.
# =============================================================================
set -o pipefail
for ENVF in "$(dirname "$0")/.env" "./.env"; do
  [ -f "$ENVF" ] && { set -a; source "$ENVF"; set +a; break; }
done
GW="http://localhost:8080"
KC="http://localhost:8090/realms/easyshop/protocol/openid-connect/token"
PASS=0; FAIL=0
ok(){ echo "  PASS  $1"; PASS=$((PASS+1)); }
bad(){ echo "  FAIL  $1"; FAIL=$((FAIL+1)); }

TOKEN=$(curl -s -X POST "$KC" \
  -d client_id=easyshop-gateway -d "client_secret=$KEYCLOAK_GATEWAY_CLIENT_SECRET" \
  -d grant_type=password -d username=demo.customer -d 'password=Customer#Pass1' \
  | jq -r '.access_token')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "FATAL: no token (run keycloak verify first)"; exit 1; }

code(){ curl -s -o /dev/null -w '%{http_code}' --connect-timeout 3 "$@"; }

# ── Target selection: gateway preferred, direct service as fallback ─────────
# Testing BOTH isolates a gateway problem from a resource-server problem -
# a 000 everywhere means nothing is listening, not that validation failed.
echo "=== Preflight: what is actually running ==============================="
GW_UP=$([ "$(code $GW/actuator/health)" != "000" ] && echo yes || echo no)
US_UP=$([ "$(code http://localhost:8081/actuator/health)" != "000" ] && echo yes || echo no)
echo "  api-gateway   :8080  reachable=$GW_UP"
echo "  user-service  :8081  reachable=$US_UP"

if [ "$GW_UP" = "yes" ]; then
  BASE="$GW"; VIA="via gateway"
elif [ "$US_UP" = "yes" ]; then
  BASE="http://localhost:8081"; VIA="DIRECT to user-service (gateway down)"
  echo "  NOTE: gateway is down - testing user-service directly. This still"
  echo "        fully validates the resource server (the ticket's scope)."
else
  echo ""
  echo "  FATAL: neither the gateway nor user-service is running."
  echo "    docker compose ps            # what is up?"
  echo "    docker compose up -d api-gateway user-service"
  echo "    docker compose logs --tail=50 user-service"
  exit 1
fi
echo "  Testing $VIA -> $BASE"
echo ""

echo "=== Positive: valid token is accepted =================================="
C=$(code -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/users/me")
[ "$C" = "200" ] || [ "$C" = "404" ] \
  && ok "valid token accepted at /users/me (http $C; 404 = authed but no profile row yet)" \
  || { bad "valid token got $C (expected 200, or 404 if user unregistered)"
       [ "$C" = "401" ] && cat <<'HINT'
        401 on a VALID token = the resource server rejected it. Note a 503
        would mean auth PASSED but the downstream service is down - so 401
        points at validation. Check, in order:
          1. issuer mismatch: token iss vs configured issuer-uri, exact string
          2. JWKS unreachable from inside the container (localhost != Keycloak)
          3. audiences property enabled before the aud claim existed
        See oidc-container-fix/README-and-env.md
HINT
       [ "$C" = "503" ] && echo "        503 = auth OK, but the downstream service is not running."
     }

echo "=== Negative: the attacks that MUST be rejected ========================"
# 1. No token
C=$(code "$BASE/api/v1/users/me")
[ "$C" = "401" ] && ok "no token -> 401" || bad "no token got $C"

# 2. Garbage token
C=$(code -H "Authorization: Bearer not.a.jwt" "$BASE/api/v1/users/me")
[ "$C" = "401" ] && ok "malformed token -> 401" || bad "malformed got $C"

# 3. alg=none forgery: valid-looking header/payload, algorithm 'none', no sig.
#    The canonical JWT attack - must be rejected.
b64url(){ printf '%s' "$1" | base64 | tr '+/' '-_' | tr -d '='; }
H=$(b64url '{"alg":"none","typ":"JWT"}')
P=$(b64url '{"sub":"forged","iss":"http://localhost:8090/realms/easyshop","roles":["ADMIN"]}')
C=$(code -H "Authorization: Bearer ${H}.${P}." "$BASE/api/v1/users/me")
# Accept 400 OR 401: per RFC 6750 Spring returns 401 invalid_token for a
# well-formed-but-invalid token and 400 invalid_request for a malformed one.
# An alg=none token has an EMPTY signature segment, so it is often rejected
# as malformed before validation runs. Either way it was REJECTED - which is
# the security property. Only a 2xx (routed downstream) would be a breach.
# (v1 of this test demanded exactly 401 and wrongly flagged 400 as CRITICAL.)
case "$C" in
  400|401|403) ok "alg=none forgery rejected ($C) - signature enforcement works" ;;
  *)           bad "alg=none forgery got $C - CRITICAL: anything 2xx means ACCEPTED" ;;
esac

# 4. Tampered payload: real token with the middle segment swapped for a
#    self-elevated one -> signature no longer matches -> 401.
FORGED_P=$(b64url '{"sub":"attacker","roles":["ADMIN"]}')
ORIG_H=$(echo "$TOKEN" | cut -d. -f1); ORIG_S=$(echo "$TOKEN" | cut -d. -f3)
C=$(code -H "Authorization: Bearer ${ORIG_H}.${FORGED_P}.${ORIG_S}" "$BASE/api/v1/users/me")
case "$C" in
  400|401|403) ok "tampered payload rejected ($C) - signature binds the claims" ;;
  *)           bad "tampered payload got $C - CRITICAL: anything 2xx means ACCEPTED" ;;
esac

echo "=== Audience (only meaningful once the property is enabled) ============"
AUD=$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' \
  | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' \
  | base64 -d 2>/dev/null | jq -c '.aud // "ABSENT"')
echo "  INFO current token aud: $AUD"
echo "$AUD" | grep -q easyshop-api \
  && ok "token carries easyshop-api audience" \
  || bad "aud missing easyshop-api - run provision-audience.sh before enabling the property"

echo ""
echo "======================================================================="
echo "  RESULT: $PASS passed, $FAIL failed"
echo "======================================================================="
exit $FAIL