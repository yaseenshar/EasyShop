#!/usr/bin/env bash
# =============================================================================
# Provision the 'easyshop-api' audience into all EasyShop tokens.
# Same explicit-provisioning pattern as fix-client-secrets.sh (import-time
# realm JSON carries structure; live realm changes go through kcadm).
#
# What this does:
#   1. Creates client scope 'easyshop-api-audience' with an oidc-audience-
#      mapper that hardcodes 'easyshop-api' into the access token's aud claim
#   2. Attaches it as a DEFAULT scope to each EXISTING client - necessary
#      because realm-level defaultDefaultClientScopes only applies to
#      clients created AFTER the setting exists, not retroactively
#
# Run BEFORE enabling spring.security...jwt.audiences in any service,
# or every token in the system will be rejected.
# =============================================================================
set -o pipefail
for ENVF in "$(dirname "$0")/.env" "$(dirname "$0")/../../.env" "./.env"; do
  [ -f "$ENVF" ] && { set -a; source "$ENVF"; set +a; echo "Loaded $ENVF"; break; }
done
[ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ] && { echo "FATAL: KEYCLOAK_ADMIN_PASSWORD missing"; exit 1; }

DC_FILE="$(dirname "$0")/../../infra/docker-compose.yml"
KCADM="docker compose -f $DC_FILE exec -T keycloak /opt/keycloak/bin/kcadm.sh"
AUDIENCE="easyshop-api"
SCOPE_NAME="easyshop-api-audience"

echo "=== kcadm login ========================================================"
$KCADM config credentials --server http://localhost:8080 \
  --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" || exit 1

echo "=== Client scope + audience mapper ====================================="
SCOPE_ID=$($KCADM get client-scopes -r easyshop 2>/dev/null \
  | jq -r --arg n "$SCOPE_NAME" '.[] | select(.name == $n) | .id')

if [ -n "$SCOPE_ID" ]; then
  echo "  scope '$SCOPE_NAME' already exists ($SCOPE_ID) - reusing"
else
  $KCADM create client-scopes -r easyshop \
    -s "name=$SCOPE_NAME" \
    -s protocol=openid-connect \
    -s 'attributes."include.in.token.scope"=false' || exit 1
  SCOPE_ID=$($KCADM get client-scopes -r easyshop \
    | jq -r --arg n "$SCOPE_NAME" '.[] | select(.name == $n) | .id')
  echo "  created scope '$SCOPE_NAME' ($SCOPE_ID)"

  $KCADM create "client-scopes/$SCOPE_ID/protocol-mappers/models" -r easyshop \
    -s name=easyshop-audience-mapper \
    -s protocol=openid-connect \
    -s protocolMapper=oidc-audience-mapper \
    -s "config.\"included.custom.audience\"=$AUDIENCE" \
    -s 'config."access.token.claim"=true' \
    -s 'config."id.token.claim"=false' || exit 1
  echo "  added audience mapper (aud += $AUDIENCE)"
fi

echo "=== Attaching to existing clients (not retroactive via realm default) =="
for CID in easyshop-gateway easyshop-angular easyshop-review-service; do
  UUID=$($KCADM get clients -r easyshop -q "clientId=$CID" --fields id \
    | jq -r '.[0].id // empty')
  if [ -z "$UUID" ]; then echo "  WARN: client $CID not found - skipped"; continue; fi
  # PUT is idempotent - re-attaching an attached scope is a no-op
  $KCADM update "clients/$UUID/default-client-scopes/$SCOPE_ID" -r easyshop 2>/dev/null \
    && echo "  attached to $CID" \
    || echo "  WARN: attach failed for $CID - verify in admin console (Clients -> $CID -> Client scopes)"
done

echo ""
echo "=== Proof: mint a token and inspect aud ================================"
if [ -n "${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}" ]; then
  T=$(curl -s -X POST http://localhost:8090/realms/easyshop/protocol/openid-connect/token \
    -d client_id=easyshop-gateway -d "client_secret=$KEYCLOAK_GATEWAY_CLIENT_SECRET" \
    -d grant_type=password -d username=demo.customer -d 'password=Customer#Pass1' \
    | jq -r '.access_token')
  AUD=$(echo "$T" | cut -d. -f2 | tr '_-' '/+' \
    | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' \
    | base64 -d 2>/dev/null | jq -c '.aud')
  echo "  fresh token aud claim: $AUD"
  echo "$AUD" | grep -q "$AUDIENCE" \
    && echo "  OK - SAFE to enable spring.security.oauth2.resourceserver.jwt.audiences now" \
    || echo "  NOT PRESENT - do NOT enable the audiences property yet; check warnings above"
else
  echo "  (skipped - KEYCLOAK_GATEWAY_CLIENT_SECRET not in .env)"
fi
