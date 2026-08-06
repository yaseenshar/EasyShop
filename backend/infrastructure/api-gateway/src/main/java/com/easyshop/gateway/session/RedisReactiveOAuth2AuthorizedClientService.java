package com.easyshop.gateway.session;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stores OAuth2 authorized clients - the user's access and refresh tokens - in
 * Redis instead of Spring's default in-memory map.
 *
 * WHY THIS IS SEPARATE FROM THE SESSION. It would be reasonable to assume that
 * putting WebSessions in Redis carries the tokens along with them, and for some
 * setups it would: Spring Security has a WebSession-backed authorized-client
 * repository. But Boot auto-configures an InMemoryReactiveOAuth2AuthorizedClient-
 * SERVICE bean, and the presence of that bean makes the repository an
 * AuthenticatedPrincipalServerOAuth2AuthorizedClientRepository, which delegates
 * to the service and never touches the session. So sessions in Redis alone would
 * have produced a user who stays logged in across a restart but whose every
 * proxied call fails for want of a relayed token - arguably worse than being
 * logged out cleanly. Replacing the service is what actually closes it.
 *
 * A REDIS HASH, NOT A SERIALIZED BLOB. Java serialization would couple the
 * stored form to exact class versions, and a JSON blob of a Spring Security
 * domain object drags in whatever those classes expose. A hash of named fields
 * is stable, greppable in redis-cli, and makes it obvious at a glance which
 * fields are ciphertext - the same reasoning as CartRepository's hash model.
 *
 * TOKEN VALUES ARE ENCRYPTED, everything else is not. Expiry timestamps and
 * scopes are not credentials, and leaving them readable keeps the entry
 * debuggable; the two fields that actually grant access are the ones that must
 * be useless to a reader of the database. See TokenCipher.
 *
 * EVERY ENTRY HAS A TTL. An authorized client that outlives its session is
 * unreachable, so keeping it only leaves a decryptable credential on disk for
 * longer than anything can use it.
 */
public class RedisReactiveOAuth2AuthorizedClientService implements ReactiveOAuth2AuthorizedClientService {

    private static final String KEY_PREFIX = "gateway:oauth2:client:";

    private static final String FIELD_ACCESS_TOKEN_VALUE = "accessTokenValue";
    private static final String FIELD_ACCESS_TOKEN_ISSUED = "accessTokenIssuedAt";
    private static final String FIELD_ACCESS_TOKEN_EXPIRES = "accessTokenExpiresAt";
    private static final String FIELD_ACCESS_TOKEN_SCOPES = "accessTokenScopes";
    private static final String FIELD_REFRESH_TOKEN_VALUE = "refreshTokenValue";
    private static final String FIELD_REFRESH_TOKEN_ISSUED = "refreshTokenIssuedAt";

    private final ReactiveStringRedisTemplate redis;
    private final ReactiveClientRegistrationRepository clientRegistrations;
    private final TokenCipher cipher;
    private final Duration ttl;

    public RedisReactiveOAuth2AuthorizedClientService(ReactiveStringRedisTemplate redis,
                                                      ReactiveClientRegistrationRepository clientRegistrations,
                                                      TokenCipher cipher,
                                                      Duration ttl) {
        this.redis = redis;
        this.clientRegistrations = clientRegistrations;
        this.cipher = cipher;
        this.ttl = ttl;
    }

    /**
     * The principal name is part of the key, and it comes from the JWT "sub"
     * claim (application.yml pins user-name-attribute to sub), never from
     * anything the caller can choose - the same identity-derived addressing that
     * keeps carts un-guessable.
     */
    private String key(String registrationId, String principalName) {
        return KEY_PREFIX + registrationId + ":" + principalName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String registrationId,
                                                                          String principalName) {
        String key = key(registrationId, principalName);
        return redis.<String, String>opsForHash().entries(key)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(fields -> !fields.isEmpty())
                .flatMap(fields -> clientRegistrations.findByRegistrationId(registrationId)
                        .map(registration -> (T) toAuthorizedClient(registration, principalName, fields)));
    }

    @Override
    public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                                           org.springframework.security.core.Authentication principal) {
        String key = key(authorizedClient.getClientRegistration().getRegistrationId(), principal.getName());

        OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
        OAuth2RefreshToken refreshToken = authorizedClient.getRefreshToken();

        // A LinkedHashMap keeps field order stable, which only matters for
        // humans reading HGETALL output - but that is the reason the hash model
        // was chosen, so it is worth keeping.
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put(FIELD_ACCESS_TOKEN_VALUE, cipher.encrypt(accessToken.getTokenValue()));
        fields.put(FIELD_ACCESS_TOKEN_ISSUED, String.valueOf(
                accessToken.getIssuedAt() == null ? 0L : accessToken.getIssuedAt().toEpochMilli()));
        fields.put(FIELD_ACCESS_TOKEN_EXPIRES, String.valueOf(
                accessToken.getExpiresAt() == null ? 0L : accessToken.getExpiresAt().toEpochMilli()));
        fields.put(FIELD_ACCESS_TOKEN_SCOPES, String.join(",", accessToken.getScopes()));
        if (refreshToken != null) {
            fields.put(FIELD_REFRESH_TOKEN_VALUE, cipher.encrypt(refreshToken.getTokenValue()));
            fields.put(FIELD_REFRESH_TOKEN_ISSUED, String.valueOf(
                    refreshToken.getIssuedAt() == null ? 0L : refreshToken.getIssuedAt().toEpochMilli()));
        }

        return redis.opsForHash().putAll(key, java.util.Collections.unmodifiableMap(fields))
                .then(redis.expire(key, ttl))
                .then();
    }

    @Override
    public Mono<Void> removeAuthorizedClient(String registrationId, String principalName) {
        return redis.delete(key(registrationId, principalName)).then();
    }

    private OAuth2AuthorizedClient toAuthorizedClient(ClientRegistration registration,
                                                     String principalName,
                                                     Map<String, String> fields) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                // Only bearer is stored: it is the sole token type this gateway
                // ever receives from Keycloak, and guessing at others on read
                // would invent support that was never exercised.
                OAuth2AccessToken.TokenType.BEARER,
                cipher.decrypt(fields.get(FIELD_ACCESS_TOKEN_VALUE)),
                instantOrNull(fields.get(FIELD_ACCESS_TOKEN_ISSUED)),
                instantOrNull(fields.get(FIELD_ACCESS_TOKEN_EXPIRES)),
                scopes(fields.get(FIELD_ACCESS_TOKEN_SCOPES)));

        OAuth2RefreshToken refreshToken = null;
        String storedRefresh = fields.get(FIELD_REFRESH_TOKEN_VALUE);
        if (storedRefresh != null) {
            refreshToken = new OAuth2RefreshToken(cipher.decrypt(storedRefresh),
                    instantOrNull(fields.get(FIELD_REFRESH_TOKEN_ISSUED)));
        }

        return new OAuth2AuthorizedClient(registration, principalName, accessToken, refreshToken);
    }

    private static Instant instantOrNull(String millis) {
        if (millis == null) {
            return null;
        }
        long value = Long.parseLong(millis);
        return value == 0L ? null : Instant.ofEpochMilli(value);
    }

    private static Set<String> scopes(String joined) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(joined.split(","))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
