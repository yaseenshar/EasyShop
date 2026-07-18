#!/usr/bin/env bash
# =============================================================================
# ROOT CAUSE: the realm import declared a "clientScopes" array, which Keycloak
# treats as the COMPLETE set for the realm - so the built-in scopes (basic,
# profile, email, web-origins, acr) were never created. 'basic' is the scope
# that carries the oidc-sub-mapper, hence no 'sub' claim in any token.
#
# FIX: add an explicit sub mapper to our own scope. Our services only read
# 'sub' and 'roles', so this restores everything the code actually needs
# without recreating Keycloak's whole default scope set.
# =============================================================================
for E in "$(dirname "$0")/.env" "./.env"; do [ -f "$E" ] && { set -a; source "$E"; set +a; break; }; done
KCADM="docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh"

mint() {
  curl -s -X POST http://localhost:8090/realms/easyshop/protocol/openid-connect/token \
    -d client_id=easyshop-gateway -d "client_secret=$KEYCLOAK_GATEWAY_CLIENT_SECRET" \
    -d grant_type=password -d username=demo.customer -d 'password=Customer#Pass1' \
    | jq -r .access_token
}
claims() {
  echo "$1" | cut -d. -f2 | tr '_-' '/+' \
    | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' \
    | base64 -d 2>/dev/null
}

$KCADM config credentials --server http://localhost:8080 --realm master \
  --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1 \
  || { echo "FATAL: kcadm login failed"; exit 1; }

SCOPE_ID=$($KCADM get client-scopes -r easyshop | jq -r '.[] | select(.name=="easyshop-api-audience") | .id')
[ -z "$SCOPE_ID" ] && { echo "FATAL: easyshop-api-audience scope missing - run provision-audience.sh"; exit 1; }

echo "=== Adding oidc-sub-mapper to easyshop-api-audience ==================="
if $KCADM create "client-scopes/$SCOPE_ID/protocol-mappers/models" -r easyshop \
     -s name=sub-mapper \
     -s protocol=openid-connect \
     -s protocolMapper=oidc-sub-mapper \
     -s 'config."access.token.claim"=true' \
     -s 'config."introspection.token.claim"=true' 2>/dev/null; then
  echo "  sub mapper created"
else
  echo "  create returned non-zero (already exists is fine) - verifying below"
fi

echo ""
echo "=== Verify: sub is now in the token ==================================="
NEW=$(mint)
SUB=$(claims "$NEW" | jq -r '.sub // "STILL MISSING"')
echo "  sub = $SUB"
[ "$SUB" = "STILL MISSING" ] && {
  echo "  Mapper did not take effect. Check the admin console:"
  echo "  Client scopes -> easyshop-api-audience -> Mappers"
  exit 1
}

echo ""
echo "=== Reconcile with user-service's database ============================"
DBID=$(docker compose exec -T postgres psql -U easyshop -d easyshop_user -tAc \
  "SELECT keycloak_id FROM users WHERE email='demo.customer@easyshop.test';" 2>/dev/null | tr -d '[:space:]')
echo "  token sub  : $SUB"
echo "  db row     : ${DBID:-<none>}"

if [ "$SUB" = "$DBID" ]; then
  echo "  MATCH - /me should now return 200."
elif [ -z "$DBID" ]; then
  echo "  No row - registering demo.customer now..."
  curl -s -X POST http://localhost:8081/api/v1/users/register \
    -H "Content-Type: application/json" \
    -d "{\"keycloakId\":\"$SUB\",\"email\":\"demo.customer@easyshop.test\",\"firstName\":\"Demo\",\"lastName\":\"Customer\"}" | jq -c .
else
  echo "  MISMATCH. The existing row's keycloak_id is STALE - it came from the"
  echo "  realm that existed BEFORE we deleted and re-imported it during the"
  echo "  secret fix. Deleting a realm destroys its users; the recreated"
  echo "  demo.customer has a brand-new Keycloak ID. Repointing the row:"
  docker compose exec -T postgres psql -U easyshop -d easyshop_user -c \
    "UPDATE users SET keycloak_id='$SUB' WHERE email='demo.customer@easyshop.test';" 2>/dev/null \
    | sed 's/^/    /'
fi

echo ""
echo "=== Final check: /me through the gateway =============================="
curl -s -o /dev/null -w '  status: %{http_code}\n' \
  -H "Authorization: Bearer $NEW" http://localhost:8080/api/v1/users/me
echo "  200 = done. 500 = GlobalExceptionHandler still not installed AND"
echo "  something else is throwing - check user-service logs."