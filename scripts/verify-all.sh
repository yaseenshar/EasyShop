#!/usr/bin/env bash
#
# verify-all.sh
#
# Runs every verify-*.sh script in this repo back to back and prints a
# combined summary. Each script keeps its own PASS/FAIL semantics and exit
# code (0 = all assertions passed, 2 = aborted early on a missing
# prerequisite such as a token or .env value, anything else = failures
# present) - this wrapper does not reinterpret that, it just aggregates it.
#
# PREREQUISITE: same as the individual scripts - the full local stack up
# (infra/docker-compose.yml + all services), since several of these hit
# Keycloak, the gateway, and real service endpoints.
#
# Usage: ./scripts/verify-all.sh
# Full output of every script is streamed live AND saved to a log per
# script; the log directory is printed in the summary at the end.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/easyshop-verify-all.XXXXXX")"

SCRIPTS=(
  "integration-verification/verify-e2e.sh"
  "keycloak-setup/verify-keycloak.sh"
  "rbac/verify-rbac.sh"
  "redis-idempotent/verify-idempotency.sh"
  "redis-idempotent/verify-payment-order-idempotency.sh"
  "resilience4j-core/verify-circuit-breakers.sh"
  "resource-server-hardening/verify-resource-server.sh"
  "resource-server-hardening/verify-token-relay.sh"
  "retry-bulkhead/verify-retry-bulkhead.sh"
)

GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'

NAMES=(); STATUSES=(); CODES=(); RESULT_LINES=(); LOGFILES=()
OVERALL_FAIL=0

for rel in "${SCRIPTS[@]}"; do
  path="$SCRIPT_DIR/$rel"
  log="$LOG_DIR/$(echo "$rel" | tr '/' '__').log"

  echo
  echo "################################################################################"
  echo "# $rel"
  echo "################################################################################"

  if [ ! -f "$path" ]; then
    echo "  SKIP  script not found: $path"
    NAMES+=("$rel"); STATUSES+=("MISSING"); CODES+=("-"); RESULT_LINES+=("script not found"); LOGFILES+=("-")
    OVERALL_FAIL=1
    continue
  fi

  bash "$path" 2>&1 | tee "$log"
  code="${PIPESTATUS[0]}"

  # Every script ends with a "<n> passed, <n> failed[, <n> skipped/warnings]"
  # line (ANSI colors stripped first, since verify-circuit-breakers.sh colors
  # its PASS/FAIL lines) - pull the last one as the human-readable result.
  result_line="$(sed -E 's/\x1b\[[0-9;]*m//g' "$log" | grep -iE 'passed.*failed' | tail -1)"
  [ -n "$result_line" ] || result_line="(no summary line found - see log)"

  if [ "$code" -eq 0 ]; then
    status="PASS"
  elif [ "$code" -eq 2 ]; then
    status="ABORT"
    OVERALL_FAIL=1
  else
    status="FAIL"
    OVERALL_FAIL=1
  fi

  NAMES+=("$rel"); STATUSES+=("$status"); CODES+=("$code"); RESULT_LINES+=("$result_line"); LOGFILES+=("$log")
done

echo
echo "################################################################################"
echo "# SUMMARY"
echo "################################################################################"

for i in "${!NAMES[@]}"; do
  case "${STATUSES[$i]}" in
    PASS)  color="$GREEN" ;;
    ABORT) color="$YELLOW" ;;
    *)     color="$RED" ;;
  esac
  printf '%b%-6s%b %-58s (exit %-3s) %s\n' \
    "$color" "${STATUSES[$i]}" "$RESET" "${NAMES[$i]}" "${CODES[$i]}" "${RESULT_LINES[$i]}"
done

TOTAL=${#NAMES[@]}
PASS_COUNT=0
for s in "${STATUSES[@]}"; do [ "$s" = "PASS" ] && PASS_COUNT=$((PASS_COUNT+1)); done

echo
echo "$PASS_COUNT/$TOTAL scripts passed."
echo "Full logs: $LOG_DIR"

exit "$OVERALL_FAIL"
