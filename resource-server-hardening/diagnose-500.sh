#!/usr/bin/env bash
# Isolates WHERE the 500 comes from and prints the actual stack trace.
for E in "$(dirname "$0")/.env" "./.env"; do [ -f "$E" ] && { set -a; source "$E"; set +a; break; }; done

TOKEN=$(curl -s -X POST http://localhost:8090/realms/easyshop/protocol/openid-connect/token \
  -d client_id=easyshop-gateway -d "client_secret=$KEYCLOAK_GATEWAY_CLIENT_SECRET" \
  -d grant_type=password -d username=demo.customer -d 'password=Customer#Pass1' \
  | jq -r .access_token)

echo "=== 1. Gateway vs direct: where does the 500 originate? ================"
echo "--- via gateway :8080 ---"
curl -s -o /dev/null -w '  status: %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/users/me
echo "--- direct to user-service :8081 ---"
curl -s -w '\n  status: %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/v1/users/me
echo "  (same status both ways => user-service is the source;"
echo "   only gateway 500s => a gateway filter is throwing)"

echo ""
echo "=== 2. THE STACK TRACE (this is the answer) ==========================="
docker compose logs --tail=200 user-service 2>/dev/null \
  | grep -iE "exception|error|caused by|\tat com\.easyshop" | tail -30
echo "  ^ look for the exception TYPE on the first line"

echo ""
echo "=== 3. Is GlobalExceptionHandler actually loaded? ======================"
if docker compose logs user-service 2>/dev/null | grep -qi "GlobalExceptionHandler\|CommonAutoConfiguration"; then
  echo "  mentioned in logs - likely loaded"
else
  echo "  NOT mentioned. Verify the install:"
  echo "    a) file at common-lib/src/main/resources/META-INF/spring/"
  echo "       org.springframework.boot.autoconfigure.AutoConfiguration.imports"
  echo "       (exact filename - anything else is silently ignored)"
  echo "    b) mvn -pl common/common-lib install && mvn clean install"
  echo "    c) docker compose up -d --build user-service   <-- REBUILD, not restart"
  echo "       (a plain restart reuses the old jar with the old common-lib)"
fi

echo ""
echo "=== 4. Is the demo user actually registered? =========================="
SUB=$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' \
  | awk '{l=length($0)%4; if(l==2)$0=$0"=="; else if(l==3)$0=$0"="; print}' \
  | base64 -d 2>/dev/null | jq -r .sub)
echo "  token sub (= keycloak_id user-service looks up): $SUB"
docker compose exec -T postgres psql -U easyshop -d easyshop_user \
  -c "SELECT keycloak_id, email FROM users;" 2>/dev/null \
  || echo "  (could not query - check DB name easyshop_user exists)"
echo "  If the table is empty, /me legitimately has nothing to return:"
echo "  with the handler installed that is a clean 404, without it a 500."
