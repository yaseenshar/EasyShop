package com.easyshop.gateway.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * RP-initiated (single) logout, built by hand instead of via Spring's
 * OidcClientInitiatedServerLogoutSuccessHandler.
 *
 * WHY NOT THE BUILT-IN HANDLER: it reads Keycloak's end_session_endpoint from
 * the ClientRegistration's OIDC provider metadata - which is ONLY ever
 * populated via issuer-based discovery (a startup call to
 * /.well-known/openid-configuration). This gateway deliberately does NOT use
 * issuer-uri (see application.yml: from inside the container, localhost:8090
 * doesn't resolve and the gateway crash-loops - the same reason
 * authorization-uri/token-uri/etc. are all given manually). Boot 4.1's
 * OAuth2ClientProperties.Provider has no endSessionUri property to plug the
 * gap either (verified against the actual class - no such field exists).
 * So: no discovery, no property, no metadata - the built-in handler has
 * nothing to redirect to and silently falls back to Spring's default
 * "/login?logout", which never touches Keycloak's own SSO session at all
 * (empirically confirmed - that's exactly what happened before this class
 * existed). A hand-built redirect with a hardcoded endpoint sidesteps all of
 * that; it costs one URL constant in exchange.
 */
public class KeycloakLogoutSuccessHandler implements ServerLogoutSuccessHandler {

    // Same host Keycloak the BROWSER already talks to for authorization-uri -
    // this redirect is issued to the browser, never called server-side.
    private static final String END_SESSION_ENDPOINT =
            "http://localhost:8090/realms/easyshop/protocol/openid-connect/logout";

    @Override
    public Mono<Void> onLogoutSuccess(WebFilterExchange exchange, Authentication authentication) {
        var request = exchange.getExchange().getRequest();
        String baseUrl = request.getURI().getScheme() + "://" + request.getURI().getAuthority();

        UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(END_SESSION_ENDPOINT)
                .queryParam("post_logout_redirect_uri", baseUrl + "/");

        // id_token_hint tells Keycloak WHICH SSO session to end and lets it
        // skip the "are you sure?" confirmation prompt. Best-effort: if the
        // principal isn't an OidcUser for some reason, still redirect - an
        // end-session call without the hint degrades to Keycloak asking the
        // browser to confirm, which is still a real logout, just one click
        // longer, not a broken one.
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
            redirect.queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue());
        }

        URI location = redirect.build().toUri();
        ServerHttpResponse response = exchange.getExchange().getResponse();
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(location);
        return response.setComplete();
    }
}
