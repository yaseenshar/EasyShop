#!/usr/bin/env bash
# provision-gateway-bff-client.sh
#
# Idempotent provisioning of the confidential client used by the api-gateway for
# the BFF Authorization Code flow. Same contract as fix-client-secrets.sh:
# safe to re-run, verifies its own work, exits non-zero on any failed assertion.
#
# Per the §4.17 rule: the realm JSON declares structure only; clients that need
# secrets and scope attachments are provisioned by scripts like this one.
#
# Required env:
#   GATEWAY_BFF_CLIENT_SECRET   the secret to set (generate once, keep in .env)
# Optional env (defaults shown):
#   KEYCLOAK_CONTAINER=easyshop-keycloak
#   REALM=easyshop
#   REDIRECT_URI=http://localhost:8080/login/oauth2/code/keycloak
#   AUDIENCE_SCOPE=easyshop-audience   # MUST match provision-audience.sh
#   KC_BOOTSTRAP_ADMIN_USERNAME / KC_BOOTSTRAP_ADMIN_PASSWORD
#       (if unset, read from the Keycloak container's own environment)

set -euo pipefail

KEYCLOAK_CONTAINER="${KEYCLOAK_CONTAINER:-easyshop-keycloak}"
REALM="${REALM:-easyshop}"
CLIENT_ID="easyshop-gateway-bff"
REDIRECT_URI="${REDIRECT_URI:-http://localhost:8080/login/oauth2/code/keycloak}"
AUDIENCE_SCOPE="${AUDIENCE_SCOPE:-easyshop-audience}"
SECRET="${GATEWAY_BFF_CLIENT_SECRET:?Set GATEWAY_BFF_CLIENT_SECRET}"

KC_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-$(docker exec "$KEYCLOAK_CONTAINER" printenv KC_BOOTSTRAP_ADMIN_USERNAME)}"
KC_PASS="${KC_BOOTSTRAP_ADMIN_PASSWORD:-$(docker exec "$KEYCLOAK_CONTAINER" printenv KC_BOOTSTRAP_ADMIN_PASSWORD)}"

KCADM() { docker exec -i "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

PASS=0; FAIL=0
ok() { echo "  PASS  $1"; PASS=$((PASS+1)); }
ko() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }

echo "==> kcadm login (container-internal http://localhost:8080)"
KCADM config credentials --server http://localhost:8080 --realm master \
  --user "$KC_USER" --password "$KC_PASS"

# ---------------------------------------------------------------- ensure client
get_client_id() {
  KCADM get clients -r "$REALM" -q clientId="$CLIENT_ID" \
    --fields id --format csv --noquotes 2>/dev/null | head -n1
}

CID="$(get_client_id || true)"

if [ -z "$CID" ]; then
  echo "==> Creating client $CLIENT_ID"
  KCADM create clients -r "$REALM" \
    -s clientId="$CLIENT_ID" \
    -s protocol=openid-connect \
    -s enabled=true \
    -s publicClient=false \
    -s standardFlowEnabled=true \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=false \
    -s "redirectUris=[\"$REDIRECT_URI\"]" \
    -s "webOrigins=[\"http://localhost:8080\"]" >/dev/null
  CID="$(get_client_id)"
  echo "    created (id=$CID)"
else
  echo "==> Client exists (id=$CID) — converging mutable settings"
  KCADM update "clients/$CID" -r "$REALM" \
    -s publicClient=false \
    -s standardFlowEnabled=true \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s "redirectUris=[\"$REDIRECT_URI\"]"
fi

echo "==> Setting client secret (idempotent overwrite)"
KCADM update "clients/$CID" -r "$REALM" -s secret="$SECRET"

# ------------------------------------------------- attach audience scope
# Relayed access tokens must carry the aud claim the resource servers are
# starting to enforce (handoff §8.2 #4). Reuses the scope created by
# resource-server-hardening/provision-audience.sh.
echo "==> Attaching client scope '$AUDIENCE_SCOPE' as default"
SCOPE_ID=$(KCADM get client-scopes -r "$REALM" --fields id,name --format csv --noquotes \
  | awk -F, -v s="$AUDIENCE_SCOPE" '$2==s {print $1; exit}')

if [ -z "$SCOPE_ID" ]; then
  echo "    WARNING: scope '$AUDIENCE_SCOPE' not found in realm '$REALM'."
  echo "    Run resource-server-hardening/provision-audience.sh first, or set"
  echo "    AUDIENCE_SCOPE to the correct name. Continuing without attachment."
else
  # PUT is idempotent — attaching an already-attached scope is a no-op.
  KCADM update "clients/$CID/default-client-scopes/$SCOPE_ID" -r "$REALM" || true
  echo "    attached (scope id=$SCOPE_ID)"
fi

# ---------------------------------------------------------------- verification
echo "==> Verifying provisioned state"
CLIENT_JSON=$(KCADM get "clients/$CID" -r "$REALM")

check_json() { # $1 label, $2 grep pattern
  if printf '%s' "$CLIENT_JSON" | grep -q "$2"; then ok "$1"; else ko "$1"; fi
}

check_json "confidential (publicClient=false)"        '"publicClient" *: *false'
check_json "standard flow enabled"                    '"standardFlowEnabled" *: *true'
check_json "direct access grants DISABLED"            '"directAccessGrantsEnabled" *: *false'
check_json "service accounts disabled"                '"serviceAccountsEnabled" *: *false'
check_json "redirect URI registered"                  "$REDIRECT_URI"

SECRET_JSON=$(KCADM get "clients/$CID/client-secret" -r "$REALM")
if printf '%s' "$SECRET_JSON" | grep -q '"value"'; then
  ok "client secret is set"
else
  ko "client secret is set"
fi

if [ -n "${SCOPE_ID:-}" ]; then
  ATTACHED=$(KCADM get "clients/$CID/default-client-scopes" -r "$REALM")
  if printf '%s' "$ATTACHED" | grep -q "\"$AUDIENCE_SCOPE\""; then
    ok "audience scope attached as default"
  else
    ko "audience scope attached as default"
  fi
fi

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1