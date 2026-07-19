#!/usr/bin/env bash
# verify-token-relay.sh
#
# Adversarial verification of the gateway BFF token relay (T1-T7).
# House rules applied:
#   - curl exit code 000 means NO CONNECTION, never a rejection (handoff §6.3)
#   - negative paths are asserted, not assumed
#   - the empirical answer beats the documented one (T5 vs the unverified
#     KC_HOSTNAME semantics)
#
# All env below is OPTIONAL - the script runs standalone with no exports
# needed, using this repo's known local-dev realm seed (docker/keycloak/realms/
# easyshop-realm.json ships demo.customer's password in plaintext - it is not
# a real secret) and the existing easyshop-gateway client (direct-grants +
# service-account already enabled, secret pulled from .env). Override any of
# these to point at a different realm/client:
#   GATEWAY=http://localhost:8080
#   KC_PUBLIC=http://localhost:8090
#   REALM=easyshop
#   TEST_USER=demo.customer
#   TEST_PASS            password for $TEST_USER (default: the seeded demo.customer password)
#   PROTECTED=/api/v1/users/me
#   DEV_CLIENT_ID / DEV_CLIENT_SECRET   T3 bearer regression via ROPC (default: easyshop-gateway)
#   M2M_CLIENT_ID / M2M_CLIENT_SECRET   T5 in-network issuer assertion (default: easyshop-gateway)
#   COMPOSE_NETWORK      docker network for T5 (default: auto-detected from
#                        the running keycloak container's own networks)
#   CHECK_REFRESH=1                     enables T6 (lower the realm access-token
#                                       lifespan to 60s first); REFRESH_WAIT=70

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FOUND=""
for E in "$SCRIPT_DIR/.env" "$SCRIPT_DIR/../.env" "$SCRIPT_DIR/../../.env" "./.env"; do
  if [ -f "$E" ]; then set -a; source "$E"; set +a; ENV_FOUND="$E"; break; fi
done
echo "[diag] script: ${BASH_SOURCE[0]:-<none, not running under bash>} | .env sourced from: ${ENV_FOUND:-<NOT FOUND>} | KEYCLOAK_GATEWAY_CLIENT_SECRET is $( [ -n "${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}" ] && echo 'set' || echo 'EMPTY' )" >&2

# Guard against copy-pasting INSTALL.md's example line verbatim (angle
# brackets and all) into a shell that then stays open - a value like
# '<dev gateway client id>' silently poisons every default below since it
# reads as "already set" to bash. Discard anything shaped like a placeholder
# and fall through to the real default instead of failing mysteriously.
for _V in TEST_PASS DEV_CLIENT_ID DEV_CLIENT_SECRET M2M_CLIENT_ID M2M_CLIENT_SECRET COMPOSE_NETWORK; do
  case "${!_V:-}" in
    \<*\>)
      echo "[diag] WARNING: \$$_V is set to a literal placeholder ('${!_V}') - probably pasted from INSTALL.md. Unsetting it so the real default applies." >&2
      unset "$_V"
      ;;
  esac
done

GATEWAY="${GATEWAY:-http://localhost:8080}"
KC_PUBLIC="${KC_PUBLIC:-http://localhost:8090}"
REALM="${REALM:-easyshop}"
TEST_USER="${TEST_USER:-demo.customer}"
TEST_PASS="${TEST_PASS:-Customer#Pass1}"
PROTECTED="${PROTECTED:-/api/v1/users/me}"
DEV_CLIENT_ID="${DEV_CLIENT_ID:-easyshop-gateway}"
DEV_CLIENT_SECRET="${DEV_CLIENT_SECRET:-${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}}"
M2M_CLIENT_ID="${M2M_CLIENT_ID:-easyshop-gateway}"
M2M_CLIENT_SECRET="${M2M_CLIENT_SECRET:-${KEYCLOAK_GATEWAY_CLIENT_SECRET:-}}"
if [ -z "${COMPOSE_NETWORK:-}" ] && command -v docker >/dev/null; then
  COMPOSE_NETWORK=$(docker network ls --format '{{.Name}}' | grep -m1 'easyshop-network' || true)
fi
COMPOSE_NETWORK="${COMPOSE_NETWORK:-easyshop_default}"
EXPECTED_ISS="$KC_PUBLIC/realms/$REALM"

PASS=0; FAIL=0; WARN=0
ok()   { echo "  PASS  $1"; PASS=$((PASS+1)); }
ko()   { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
warn() { echo "  WARN  $1"; WARN=$((WARN+1)); }

# 000-aware status check: distinguishes "no connection" from a real response.
http_code() { curl -s -o "${2:-/dev/null}" -w '%{http_code}' "${@:3}" "$1"; }

json_field() { # $1=json  $2=field   (no jq dependency)
  printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -n1 \
    | sed "s/\"$2\":\"//;s/\"$//"
}

jwt_payload() { # $1 = jwt -> decoded payload JSON
  local p; p=$(printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+')
  while [ $(( ${#p} % 4 )) -ne 0 ]; do p="${p}="; done
  printf '%s' "$p" | base64 -d 2>/dev/null
}

JAR="$(mktemp)"; BODY="$(mktemp)"
cleanup() { rm -f "$JAR" "$BODY"; }
trap cleanup EXIT

echo "== T1: unauthenticated API request -> 401, not a login redirect =="
CODE=$(http_code "$GATEWAY$PROTECTED" /dev/null -H 'Accept: application/json')
if   [ "$CODE" = "000" ]; then ko "no connection established (000) — is the gateway up? (000 is not a rejection)"
elif [ "$CODE" = "401" ]; then ok "API client gets 401 (entry-point negotiation working)"
else ko "expected 401, got $CODE"
fi

echo "== T2: unauthenticated browser request -> redirected to Keycloak auth endpoint =="
FINAL_URL=$(curl -s -o /dev/null -w '%{url_effective}' -L --max-redirs 4 \
  -c "$JAR" -b "$JAR" -H 'Accept: text/html' "$GATEWAY$PROTECTED")
case "$FINAL_URL" in
  "$KC_PUBLIC/realms/$REALM/protocol/openid-connect/auth"*)
    ok "browser lands on the Keycloak login page ($KC_PUBLIC, the BROWSER-facing URL)" ;;
  *) ko "redirect chain ended at: $FINAL_URL" ;;
esac

echo "== T3: bearer passthrough regression (relay must not break the old path) =="
echo "[diag] DEV_CLIENT_ID=$DEV_CLIENT_ID | DEV_CLIENT_SECRET is $( [ -n "${DEV_CLIENT_SECRET:-}" ] && echo "set (${#DEV_CLIENT_SECRET} chars)" || echo 'EMPTY' )" >&2
if [ -n "${DEV_CLIENT_ID:-}" ]; then
  TOK_JSON=$(curl -s "$KC_PUBLIC/realms/$REALM/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$DEV_CLIENT_ID" \
    ${DEV_CLIENT_SECRET:+-d client_secret="$DEV_CLIENT_SECRET"} \
    -d username="$TEST_USER" -d password="$TEST_PASS")
  BEARER=$(json_field "$TOK_JSON" access_token)
  if [ -z "$BEARER" ]; then
    ko "could not mint a bearer token: $(json_field "$TOK_JSON" error_description)"
  else
    CODE=$(http_code "$GATEWAY$PROTECTED" /dev/null -H "Authorization: Bearer $BEARER")
    [ "$CODE" = "200" ] && ok "bearer request through the gateway -> 200" \
                        || ko "bearer request expected 200, got $CODE"
  fi
else
  warn "T3 skipped (set DEV_CLIENT_ID) — run scripts/integration-verification/verify-e2e.sh for the regression instead"
fi

echo "== T4: full browser login flow -> session cookie -> TokenRelay -> 200 downstream =="
# The cookie jar already holds the gateway auth-request state + Keycloak
# session cookies from T2's redirect chain. Extract the login form action
# (HTML-entity-decode &amp;) and submit credentials.
LOGIN_PAGE=$(curl -s -L --max-redirs 4 -c "$JAR" -b "$JAR" \
  -H 'Accept: text/html' "$GATEWAY$PROTECTED")
ACTION=$(printf '%s' "$LOGIN_PAGE" | grep -o 'action="[^"]*"' | head -n1 \
  | sed 's/^action="//;s/"$//;s/\&amp;/\&/g')

if [ -z "$ACTION" ]; then
  ko "could not extract the Keycloak login form action from the login page"
else
  # curl converts POST->GET when following the 302s: Keycloak 302s to the
  # gateway callback (code exchange happens, SESSION issued), the gateway 302s
  # to the originally requested URL — which the request cache saved as
  # $PROTECTED — and TokenRelay fires on that final hop.
  # A WRONG password does NOT get a non-200: Keycloak re-renders its own login
  # form with an inline error, which is still HTTP 200. http_code alone can't
  # tell a real success (landed back on $GATEWAY$PROTECTED) from a rejected
  # login (still sitting on $KC_PUBLIC). Check url_effective too.
  RESP=$(curl -s -o "$BODY" -w '%{http_code}|%{url_effective}' -L --max-redirs 6 \
    -c "$JAR" -b "$JAR" \
    --data-urlencode "username=$TEST_USER" \
    --data-urlencode "password=$TEST_PASS" \
    "$ACTION")
  FINAL_CODE="${RESP%%|*}"
  FINAL_URL="${RESP##*|}"
  if [ "$FINAL_CODE" = "200" ] && [ "$FINAL_URL" = "$GATEWAY$PROTECTED" ]; then
    ok "login flow completed and original request replayed -> 200"
  elif [ "$FINAL_CODE" = "200" ]; then
    ko "login flow returned 200 but never left Keycloak (still at $FINAL_URL) — credentials were REJECTED; check TEST_PASS"
  else
    ko "login flow ended with $FINAL_CODE at $FINAL_URL (body in: run diagnose-401.sh if 401 downstream)"
  fi

  # The core proof, isolated: a FRESH request carrying ONLY the session cookie.
  # A 200 here proves the whole chain at once: TokenRelay fired, a bearer token
  # was attached, and downstream accepted its iss, aud and sub.
  CODE=$(http_code "$GATEWAY$PROTECTED" /dev/null -b "$JAR" -H 'Accept: application/json')
  [ "$CODE" = "200" ] \
    && ok "session-only request -> 200 (relay proven end-to-end: iss/aud/sub all accepted)" \
    || ko "session-only request expected 200, got $CODE — if 401, the downstream rejected the RELAYED token; check iss first (T5)"
fi

echo "== T5: issuer of tokens minted INSIDE the docker network (the §6.5-inverted trap) =="
if [ -n "${M2M_CLIENT_ID:-}" ] && command -v docker >/dev/null; then
  TOK_JSON=$(docker run --rm --network "$COMPOSE_NETWORK" curlimages/curl:8.7.1 -s \
    "http://keycloak:8080/realms/$REALM/protocol/openid-connect/token" \
    -d grant_type=client_credentials \
    -d client_id="$M2M_CLIENT_ID" -d client_secret="${M2M_CLIENT_SECRET:?}" 2>/dev/null)
  ACCESS=$(json_field "$TOK_JSON" access_token)
  if [ -z "$ACCESS" ]; then
    warn "T5: could not mint in-network token (network '$COMPOSE_NETWORK'? creds?) — inconclusive"
  else
    ISS=$(json_field "$(jwt_payload "$ACCESS")" iss)
    if [ "$ISS" = "$EXPECTED_ISS" ]; then
      ok "in-network minting yields iss=$ISS — KC_HOSTNAME pinning empirically confirmed"
    else
      ko "in-network minting yields iss=$ISS (expected $EXPECTED_ISS) — the hostname pin is NOT working; every relayed token will 401 downstream"
    fi
  fi
else
  warn "T5 skipped (set M2M_CLIENT_ID/M2M_CLIENT_SECRET; docker required). T4's 200 implies iss was accepted, but this test names the failure directly when T4 breaks."
fi

echo "== T6: token refresh probe (opt-in; unverified behaviour — README §6) =="
if [ "${CHECK_REFRESH:-0}" = "1" ]; then
  SLEEP="${REFRESH_WAIT:-70}"
  echo "  sleeping ${SLEEP}s (realm access-token lifespan must be lowered to 60s first)"
  sleep "$SLEEP"
  CODE=$(http_code "$GATEWAY$PROTECTED" /dev/null -b "$JAR" -H 'Accept: application/json')
  if   [ "$CODE" = "200" ]; then ok "post-expiry session request -> 200: TokenRelay refreshes in this Gateway version"
  elif [ "$CODE" = "401" ]; then warn "post-expiry -> 401: TokenRelay does NOT refresh here — record in §9 and plan a refresh strategy"
  else warn "post-expiry -> $CODE (inconclusive)"
  fi
else
  echo "  skipped (set CHECK_REFRESH=1 after lowering the access-token lifespan)"
fi

echo "== T7: the session cookie must be WORTHLESS outside the gateway (the point of BFF) =="
CODE=$(http_code "http://localhost:8081$PROTECTED" /dev/null -b "$JAR" -H 'Accept: application/json')
if   [ "$CODE" = "000" ]; then
  ok "user-service not directly reachable from host — topology enforces isolation (000 = no connection, not a rejection)"
elif [ "$CODE" = "401" ]; then
  ok "resource server ignores the session cookie -> 401 (cookie is gateway-scoped, as designed)"
else
  ko "direct call with session cookie returned $CODE — a resource server honoured a cookie it should not understand"
fi

echo
echo "Result: $PASS passed, $FAIL failed, $WARN warnings"
[ "$FAIL" -eq 0 ] || exit 1