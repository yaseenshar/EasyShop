# JWT Validation — What Actually Happens, and the Hardened Config

## Ticket scope vs Phase 2 reality

Phase 2 already delivered the resource-server skeleton for user-service:
the oauth2-resource-server starter, the SecurityFilterChain with
`.oauth2ResourceServer(jwt)`, issuer-uri auto-discovery, and the
roles-claim converter. These tickets close by (a) understanding precisely
what that config validates, (b) closing the audience gap, and (c) proving
the whole thing adversarially — not by rewriting working code.

## What `issuer-uri` gives you automatically

| Check      | Mechanism                                                        |
|------------|------------------------------------------------------------------|
| Signature  | JWKS fetched from the issuer's discovery doc; keys cached; an unknown `kid` triggers a re-fetch — this is why Keycloak key rotation is seamless with zero service restarts |
| Expiry     | `exp` claim, with **60s default clock skew** tolerance           |
| Not-before | `nbf` claim, same skew                                           |
| Issuer     | `iss` must equal the configured issuer-uri exactly               |

## What it does NOT validate (the gaps)

1. **Audience (`aud`)** — the gap these tickets close. Without it, a token
   minted by the same realm for any other API/client is accepted here.
   Fix = one property (Boot 3.1+ auto-wires a JwtClaimValidator for aud):

       spring:
         security:
           oauth2:
             resourceserver:
               jwt:
                 issuer-uri: http://localhost:8090/realms/easyshop
                 audiences: [easyshop-api]

   ORDER OF OPERATIONS: run provision-audience.sh FIRST and confirm its
   final "SAFE to enable" line — the property rejects every token that
   lacks the claim, i.e. all tokens minted before the mapper existed.
   (Restart services after enabling; already-issued tokens without aud
   die at their natural 15-min expiry.)

2. **Revocation** — JWTs are self-contained; a stolen token stays valid
   until exp. Mitigations, in escalating cost: short lifetimes (we use
   15 min - this IS the primary mitigation), refresh-token rotation with
   reuse detection (enabled in our realm), and only if truly needed,
   token introspection or a denylist (which reintroduces a per-request
   IdP dependency — the thing JWTs exist to avoid; know the tradeoff).

3. **`alg` confusion** — historical JWT libraries accepted `"alg":"none"`
   (attacker strips the signature and the token "verifies"). Nimbus/
   Spring rejects this, but a security ticket isn't closed on trust:
   verify-resource-server.sh forges an alg=none token and asserts 401.

## Programmatic alternative (when the property isn't enough)

A custom JwtDecoder bean gives control over skew and arbitrary claim
rules. NOTE: defining this bean REPLACES the property-driven decoder —
choose one home for validation config, never both:

    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties props) {
        String issuer = props.getJwt().getIssuerUri();
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
            "aud", aud -> aud != null && aud.contains("easyshop-api"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audience));
        return decoder;
    }

Use the property. Reach for this only for custom skew (JwtTimestampValidator
with a Duration) or claim logic a simple equality can't express.

## Rollout note

The audiences property belongs in EVERY resource server's yml (order,
cart, review, catalog...), not just user-service — the ticket names
user-service; the pattern is fleet-wide. Natural refactor once applied
everywhere: the roles converter + shared security defaults are now
duplicated across five SecurityConfigs and are a candidate for a
common-lib auto-configuration — same judgment call as the outbox and
SagaMessages moves, and worth making deliberately rather than by copy-paste
drift.
