#!/usr/bin/env bash
# =============================================================================
# provision-self-registration.sh
#
# Makes Keycloak's own registration page ("registrationAllowed": true in the
# realm JSON) actually produce a USABLE EasyShop account, not just a Keycloak
# user record. Two gaps closed here, same explicit-provisioning pattern as
# provision-audience.sh / add-sub-mapper.sh (realm JSON carries structure;
# live realm changes go through kcadm):
#
#   1. DEFAULT ROLE: a freshly registered user gets ONLY Keycloak's own
#      default-roles-easyshop composite (offline_access, uma_authorization) -
#      none of OUR app roles. Every service's SecurityConfig gates /me behind
#      hasAnyRole("CUSTOMER","VENDOR","ADMIN"), so a brand-new self-registered
#      user 403s on their very first request. Fix: add CUSTOMER as a member of
#      that composite so every new registration inherits it automatically.
#
#   2. PROFILE CLAIMS: this realm's import declared its own "clientScopes"
#      array (see add-sub-mapper.sh's root-cause note), which replaces
#      Keycloak's built-in scopes entirely - "profile" and "email" were never
#      created, so tokens carry no email/given_name/family_name. user-service's
#      JIT provisioning (creating the local `users` row on a new account's
#      first /me call) needs those claims to fill in the row. Fix: add the
#      three mappers directly to 'easyshop-api-audience' - already a default
#      scope on every client that matters - rather than standing up a whole
#      separate scope and re-attaching it everywhere.
#
# Safe to re-run; verifies its own work.
# =============================================================================
set -o pipefail
for ENVF in "$(dirname "$0")/.env" "./.env"; do
  [ -f "$ENVF" ] && { set -a; source "$ENVF"; set +a; echo "Loaded $ENVF"; break; }
done
[ -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ] && { echo "FATAL: KEYCLOAK_ADMIN_PASSWORD missing"; exit 1; }
[ -z "${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}" ] && { echo "FATAL: KEYCLOAK_GATEWAY_CLIENT_SECRET missing"; exit 1; }

KCADM="docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh"
REALM="easyshop"
AUDIENCE_SCOPE="easyshop-api-audience"

PASS=0; FAIL=0
ok() { echo "  PASS  $1"; PASS=$((PASS+1)); }
ko() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }

echo "=== kcadm login ========================================================"
$KCADM config credentials --server http://localhost:8080 \
  --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" || exit 1

echo "=== 1. CUSTOMER as a default role for new registrations ================"
DEFAULT_ROLE_ID=$($KCADM get "roles/default-roles-$REALM" -r "$REALM" | jq -r '.id')
[ -z "$DEFAULT_ROLE_ID" ] || [ "$DEFAULT_ROLE_ID" = "null" ] && {
  echo "FATAL: could not find default-roles-$REALM"; exit 1;
}
# NOTE: `kcadm get roles -q name=X` silently ignores the filter and returns
# the full list (empirically confirmed) - filter with jq instead, not -q.
CUSTOMER_ROLE_JSON=$($KCADM get roles -r "$REALM" | jq -c '.[] | select(.name=="CUSTOMER")')
[ -z "$CUSTOMER_ROLE_JSON" ] && { echo "FATAL: CUSTOMER role not found"; exit 1; }

ALREADY_MEMBER=$($KCADM get "roles-by-id/$DEFAULT_ROLE_ID/composites" -r "$REALM" \
  | jq -e '.[] | select(.name=="CUSTOMER")' >/dev/null 2>&1 && echo yes || echo no)
if [ "$ALREADY_MEMBER" = "yes" ]; then
  echo "  CUSTOMER already a default role - no change needed"
else
  # The composites endpoint takes an ARRAY of RoleRepresentation, not a bare object.
  echo "[$CUSTOMER_ROLE_JSON]" | $KCADM create "roles-by-id/$DEFAULT_ROLE_ID/composites" -r "$REALM" -f - \
    && echo "  CUSTOMER added to default-roles-$REALM"
fi

echo "=== 2. email/given_name/family_name claims on $AUDIENCE_SCOPE =========="
SCOPE_ID=$($KCADM get client-scopes -r "$REALM" | jq -r --arg n "$AUDIENCE_SCOPE" '.[] | select(.name==$n) | .id')
[ -z "$SCOPE_ID" ] && { echo "FATAL: $AUDIENCE_SCOPE missing - run provision-audience.sh first"; exit 1; }

add_mapper() { # $1 mapper-name  $2 user-attribute  $3 claim-name
  local existing
  existing=$($KCADM get "client-scopes/$SCOPE_ID/protocol-mappers/models" -r "$REALM" \
    | jq -r --arg n "$1" '.[] | select(.name==$n) | .id')
  if [ -n "$existing" ]; then
    echo "  mapper '$1' already exists - skipping"
    return
  fi
  $KCADM create "client-scopes/$SCOPE_ID/protocol-mappers/models" -r "$REALM" \
    -s "name=$1" \
    -s protocol=openid-connect \
    -s protocolMapper=oidc-usermodel-property-mapper \
    -s "config.\"user.attribute\"=$2" \
    -s "config.\"claim.name\"=$3" \
    -s 'config."jsonType.label"=String' \
    -s 'config."access.token.claim"=true' \
    -s 'config."id.token.claim"=true' \
    -s 'config."userinfo.token.claim"=true' \
    && echo "  added mapper '$1' ($2 -> $3)"
}
add_mapper "easyshop-email-mapper" "email" "email"
add_mapper "easyshop-given-name-mapper" "firstName" "given_name"
add_mapper "easyshop-family-name-mapper" "lastName" "family_name"

echo "=== Verify: mint a token and decode ===================================="
claims() {
  echo "$1" | cut -d. -f2 | tr '_-' '/+' \
    | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' \
    | base64 -d 2>/dev/null
}
TOKEN=$(curl -s -X POST http://localhost:8090/realms/$REALM/protocol/openid-connect/token \
  -d client_id=easyshop-gateway -d "client_secret=$KEYCLOAK_GATEWAY_CLIENT_SECRET" \
  -d grant_type=password -d username=demo.customer -d 'password=Customer#Pass1' \
  | jq -r .access_token)
[ -z "$TOKEN" ] || [ "$TOKEN" = "null" ] && { echo "FATAL: could not mint a token to verify"; exit 1; }
DECODED=$(claims "$TOKEN")

check() { # $1 label  $2 jq-filter
  local v
  v=$(echo "$DECODED" | jq -r "$2")
  if [ -n "$v" ] && [ "$v" != "null" ]; then ok "$1 = $v"; else ko "$1 missing"; fi
}
check "email" '.email'
check "given_name" '.given_name'
check "family_name" '.family_name'
check "roles claim still present (converter needs this)" '.roles'

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
