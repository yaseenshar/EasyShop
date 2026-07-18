# INSTALL — Gateway Token Relay

Ordered so each step is verified before the next one changes anything — one variable
at a time.

**Recommended prerequisite:** land the `GlobalExceptionHandler` (handoff §5.2) first
and re-run both verification suites. You want a green baseline before adding a new
authentication mode, or you will be diagnosing 500s and 401s simultaneously.

---

## Step 1 — Pin the Keycloak issuer, then prove nothing broke

Merge `keycloak-hostname-snippet.yml` into the Keycloak service in
`docker-compose.yml`, then:

```bash
docker compose up -d keycloak
# wait for healthy (management port 9000)
./keycloak-setup/verify-keycloak.sh     # MUST remain 14/14
```

If the 14/14 regresses, stop here — the hostname change is the only variable.
(The snippet is flagged UNVERIFIED in README §6; `verify-token-relay.sh` T5 will
prove the issuer behaviour empirically in Step 7.)

## Step 2 — Provision the confidential BFF client

```bash
export GATEWAY_BFF_CLIENT_SECRET='<generate one>'
# Align with the scope name created by resource-server-hardening/provision-audience.sh:
export AUDIENCE_SCOPE='<your audience scope name>'
./gateway-token-relay/provision-gateway-bff-client.sh
```

Idempotent — safe to re-run. Verifies its own work (client flags, redirect URI,
scope attachment).

## Step 3 — Add the dependency to `api-gateway/pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

Keep the existing `spring-boot-starter-oauth2-resource-server` — both coexist.

## Step 4 — Merge the yml

Merge `application-token-relay-snippet.yml` into the api-gateway configuration:

- The `spring.security.oauth2.client.*` block is **new**.
- The existing `spring.security.oauth2.resourceserver.*` block is **unchanged** —
  keep the §6.5 `issuer-uri` + `jwk-set-uri` anchor exactly as-is.
- `default-filters` gains `TokenRelay=` and `RemoveRequestHeader=Cookie`; the five
  curated routes are untouched.
- If the existing `RequestRateLimiter` config references the key-resolver bean by
  name (`"#{@…}"`), keep the bean name consistent with `RateLimiterConfig.java`
  (`principalKeyResolver`) or adjust one side.

Pass the secret into the gateway container (compose):

```yaml
  api-gateway:
    environment:
      GATEWAY_BFF_CLIENT_SECRET: ${GATEWAY_BFF_CLIENT_SECRET}
```

## Step 5 — Replace the security config and key resolver

Drop in `GatewaySecurityConfig.java` and `RateLimiterConfig.java` (adjust the package
declaration to the gateway's actual package, e.g. `com.easyshop.gateway.config`, and
keep the import lines your existing `RateLimiterConfig` already compiles with).

## Step 6 — Rebuild

```bash
mvn -pl api-gateway clean install
docker compose up -d --build api-gateway   # --build is required — a plain restart
                                           # reuses the old jar (§5.2 lesson)
```

Startup check: if the gateway fails with
*"Unable to find GatewayFilterFactory with name TokenRelay"*, the client starter or
the `spring.security.oauth2.client.*` properties are missing — the filter bean is
conditional on them (README §2).

## Step 7 — Verify

```bash
export TEST_PASS='<demo.customer password>'
# optional, enables T3 bearer regression + T5 issuer assertion:
export DEV_CLIENT_ID='<dev gateway client id>' DEV_CLIENT_SECRET='<if confidential>'
export M2M_CLIENT_ID='<m2m client>' M2M_CLIENT_SECRET='<secret>'
./gateway-token-relay/verify-token-relay.sh
```

## Step 8 — Full regression

```bash
./integration-verification/verify-e2e.sh
./resource-server-hardening/verify-resource-server.sh
```

Relay must not regress passthrough. Then update the handoff: §9 additions from
README §6, and CSRF-disabled + dev-client-retirement notes into §7.