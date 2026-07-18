#!/usr/bin/env bash
# =============================================================================
# Keycloak client-secret provisioning (diagnose + reconcile)
# Run from project root: ./fix-client-secrets.sh   then re-run verify.
#
# WHY THIS EXISTS: ${env.VAR} substitution in realm imports is a silent-
# failure mechanism - if the var is missing (or the realm predates it, since
# --import-realm never overwrites), you get a validly-imported realm holding
# the literal placeholder as its secret, and nothing ever errors. This script
# replaces that fragility with the production pattern: structure lives in the
# realm JSON, secrets are INJECTED by an explicit, idempotent provisioning
# step that can be re-run any time and verified immediately.
# =============================================================================
set -o pipefail

# ── Load .env ────────────────────────────────────────────────────────────────
for ENVF in "$(dirname "$0")/.env" "./.env"; do
  [ -f "$ENVF" ] && { set -a; source "$ENVF"; set +a; echo "Loaded $ENVF"; break; }
done

for V in KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_GATEWAY_CLIENT_SECRET KEYCLOAK_REVIEW_SERVICE_CLIENT_SECRET; do
  if [ -z "${!V:-}" ]; then echo "FATAL: $V missing from .env"; exit 1; fi
done

KCADM="docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh"

# ── Diagnosis 1: did the env vars ever reach the container? ──────────────────
echo ""
echo "=== Diagnosis: container environment ==================================="
for V in KEYCLOAK_GATEWAY_CLIENT_SECRET KEYCLOAK_REVIEW_SERVICE_CLIENT_SECRET; do
  if docker compose exec -T keycloak printenv "$V" >/dev/null 2>&1; then
    echo "  present in container: $V (import substitution SHOULD have worked)"
  else
    echo "  ABSENT from container: $V (explains the placeholder secrets -"
    echo "    either not in the keycloak environment: block, or container"
    echo "    not recreated after adding it)"
  fi
done

# ── kcadm login ──────────────────────────────────────────────────────────────
echo ""
echo "=== Logging into kcadm ================================================="
$KCADM config credentials --server http://localhost:8080 \
  --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" \
  || { echo "FATAL: kcadm login failed - check KEYCLOAK_ADMIN_PASSWORD"; exit 1; }

reconcile_client() { # <clientId> <desired-secret>
  local CID="$1" SECRET="$2"
  local UUID CURRENT

  UUID=$($KCADM get clients -r easyshop -q "clientId=$CID" --fields id 2>/dev/null | jq -r '.[0].id // empty')
  if [ -z "$UUID" ]; then
    echo "  FATAL: client '$CID' not found in realm easyshop - realm not imported?"
    return 1
  fi

  # Diagnosis 2: what secret does Keycloak hold RIGHT NOW?
  CURRENT=$($KCADM get "clients/$UUID/client-secret" -r easyshop 2>/dev/null | jq -r '.value // "unreadable"')
  if [[ "$CURRENT" == *'${env.'* ]]; then
    echo "  $CID: current secret is the LITERAL placeholder -> substitution never ran (root cause confirmed)"
  elif [ "$CURRENT" = "$SECRET" ]; then
    echo "  $CID: secret already matches .env - nothing to do"
    return 0
  else
    echo "  $CID: secret differs from .env (stale import or manual edit) - reconciling"
  fi

  $KCADM update "clients/$UUID" -r easyshop -s "secret=$SECRET" \
    && echo "  $CID: secret set from .env" \
    || { echo "  FATAL: failed to update $CID secret"; return 1; }
}

echo ""
echo "=== Reconciling client secrets ========================================="
reconcile_client "easyshop-gateway"        "$KEYCLOAK_GATEWAY_CLIENT_SECRET"        || exit 1
reconcile_client "easyshop-review-service" "$KEYCLOAK_REVIEW_SERVICE_CLIENT_SECRET" || exit 1

# ── SERVICE role for the M2M service account (the other fragile import) ─────
echo ""
echo "=== Ensuring SERVICE role on review-service service account ==========="
if $KCADM add-roles -r easyshop \
     --uusername service-account-easyshop-review-service \
     --rolename SERVICE 2>/dev/null; then
  echo "  SERVICE role assigned"
else
  # add-roles errors if already assigned OR if the SA user doesn't exist;
  # distinguish by looking the user up.
  if $KCADM get users -r easyshop -q username=service-account-easyshop-review-service 2>/dev/null | jq -e '.[0].id' >/dev/null; then
    echo "  SERVICE role already assigned (or re-assign is a no-op) - fine"
  else
    echo "  WARN: service account user not found - is serviceAccountsEnabled"
    echo "        true on easyshop-review-service in the realm JSON?"
  fi
fi

echo ""
echo "Done. Now run: ./verify-keycloak.sh"