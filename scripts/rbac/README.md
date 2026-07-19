# rbac/ — CUSTOMER / VENDOR / ADMIN / SERVICE role enforcement

**Ticket:** Implement CUSTOMER, ADMIN, and VENDOR role checks across service endpoints.
**Scope note:** Keycloak already carries the roles (verify-keycloak.sh asserts the
`roles` claims for all three personas plus the M2M `SERVICE` role). This bundle is the
Spring-side enforcement that was missing: today every service authorizes with
`.authenticated()`, so any valid token reaches any endpoint.

---

## 1. Decision record

**Enforcement lives in the services, not the gateway.** The gateway stays
authentication-only. Payment and inventory have no routes for gateway rules to cover;
ownership rules aren't expressible at route level; §4.16 already says never assume a
request arrived by the expected path. Post-BFF bonus reason: session-authenticated
`OidcUser` principals at the gateway don't carry realm roles as authorities by default —
enforcing on the relayed access token in the services uses one code path for browser
and API clients alike.

**Mechanism: shared converter in common-lib, rules local to each service.** Spring
Security does not map Keycloak roles to authorities out of the box (only `scope`→
`SCOPE_*`). The claim→authority mapping is identical for all six resource servers, so
it ships as a common-lib auto-configuration — the exact mechanism proven with
`GlobalExceptionHandler`. Endpoint rules differ per service and stay per-service. This
is the first, deliberately-limited step of the deferred §8.6 SecurityConfig
consolidation.

**Principle adopted fleet-wide:** `.authenticated()` is authentication, not
authorization. Every endpoint names its audience — which is what makes "a SERVICE
token must not act as a person" and "an ADMIN token is not a skeleton key for
`/internal/**`" testable assertions instead of hopes.

**Scope discipline:** RBAC now; ownership where the data supports it (orders, carts,
reviews all link to the Keycloak `sub`); **vendor-product ownership deferred** —
catalog has no `vendor_id`, and that's a schema migration, not a role check. Stated
consequence until then: VENDOR write access to products is fleet-wide, and DELETE
stays ADMIN-only.

---

## 2. Files

```
rbac/
  README.md                                   # this file
  common-lib/
    KeycloakRolesConverter.java               # roles claim -> ROLE_* authorities
    SecurityCommonAutoConfiguration.java      # servlet-only auto-config bean
    GlobalExceptionHandler-additions.java     # 403/401 handlers (NOT a class — merge)
  service-security-configs/
    SecurityConfig-user-service.java          # named audiences; by-id = admin surface
    SecurityConfig-catalog-service.java       # public browse (Option A) + vendor writes
    SecurityConfig-order-service.java         # /internal -> SERVICE; checkout -> CUSTOMER
    SecurityConfig-cart-service.java          # CUSTOMER-only; ownership is structural
    SecurityConfig-review-service.java        # customer writes, admin moderation
  OrderAccess.java                            # bean-based ownership for @PreAuthorize
  decode-token.sh                             # STEP ZERO — verify claim shape and case
  verify-rbac.sh                              # the adversarial matrix
```

---

## 3. Install order

1. **`./decode-token.sh <persona> <password>` for all three personas** (working
   practice #1). Record where roles live and their exact case. `hasRole("ADMIN")`
   requires the token to literally contain `ADMIN`; fix mismatches in Keycloak, not
   by normalizing in code.
2. **common-lib:** add the two classes under `com.easyshop.common.security`, append
   `com.easyshop.common.security.SecurityCommonAutoConfiguration` to the existing
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   (exact filename — silently ignored otherwise), and add to common-lib's pom:

   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
       <optional>true</optional>
   </dependency>
   ```
   Same `<optional>true</optional>` pattern as the web/validation starters from §5.2.
3. **Merge the two exception handlers** into `GlobalExceptionHandler` (see the §9 flag
   in that file about the `AccessDeniedException` package on Security 7).
4. **Per service:** merge the matching SecurityConfig, **aligning every URL pattern
   with the real controllers** — a pattern that matches nothing fails *open* into the
   broader rule below it, not closed. Add `@PreAuthorize` + `OrderAccess` where
   admin-or-owner applies; prefer query-scoped ownership
   (`findByIdAndCustomerKeycloakId(...)` → 404) for plain customer reads.
5. **If catalog stays public (Option A),** add to the gateway's reactive chain,
   *before* `anyExchange()`:

   ```java
   .pathMatchers(HttpMethod.GET,
       "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/reviews/**").permitAll()
   ```
6. **Sequencing decision, made consciously:** land review-service's M2M token
   propagation (§8.2 #3's other half) in the same change, or accept a documented
   window in which every new review lands UNVERIFIED — silently, because the §4.12
   fallback is doing its job. verify-rbac.sh section [9] prints the manual test for it.
7. Build the fleet — §5.2 lesson applies verbatim:

   ```
   cd backend && mvn -pl common/common-lib install && mvn clean install && cd ..
   docker compose -f infra/docker-compose.yml up -d --build
   ```
8. **Verify:** `verify-rbac.sh` (below), then re-run `verify-e2e.sh` and
   `verify-resource-server.sh` — RBAC must not regress authentication.

---

## 4. Verification

```bash
CUSTOMER_PASSWORD=... VENDOR_PASSWORD=... ADMIN_PASSWORD=... \
ROPC_CLIENT_ID=<dev-client> M2M_CLIENT_ID=<m2m-client> M2M_CLIENT_SECRET=... \
INTERNAL_PATH=/internal/orders/<id>/purchase-check \
OTHER_CUSTOMERS_ORDER_ID=<uuid-not-owned-by-demo.customer> \
./verify-rbac.sh
```

What it asserts, per endpoint: the permitted persona gets past security (a 400 on an
empty write body **counts** — it proves the request reached validation), every
forbidden persona gets **exactly 403** (not 401, not 500 — the exact-code discipline
is what catches the GlobalExceptionHandler trap automatically), and no-token gets
**exactly 401**. Plus the two adversarial extras: a SERVICE token against human
endpoints → 403, and an ADMIN token against `/internal/**` → 403.

Two sections skip until you feed them real data (`INTERNAL_PATH`,
`OTHER_CUSTOMERS_ORDER_ID`) — the script says so explicitly rather than pretending
coverage it doesn't have.

For CI, mirror the same matrix in `@WebMvcTest` slices with spring-security-test's
`jwt()` post-processor (`.jwt(jwt -> jwt.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))`)
— same assertions, no Keycloak required. That belongs with the §8.3 Testcontainers
work.

---

## 5. Traps (diagnostic signatures for §6.3)

| Symptom | Diagnosis |
|---|---|
| **403 on every endpoint for every persona** | Converter not applied, `ROLE_` prefix mismatch, wrong claim location, or role-case mismatch. `decode-token.sh`, then compare against the rules. |
| **500 where a 403 belongs** | The catch-all `@ExceptionHandler(Exception.class)` swallowed a method-security `AccessDeniedException`. Merge the handler additions. Handler *position* is irrelevant — Spring resolves by type specificity; existence is what matters. |
| **`#param` SpEL fails at runtime, compiles fine** | Missing `-parameters` compiler flag (Spring Framework 6.1+ removed the bytecode fallback). Boot's parent sets it; any module overriding `maven-compiler-plugin` loses it. Check every module that uses `@PreAuthorize` with parameter names. |
| **401 with a valid-looking token** | Authentication, not authorization — iss/aud/expiry. `diagnose-401.sh` territory, unchanged by this ticket. |
| **A rule "doesn't work"** | Pattern/controller mismatch — the request fell through to a broader rule. Patterns fail open, which is why `anyRequest().denyAll()` is the stated hardening step once patterns are confirmed complete. |
| **Anonymous catalog 401s despite permitAll** | The gateway chain still requires authentication — Option A needs the matching gateway `pathMatchers(...).permitAll()` (install step 5). |

---

## 6. §9 additions (flagged, not asserted)

| Item | Concern | Fallback |
|---|---|---|
| `org.springframework.security.access.AccessDeniedException` on Security 7 | Parts of that package relocated to the `spring-security-access` artifact; whether this class moved is unverified | If the import breaks: check current package; consider catching `AuthorizationDeniedException` (`org.springframework.security.authorization`, the 6.3+ subclass); handle whatever the stack trace names |
| Boot auto-detection of a `JwtAuthenticationConverter` bean | Boot may wire a context bean into the resource-server DSL automatically | Moot here — every service wires it explicitly, which is why the configs do |
| Role-value case in tokens | Rules assume `CUSTOMER`/`VENDOR`/`ADMIN`/`SERVICE` verbatim | `decode-token.sh` before install; align Keycloak, not code |

## 7. Deferred (with triggers)

- **`vendor_id` on products + vendor ownership checks** — trigger: any real vendor
  write path. Schema migration + backfill + `@PreAuthorize`/query-scoping; also
  unlocks vendor review-responses and per-vendor DELETE.
- **Full SecurityFilterChain consolidation into common-lib (§8.6)** — trigger: the
  per-service configs above drifting. The converter move is step one; the chain
  itself consolidates once a customizer interface for per-service rules is designed.
- **`anyRequest().denyAll()` hardening** — trigger: URL patterns confirmed complete
  against the controllers (the §4.15 posture, applied inside each service).
- **Gateway coarse role gates** — only if defense-in-depth at the edge becomes worth
  the `OidcUser` authorities-mapper complexity; requires a `GrantedAuthoritiesMapper`
  for the login path in addition to the JWT converter.