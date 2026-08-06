package com.easyshop.gateway.session;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler;

import reactor.core.publisher.Mono;

/**
 * Deletes the user's stored access/refresh tokens on logout.
 *
 * Spring does NOT do this by itself. Its logout chain clears the security
 * context and can invalidate the session, but the authorized client is held in a
 * separate store keyed by registration id and principal name, and nothing in the
 * default chain removes it. In memory that oversight was invisible - the map
 * entry died with the process. Once the same data is in Redis with a TTL, it
 * stops being invisible: without this handler, "log out" would leave a live,
 * decryptable refresh token on disk for the rest of its TTL, still able to mint
 * access tokens for a user who believes they have signed out.
 *
 * Best-effort by design. If removal fails, logout still proceeds - stranding the
 * user in a half-logged-out state because Redis hiccuped would be worse than
 * relying on the TTL to clean up, and the entry does expire on its own. The
 * failure is swallowed rather than logged with detail because the key contains
 * the principal name.
 */
public class RemoveAuthorizedClientLogoutHandler implements ServerLogoutHandler {

    private final ReactiveOAuth2AuthorizedClientService authorizedClientService;

    public RemoveAuthorizedClientLogoutHandler(ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    @Override
    public Mono<Void> logout(WebFilterExchange exchange, Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            // A bearer-token caller has no stored client to remove: its token
            // lives with the API client, not here.
            return Mono.empty();
        }
        return authorizedClientService
                .removeAuthorizedClient(oauthToken.getAuthorizedClientRegistrationId(), oauthToken.getName())
                .onErrorResume(error -> Mono.empty());
    }
}
