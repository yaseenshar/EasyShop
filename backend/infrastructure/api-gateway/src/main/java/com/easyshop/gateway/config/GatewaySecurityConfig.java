package com.easyshop.gateway.config; // adjust to the gateway's actual package

import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher;

import com.easyshop.gateway.session.RemoveAuthorizedClientLogoutHandler;

/**
 * Hybrid gateway security: OAuth2 client (BFF) + OAuth2 resource server.
 *
 * Replaces the resource-server-only chain from gateway-jwt-fix. Two entry modes
 * coexist in ONE chain:
 *
 *  - Browser (no token, Accept: text/html): oauth2Login() runs the Authorization
 *    Code flow against Keycloak. Tokens are held SERVER-SIDE in the WebSession;
 *    the browser gets only a SESSION cookie. The TokenRelay default filter
 *    (application.yml) attaches the user's access token downstream.
 *
 *  - API client (Authorization: Bearer ...): unchanged resource-server
 *    validation. verify-* scripts and machine callers are unaffected.
 *
 * Entry-point negotiation is NOT automatic when oauth2Login() and
 * oauth2ResourceServer() are combined on one chain — each registers its own
 * default entry point, and without an explicit delegating entry point one
 * simply wins for every request (empirically: login's redirect wins for
 * everything, including API clients sending Accept: application/json). The
 * explicit DelegatingServerAuthenticationEntryPoint below restores the
 * intended split: text/html -> redirect to login (302), everything else ->
 * 401 + WWW-Authenticate. Asserted by verify-token-relay.sh T1/T2.
 *
 * Defence in depth unchanged (handoff §4.16): the gateway rejects bad
 * credentials at the edge, and every downstream service independently
 * validates the relayed JWT again.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
            org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        var htmlMatcher = new MediaTypeServerWebExchangeMatcher(MediaType.TEXT_HTML);
        htmlMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));

        var entryPoint = new DelegatingServerAuthenticationEntryPoint(
                List.of(new DelegatingServerAuthenticationEntryPoint.DelegateEntry(
                        htmlMatcher, new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/keycloak")))
        );
        entryPoint.setDefaultEntryPoint(new BearerTokenServerAuthenticationEntryPoint());

        // RP-INITIATED (single) LOGOUT. Without this, the default logout
        // handler only ends the GATEWAY's own WebSession - Keycloak's own
        // KEYCLOAK_SESSION cookie stays alive, so the very next
        // /oauth2/authorization/keycloak navigation (clicking "Sign in" again,
        // or hitting a 401) silently re-authenticates against that still-live
        // SSO session with NO login form shown, logging the same user back in.
        // See KeycloakLogoutSuccessHandler's own javadoc for why this is a
        // hand-built handler and not Spring's OidcClientInitiatedServerLogout-
        // SuccessHandler.
        var logoutSuccessHandler = new KeycloakLogoutSuccessHandler();

        http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**").permitAll()
                        // Route introspection: dev-only, matches the explicit
                        // management.endpoint.gateway.access: unrestricted grant in
                        // application.yml (that setting only unlocks actuator's OWN
                        // access level - it does nothing without this permitAll too).
                        .pathMatchers("/actuator/gateway/**").permitAll()
                        // Circuit breaker introspection: dev-only, matches
                        // management.endpoints.web.exposure.include listing
                        // circuitbreakers/circuitbreakerevents in application.yml -
                        // without this the endpoints are exposed but still fall
                        // through to .anyExchange().authenticated() below and 401,
                        // which is what verify-circuit-breakers.sh catches.
                        .pathMatchers("/actuator/circuitbreakers/**", "/actuator/circuitbreakerevents/**").permitAll()
                        // Public reads: catalog browsing and review summaries need no
                        // token (verify-e2e.sh §4). Only GET is opened - creating a
                        // review, moderating, or writing a product still requires auth
                        // via the catch-all below.
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/products/**", "/api/v1/categories/**",
                                "/api/v1/reviews/products/**").permitAll()
                        // Guest carts: anonymous shoppers, so ALL methods are open
                        // here, not just GET - a guest has to be able to add and
                        // remove items too. This mirrors the identical permitAll in
                        // cart-service's own SecurityConfig; without BOTH, the
                        // gateway 401s anonymous cart calls before they ever reach
                        // the service. Authorization is possession of the
                        // unguessable X-Cart-Token, checked nowhere else because
                        // for an anonymous cart the token IS the credential.
                        // /api/v1/cart/merge is deliberately NOT here - it needs a
                        // real user and falls through to the catch-all below.
                        .pathMatchers("/api/v1/cart/guest", "/api/v1/cart/guest/**").permitAll()
                        // /oauth2/authorization/** and /login/oauth2/code/** are handled
                        // by the oauth2Login filters before authorization — no explicit
                        // permitAll needed.
                        .anyExchange().authenticated()
                )
                .oauth2Login(Customizer.withDefaults())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(entryPoint)
                )
                // Sessions and tokens now OUTLIVE the process, so logout has to
                // delete them rather than rely on the JVM exiting. Two handlers,
                // because Spring's default chain covers neither completely:
                //   - WebSessionServerLogoutHandler invalidates the WebSession,
                //     which with Spring Session means the Redis key is deleted.
                //     The default SecurityContextServerLogoutHandler only clears
                //     the context attribute, leaving the session itself alive.
                //   - RemoveAuthorizedClientLogoutHandler deletes the stored
                //     access/refresh tokens, which nothing in Spring's chain
                //     does - harmless when they lived in a map that died with
                //     the process, a live credential left on disk now.
                .logout(logout -> logout
                        .logoutHandler(new DelegatingServerLogoutHandler(
                                new WebSessionServerLogoutHandler(),
                                new RemoveAuthorizedClientLogoutHandler(authorizedClientService)))
                        .logoutSuccessHandler(logoutSuccessHandler))
                // DEV-ONLY: cookie sessions make the gateway CSRF-relevant (the
                // resource-server-only gateway was not). Disabled for local dev —
                // recorded in the handoff §7 "reverse before production" list.
                // Production direction: cookie-based CSRF token repository consumed
                // by the SPA.
                .csrf(ServerHttpSecurity.CsrfSpec::disable);

        return http.build();
    }
}