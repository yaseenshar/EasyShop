#!/usr/bin/env bash
#
# decode-token.sh — STEP ZERO of the RBAC ticket (working practice #1: verify
# before writing code). Mints a token for a persona and prints the claims the
# converter and the rules depend on. Run once per persona BEFORE installing
# anything, and record:
#
#   1. WHERE the roles live: flat "roles" claim vs realm_access.roles
#      (KeycloakRolesConverter handles both, flat first — but you should KNOW).
#   2. THE EXACT CASE of the role values: hasRole("ADMIN") requires the token
#      to literally contain ADMIN. The converter deliberately does not
#      normalize case; fix mismatches in Keycloak, not in code.
#   3. sub / iss / aud sanity — the same claims your resource servers validate.
#
# Usage:
#   ROPC_CLIENT_ID=<dev-client> [ROPC_CLIENT_SECRET=...] \
#     ./decode-token.sh demo.customer <password>
#
# Uses the dev ROPC client (directAccessGrantsEnabled — dev-only, §7).

set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8090}"
REALM="${REALM:-easyshop}"
ROPC_CLIENT_ID="${ROPC_CLIENT_ID:?set to the dev client with directAccessGrantsEnabled}"
ROPC_CLIENT_SECRET="${ROPC_CLIENT_SECRET:-}"

USERNAME="${1:?usage: decode-token.sh <username> <password>}"
PASSWORD="${2:?usage: decode-token.sh <username> <password>}"

TOKEN_JSON=$(curl -s \
  -d grant_type=password \
  -d client_id="$ROPC_CLIENT_ID" \
  ${ROPC_CLIENT_SECRET:+-d client_secret="$ROPC_CLIENT_SECRET"} \
  --data-urlencode "username=$USERNAME" \
  --data-urlencode "password=$PASSWORD" \
  "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token")

python3 - "$TOKEN_JSON" <<'PY'
import base64
import json
import sys

token_response = json.loads(sys.argv[1])
access_token = token_response.get("access_token")

if not access_token:
    # §6.3: read the detailed channel, not the status code.
    print("Token request failed — error_description below:", file=sys.stderr)
    print(json.dumps(token_response, indent=2), file=sys.stderr)
    sys.exit(1)

payload = access_token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))

print("sub:             ", claims.get("sub"))
print("iss:             ", claims.get("iss"))
print("aud:             ", claims.get("aud"))
print("preferred_username:", claims.get("preferred_username"))
print()
print("roles (flat):    ", claims.get("roles"))
print("realm_access:    ", claims.get("realm_access"))
print("resource_access: ", json.dumps(claims.get("resource_access", {}), indent=2))
PY