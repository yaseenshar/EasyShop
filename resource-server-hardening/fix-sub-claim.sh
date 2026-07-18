#!/usr/bin/env bash
# =============================================================================
# Diagnose + fix a missing 'sub' claim in Keycloak access tokens.
# Root symptom: jwt.getSubject() == null in every resource server, so any
# lookup keyed on keycloak_id fails no matter what is in the database.
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

echo "=== 1. EVERY claim currently in the access token ======================"
claims "$(mint)" | jq 'keys'
echo "  ^ if 'sub' is absent from this list, that is the bug."

echo ""
echo "=== 2. Default client scopes on easyshop-gateway ======================"
$KCADM config credentials --server http://localhost:8080 --realm master \
  --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1
UUID=$($KCADM get clients -r easyshop -q clientId=easyshop-gateway --fields id | jq -r '.[0].id')
$KCADM get "clients/$UUID/default-client-scopes" -r easyshop | jq -r '.[].name' | sed 's/^/  /'
echo "  ^ 'basic' provides the sub + auth_time mappers in Keycloak 24+."

echo ""
echo "=== 3. FIX: attach 'basic' to every client if missing =================="
BASIC_ID=$($KCADM get client-scopes -r easyshop | jq -r '.[] | select(.name=="basic") | .id')
if [ -z "$BASIC_ID" ]; then
  echo "  'basic' client scope does not exist in this realm."
  echo "  -> fall back to adding a sub mapper directly (section 4)."
else
  for CID in easyshop-gateway easyshop-angular easyshop-review-service; do
    U=$($KCADM get clients -r easyshop -q "clientId=$CID" --fields id | jq -r '.[0].id // empty')
    [ -z "$U" ] && continue
    $KCADM update "clients/$U/default-client-scopes/$BASIC_ID" -r easyshop 2>/dev/null \
      && echo "  attached 'basic' to $CID" || echo "  $CID: already attached or attach failed"
  done
fi

echo ""
echo "=== 4. Fallback: add an explicit sub mapper to our own scope ==========="
echo "  Only needed if section 5 still shows no 'sub'. Run manually:"
SCOPE_ID=$($KCADM get client-scopes -r easyshop | jq -r '.[] | select(.name=="easyshop-api-audience") | .id')
cat <<HINT
  docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh \\
    create client-scopes/$SCOPE_ID/protocol-mappers/models -r easyshop \\
    -s name=sub-mapper -s protocol=openid-connect \\
    -s protocolMapper=oidc-sub-mapper \\
    -s 'config."access.token.claim"=true' \\
    -s 'config."introspection.token.claim"=true'
HINT

echo ""
echo "=== 5. Re-mint and confirm 'sub' is present ==========================="
NEW=$(mint)
SUB=$(claims "$NEW" | jq -r '.sub // "STILL MISSING"')
echo "  sub = $SUB"
if [ "$SUB" != "STILL MISSING" ]; then
  echo "  FIXED. Compare against the DB row:"
  docker compose exec -T postgres psql -U easyshop -d easyshop_user \
    -c "SELECT keycloak_id FROM users;" 2>/dev/null | sed 's/^/    /'
  echo "  If sub matches keycloak_id above, /me will now return 200."
  echo "  If it does NOT match, the DB row belongs to a different Keycloak"
  echo "  user - re-register with the sub shown here."
else
  echo "  Still missing - run the section 4 fallback, then re-run this script."
fi
