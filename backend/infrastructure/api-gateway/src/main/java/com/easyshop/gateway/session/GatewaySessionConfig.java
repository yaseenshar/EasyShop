package com.easyshop.gateway.session;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;

/**
 * Wires the Redis-backed halves of the BFF session.
 *
 * WebSessions themselves need no code - Boot's SessionDataRedisAutoConfiguration
 * sees spring-boot-session-data-redis on the classpath in a reactive app and
 * swaps the in-memory store out. Only the authorized-client service has to be
 * replaced by hand, because Boot's in-memory one is a @ConditionalOnMissingBean
 * and declaring ours is what makes it back off.
 */
@Configuration
@EnableConfigurationProperties(GatewaySessionProperties.class)
public class GatewaySessionConfig {

    @Bean
    public TokenCipher tokenCipher(GatewaySessionProperties properties) {
        return new TokenCipher(properties.decodedEncryptionKey());
    }

    @Bean
    public ReactiveOAuth2AuthorizedClientService reactiveOAuth2AuthorizedClientService(
            ReactiveStringRedisTemplate redis,
            ReactiveClientRegistrationRepository clientRegistrations,
            TokenCipher tokenCipher,
            GatewaySessionProperties properties) {
        return new RedisReactiveOAuth2AuthorizedClientService(
                redis, clientRegistrations, tokenCipher, properties.authorizedClientTtl());
    }
}
