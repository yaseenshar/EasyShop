# Gateway Token Relay (BFF)

**Ticket:** Configure Spring Cloud Gateway to handle token relay.
**Status:** Ready to install — see `INSTALL.md`.
**Verified against:** Spring Cloud Gateway 5.0.2 reference documentation (the version
shipped by Spring Cloud 2025.1.x), 18 July 2026.

---

## 1. Decision

**Chosen:** hybrid gateway — OAuth2 **client** (`oauth2Login()` + `TokenRelay`, for
browsers) *and* OAuth2 **resource server** (bearer passthrough, unchanged, for API
clients, verification scripts, and machine callers).

In client mode the gateway runs the Authorization Code flow against Keycloak, holds
the tokens **server-side in the WebSession**, and gives the browser only a `SESSION`
cookie. The `TokenRelay` filter extracts the logged-in user's access token and attaches
it as `Authorization: Bearer` on downstream requests. This is the
**Backend-for-Frontend (BFF)** pattern.

**Rejected:**

- *Passthrough-only* ("relay" = just forwarding the incoming `Authorization` header).
  Already works today — Gateway strips only hop-by-hop headers, never `Authorization` —
  and it leaves access tokens in browser storage. Current OAuth guidance for
  browser-based apps favours BFF precisely to keep tokens out of the browser.
- *Client-only* (dropping resource-server support). Would break every bearer-token
  consumer: `verify-keycloak.sh`, `verify-resource-server.sh`, `../integration-verification/verify-e2e.sh`, and
  future M2M callers.

**Interview note:** BFF and SPA-with-PKCE are *competing* answers to the same problem
(where do tokens live for a browser app). After this ticket both are wired — the SPA
client stays for PKCE practice — and either side of the argument can be made from
hands-on experience. The §8.6 Angular plan can now choose: PKCE in the SPA, or a
plain cookie-authenticated SPA behind the BFF with no token handling at all.

---

## 2. Verified mechanics (Gateway 5.0.2)

- Config lives under the Gateway 5 prefix:
  `spring.cloud.gateway.server.webflux.…filters: - TokenRelay=` — consistent with the
  §2.1 prefix correction.
- `TokenRelay` takes one **optional** parameter, `clientRegistrationId`. Omitted → the
  currently authenticated user's own token (this ticket). Supplied → a token for any
  registered client (relevant to the §8.2 M2M item later, *not* used here).
- Requires `org.springframework.boot:spring-boot-starter-oauth2-client`.
- **The `TokenRelayGatewayFilterFactory` bean is conditional**: it is only created when
  `spring.security.oauth2.client.*` properties exist (they trigger creation of a
  `ReactiveClientRegistrationRepository`).
- The default `ReactiveOAuth2AuthorizedClientService` behind the filter is
  **in-memory**. Fine for one instance; Spring Session Redis is the scale-out /
  survive-restart answer (Redis is already at the gateway for rate limiting).

### Diagnostic signature (add to §6.3)

Adding `TokenRelay=` to a route **without** the client starter + properties fails at
startup with *"Unable to find GatewayFilterFactory with name TokenRelay"*. The filter's
existence is conditional on configuration, not guaranteed by the dependency.

---

## 3. The trap specific to this environment: which hostname mints the token

Every token in the system today is minted through `localhost:8090`, so `iss` is stable.
The BFF code exchange happens **container-to-container** at `keycloak:8080` — and
Keycloak in dev mode derives the issuer from the request URL. Without a fix, relayed
access tokens come out with `iss=http://keycloak:8080/realms/easyshop`, and every
downstream resource server — correctly validating against `localhost:8090` — 401s
them. This is §6.5 inverted: not *validation* reading the wrong URL, but *minting*
through the wrong one.

**Fix (primary):** pin Keycloak's frontend hostname so `iss` is constant regardless of
which interface served the token request — `keycloak-hostname-snippet.yml`
(`KC_HOSTNAME` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`). **Flagged unverified — see §6.**
**Fallback:** single-hostname trick — map `keycloak` → `127.0.0.1` in the host's
`/etc/hosts` and use `http://keycloak:8080` everywhere. Clean, but changes the
fleet-wide URL convention and adds a host-machine dependency.

Either way, `verify-token-relay.sh` **T5 asserts the outcome empirically**: it mints a
token from *inside* the Docker network and asserts the decoded `iss` claim equals the
public issuer. Empirical assertion beats trusting either documentation.

### Related: why the client provider config has no `issuer-uri`

For the **client** registration (unlike the resource-server side), setting
`provider.keycloak.issuer-uri` makes Boot perform OIDC discovery against it **at
startup, from inside the container** — where `localhost:8090` does not resolve. The
gateway would crash-loop. Endpoints are therefore configured manually, split by caller:

| Endpoint | URL | Who calls it |
|---|---|---|
| `authorization-uri` | `http://localhost:8090/…/auth` | the **browser** (redirect) |
| `token-uri`, `jwk-set-uri`, `user-info-uri` | `http://keycloak:8080/…` | the **gateway container** |

The existing resource-server `issuer-uri` + `jwk-set-uri` anchor (§6.5) is untouched.

---

## 4. Other integration points

**RateLimiterConfig.** Session-authenticated requests carry an
`OAuth2AuthenticationToken`, not a `JwtAuthenticationToken`. The `KeyResolver` now
resolves generically via `exchange.getPrincipal().map(Principal::getName)`. Subtle
bucket-split bug avoided: bearer principals name themselves by `sub`, login principals
by `user-name-attribute` — the yml pins `user-name-attribute: sub` so the same human
rate-limits into **one** bucket on both paths.

**Cookie hygiene.** `RemoveRequestHeader=Cookie` is a default filter: the gateway
session cookie is a credential for the gateway alone; forwarding it downstream would
violate least privilege. T7 in the verify script proves the cookie is worthless
outside the gateway — which is the entire point of BFF.

**Entry-point negotiation.** With `oauth2Login` + `oauth2ResourceServer` in one chain,
Spring Security content-negotiates: `Accept: text/html` → 302 to Keycloak; API
clients → 401 with `WWW-Authenticate`. Existing curl-based negative tests keep
getting 401s. Asserted by T1/T2.

**CSRF.** Cookie sessions make the gateway CSRF-relevant; the resource-server-only
gateway was not. CSRF is **disabled, dev-only** — add to the §7
"reverse before production" list (production answer: cookie-based CSRF token
repository consumed by the SPA).

**Dev client retirement path.** The new confidential `easyshop-gateway-bff` client is
the path to finally deleting `directAccessGrantsEnabled` from the dev gateway client
(§7 list) once the verification scripts that mint via password grant are the only
remaining consumers.

---

## 5. Files

```
README.md                          # this decision record
INSTALL.md                         # ordered installation steps
provision-gateway-bff-client.sh    # idempotent kcadm client provisioning (§4.17 rule:
                                   #   structure in realm JSON, everything else scripted)
keycloak-hostname-snippet.yml      # issuer pinning — UNVERIFIED, see §6
application-token-relay-snippet.yml# yml additions for api-gateway
GatewaySecurityConfig.java         # hybrid SecurityWebFilterChain
RateLimiterConfig.java             # principal-generic KeyResolver
verify-token-relay.sh              # adversarial verification suite (T1–T7)
```

---

## 6. Items requiring verification (add to handoff §9)

| Item | Concern | Fallback / empirical check |
|---|---|---|
| `KC_HOSTNAME` (full URL) + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` on Keycloak 26 | Hostname-v2 option semantics taken from memory of KC 25/26 direction, not verified against the KC 26 docs | T5 asserts `iss` empirically; fallback is the `/etc/hosts` single-hostname approach |
| TokenRelay refresh-on-expiry in Gateway 5.0.x | Refresh behaviour has shifted across Gateway versions | Opt-in probe: `CHECK_REFRESH=1` (lower realm access-token lifespan to 60 s first) |
| ID-token `iss` validation with manually configured provider endpoints | With no `issuer-uri` on the client provider, ID-token issuer validation is likely skipped | Acceptable in dev; verify against Spring Security 7 docs before production |
| `OAuth2ClientProperties` names on Boot 4.1 | Long-stable (`authorization-uri`, `token-uri`, …) but not re-verified against Boot 4.1 reference | Startup fails fast on unknown/moved properties |

---

## 7. Deferred (deliberate, with triggers)

- **RP-initiated logout** — `OidcClientInitiatedServerLogoutSuccessHandler` so gateway
  logout also ends the Keycloak SSO session. Trigger: the Angular frontend lands.
- **Spring Session Redis** for the WebSession + authorized-client store. Trigger:
  second gateway instance, or login-survives-restart becomes annoying.
- **CSRF re-enable strategy** for production (see §4).
- **`TokenRelay=<registrationId>` variant** for machine-context relay — evaluate
  against the §8.2 interceptor approach when doing M2M token propagation.