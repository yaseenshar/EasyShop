package com.easyshop.gateway.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two properties this ticket exists for, against a real Redis:
 * a user's tokens survive the gateway process dying, and reading the database
 * does not hand you a working credential.
 *
 * A NEW SERVICE INSTANCE per "restart" is the whole point. Each test that claims
 * survival builds a second RedisReactiveOAuth2AuthorizedClientService over the
 * same Redis and loads through it, so nothing can pass by accident from state
 * cached inside the first instance - which is exactly how the in-memory
 * implementation this replaces would have "passed".
 */
@Testcontainers
class AuthorizedClientRedisIntegrationTest {

    private static final String REGISTRATION_ID = "keycloak";
    private static final String PRINCIPAL = "8f14e45f-ceea-467a-9a1b-2b3c4d5e6f70";
    private static final String ACCESS_TOKEN_VALUE = "access-token-eyJhbGciOiJSUzI1NiJ9.aaa.bbb";
    private static final String REFRESH_TOKEN_VALUE = "refresh-token-eyJhbGciOiJIUzI1NiJ9.ccc.ddd";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private ReactiveStringRedisTemplate redis;
    private ReactiveClientRegistrationRepository registrations;
    private byte[] encryptionKey;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
        redis.getConnectionFactory().getReactiveConnection().serverCommands().flushAll().block();

        registrations = new InMemoryReactiveClientRegistrationRepository(clientRegistration());

        encryptionKey = new byte[32];
        new SecureRandom().nextBytes(encryptionKey);
    }

    /**
     * The headline behaviour: the gateway process is replaced and the user's
     * tokens are still there, so no re-authentication against Keycloak happens.
     */
    @Test
    void tokensSurviveAGatewayRestart() {
        service().saveAuthorizedClient(authorizedClient(), principal()).block();

        // "Restart": a brand-new service instance, nothing carried over in
        // process memory. Only Redis connects the two.
        OAuth2AuthorizedClient reloaded =
                service().loadAuthorizedClient(REGISTRATION_ID, PRINCIPAL).block();

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getPrincipalName()).isEqualTo(PRINCIPAL);
        assertThat(reloaded.getAccessToken().getTokenValue()).isEqualTo(ACCESS_TOKEN_VALUE);
        assertThat(reloaded.getRefreshToken()).isNotNull();
        assertThat(reloaded.getRefreshToken().getTokenValue()).isEqualTo(REFRESH_TOKEN_VALUE);
    }

    @Test
    void accessTokenMetadataSurvivesIntact() {
        service().saveAuthorizedClient(authorizedClient(), principal()).block();

        OAuth2AuthorizedClient reloaded =
                service().loadAuthorizedClient(REGISTRATION_ID, PRINCIPAL).block();

        // Expiry matters as much as the value: a token that comes back without
        // its expiresAt would either be refreshed on every call or used forever.
        assertThat(reloaded.getAccessToken().getTokenType()).isEqualTo(OAuth2AccessToken.TokenType.BEARER);
        assertThat(reloaded.getAccessToken().getExpiresAt()).isNotNull();
        assertThat(reloaded.getAccessToken().getScopes()).containsExactlyInAnyOrder("openid", "profile");
    }

    /**
     * The security posture, asserted rather than asserted-about-in-a-comment:
     * whoever reads this Redis - with the shared password, from the AOF file, or
     * out of a backup - must not come away with a usable refresh token.
     */
    @Test
    void tokenValuesAreCiphertextInRedis() {
        service().saveAuthorizedClient(authorizedClient(), principal()).block();

        Map<String, String> stored = redis.<String, String>opsForHash()
                .entries("gateway:oauth2:client:" + REGISTRATION_ID + ":" + PRINCIPAL)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block();

        assertThat(stored).isNotEmpty();
        assertThat(stored.values())
                .as("no stored field may contain a raw token")
                .noneMatch(value -> value.contains(ACCESS_TOKEN_VALUE) || value.contains(REFRESH_TOKEN_VALUE));

        // And specifically the two fields that grant access.
        assertThat(stored.get("accessTokenValue")).isNotEqualTo(ACCESS_TOKEN_VALUE);
        assertThat(stored.get("refreshTokenValue")).isNotEqualTo(REFRESH_TOKEN_VALUE);
    }

    @Test
    void nonSecretFieldsStayReadableForDebugging() {
        service().saveAuthorizedClient(authorizedClient(), principal()).block();

        Map<String, String> stored = redis.<String, String>opsForHash()
                .entries("gateway:oauth2:client:" + REGISTRATION_ID + ":" + PRINCIPAL)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block();

        // Scopes and expiry are not credentials; leaving them legible is what
        // makes an entry diagnosable in redis-cli.
        assertThat(stored.get("accessTokenScopes")).contains("openid");
    }

    @Test
    void aStoredClientCarriesATtlSoItCannotOutliveItsSession() {
        service().saveAuthorizedClient(authorizedClient(), principal()).block();

        Duration ttl = redis.getExpire("gateway:oauth2:client:" + REGISTRATION_ID + ":" + PRINCIPAL).block();

        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(30));
    }

    /**
     * Logout has to actually delete the tokens. Without the
     * RemoveAuthorizedClientLogoutHandler wired into the security chain, this
     * entry would sit in Redis for the rest of its TTL - a live, decryptable
     * refresh token belonging to someone who believes they signed out.
     */
    @Test
    void removingAClientDeletesItFromRedis() {
        service().saveAuthorizedClient(authorizedClient(), principal()).block();

        service().removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL).block();

        assertThat(redis.hasKey("gateway:oauth2:client:" + REGISTRATION_ID + ":" + PRINCIPAL).block())
                .isFalse();
        assertThat(service().loadAuthorizedClient(REGISTRATION_ID, PRINCIPAL).block()).isNull();
    }

    @Test
    void loadingAnUnknownPrincipalYieldsNothingRatherThanAnEmptyShell() {
        // Must be an empty Mono, not a half-built client - Spring treats a
        // non-null result as "this user is authorized".
        assertThat(service().loadAuthorizedClient(REGISTRATION_ID, "nobody").block()).isNull();
    }

    @Test
    void oneUsersTokensAreNotReachableUnderAnotherUsersName() {
        service().saveAuthorizedClient(authorizedClient(), principal()).block();

        assertThat(service().loadAuthorizedClient(REGISTRATION_ID, "some-other-user").block()).isNull();
    }

    /** A fresh instance every call - see the class comment on why that matters. */
    private RedisReactiveOAuth2AuthorizedClientService service() {
        return new RedisReactiveOAuth2AuthorizedClientService(
                redis, registrations, new TokenCipher(encryptionKey), Duration.ofMinutes(30));
    }

    private static TestingAuthenticationToken principal() {
        return new TestingAuthenticationToken(PRINCIPAL, "n/a");
    }

    private OAuth2AuthorizedClient authorizedClient() {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN_VALUE,
                Instant.now(), Instant.now().plusSeconds(300), Set.of("openid", "profile"));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(REFRESH_TOKEN_VALUE, Instant.now());
        return new OAuth2AuthorizedClient(clientRegistration(), PRINCIPAL, accessToken, refreshToken);
    }

    private static ClientRegistration clientRegistration() {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("easyshop-gateway-bff")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:8080/login/oauth2/code/keycloak")
                .authorizationUri("http://localhost:8090/realms/easyshop/protocol/openid-connect/auth")
                .tokenUri("http://localhost:8090/realms/easyshop/protocol/openid-connect/token")
                .userInfoUri("http://localhost:8090/realms/easyshop/protocol/openid-connect/userinfo")
                .userNameAttributeName("sub")
                .scope("openid")
                .build();
    }
}
