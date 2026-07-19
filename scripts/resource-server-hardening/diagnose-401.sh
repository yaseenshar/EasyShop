#!/usr/bin/env bash
# =============================================================================
# Decisive 401 diagnosis: reads Spring Security's WWW-Authenticate header,
# which names the exact validation failure. Also compares gateway vs direct
# service, and shows the effective config each one is actually using.
# =============================================================================
set -o pipefail
for E in "$(dirname "$0")/.env" "$(dirname "$0")/../../.env" "./.env"; do [ -f "$E" ] && { set -a; source "$E"; set +a; break; }; done

DC_FILE="$(dirname "$0")/../../infra/docker-compose.yml"
DC() { docker compose -f "$DC_FILE" "$@"; }

TOKEN=$(curl -s -X POST http://localhost:8090/realms/easyshop/protocol/openid-connect/token \
  -d client_id=easyshop-gateway -d "client_secret=$KEYCLOAK_GATEWAY_CLIENT_SECRET" \
  -d grant_type=password -d username=demo.customer -d 'password=Customer#Pass1' \
  | jq -r .access_token)
[ -z "$TOKEN" ] || [ "$TOKEN" = "null" ] && { echo "FATAL: no token"; exit 1; }

echo "=== 1. What the TOKEN claims ==========================================="
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' \
  | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' \
  | base64 -d 2>/dev/null | jq '{iss, aud, azp, exp}'
echo "  ^ 'iss' must EXACTLY equal each service's configured issuer-uri."

echo ""
echo "=== 2. THE ANSWER: WWW-Authenticate from each target ==================="
for T in "gateway|http://localhost:8080" "user-service|http://localhost:8081"; do
  NAME="${T%%|*}"; URL="${T##*|}"
  echo "--- $NAME ---"
  RESP=$(curl -s -i -H "Authorization: Bearer $TOKEN" "$URL/api/v1/users/me" 2>/dev/null)
  echo "$RESP" | head -1
  WWW=$(echo "$RESP" | grep -i '^www-authenticate:')
  if [ -n "$WWW" ]; then
    echo "  $WWW"
    case "$WWW" in
      *"iss claim is not valid"*|*"issuer"*)
        echo "  >>> ISSUER MISMATCH. Set issuer-uri to the iss shown in step 1." ;;
      *"aud claim is not valid"*|*"audience"*)
        echo "  >>> AUDIENCE MISMATCH. Token aud vs configured audiences." ;;
      *"matching key"*|*"decode"*|*"Signed JWT rejected"*|*"jwk"*|*"JWK"*)
        echo "  >>> JWKS FETCH/KEY PROBLEM. The service cannot reach or use the"
        echo "      key set - classic 'localhost inside container' case." ;;
      *expired*)
        echo "  >>> TOKEN EXPIRED (15-min lifespan) - re-mint and retry." ;;
      *)
        echo "  >>> See the error_description above." ;;
    esac
  else
    echo "  (no WWW-Authenticate header - not a token rejection; if 503 the"
    echo "   downstream service is down, if 404 auth SUCCEEDED)"
  fi
done

echo ""
echo "=== 3. Effective config each service is actually using ================="
for S in api-gateway user-service; do
  echo "--- $S ---"
  DC exec -T "$S" printenv 2>/dev/null \
    | grep -i "OAUTH2_RESOURCESERVER" | sed 's/^/  /' \
    || echo "  (no OAUTH2 env vars set - service is using application.yml values)"
done

echo ""
echo "=== 4. Can the containers actually reach the JWKS endpoint? ============"
for S in api-gateway user-service; do
  R=$(DC exec -T "$S" sh -c \
    'command -v wget >/dev/null && wget -qO- --timeout=3 http://keycloak:8080/realms/easyshop/protocol/openid-connect/certs 2>/dev/null | head -c 60' 2>/dev/null)
  if [ -n "$R" ]; then echo "  $S -> keycloak:8080 JWKS reachable"
  else echo "  $S -> could not fetch (no wget in image, or genuinely unreachable)"; fi
done

echo ""
echo "TIP: for full detail, add to the failing service and restart:"
echo "  LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY: DEBUG"
echo "  then: docker compose -f infra/docker-compose.yml logs --tail=80 api-gateway | grep -i jwt"
