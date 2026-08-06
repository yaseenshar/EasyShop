package com.easyshop.gateway.session;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a BFF WebSession outlives the gateway process.
 *
 * THE RESTART IS REAL HERE, not simulated by reusing a bean: the first Spring
 * context is fully CLOSED before the second is started, and the only thing
 * connecting them is Redis. That distinction is the whole test - with the
 * in-memory WebSessionStore this replaces, the session would die with the first
 * context and findById would return nothing, which is precisely the behaviour
 * that used to force every user back through Keycloak on every deploy.
 */
@Testcontainers
class RedisWebSessionRestartIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Test
    @SuppressWarnings("unchecked")
    void aSessionWrittenBeforeARestartIsStillReadableAfterIt() {
        String sessionId;

        // ---- gateway instance #1 ----
        try (ConfigurableApplicationContext first = startGateway()) {
            ReactiveSessionRepository<Session> sessions =
                    first.getBean(ReactiveSessionRepository.class);

            Session session = sessions.createSession().block();
            session.setAttribute("principal", "8f14e45f-ceea-467a-9a1b-2b3c4d5e6f70");
            sessions.save(session).block();
            sessionId = session.getId();

            assertThat(sessionId).isNotBlank();
        }

        // ---- the process is gone; a new one comes up against the same Redis ----
        try (ConfigurableApplicationContext second = startGateway()) {
            ReactiveSessionRepository<Session> sessions =
                    second.getBean(ReactiveSessionRepository.class);

            Session restored = sessions.findById(sessionId).block();

            assertThat(restored)
                    .as("the session must survive the restart, or every user is logged out by a deploy")
                    .isNotNull();
            assertThat(restored.<String>getAttribute("principal"))
                    .isEqualTo("8f14e45f-ceea-467a-9a1b-2b3c4d5e6f70");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionsAreStoredUnderTheConfiguredNamespace() {
        try (ConfigurableApplicationContext context = startGateway()) {
            ReactiveSessionRepository<Session> sessions =
                    context.getBean(ReactiveSessionRepository.class);
            ReactiveStringRedisTemplate redis = context.getBean(ReactiveStringRedisTemplate.class);

            Session session = sessions.createSession().block();
            sessions.save(session).block();

            // Namespacing is what keeps gateway sessions distinguishable from
            // cart:* and idem:* in the shared instance - worth pinning, since a
            // silently-ignored property would just put keys under Spring's
            // default prefix and nobody would notice until they went looking.
            Long matching = redis.keys("easyshop:gateway:session*").count().block();
            assertThat(matching).isPositive();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingASessionRemovesItFromRedis() {
        try (ConfigurableApplicationContext context = startGateway()) {
            ReactiveSessionRepository<Session> sessions =
                    context.getBean(ReactiveSessionRepository.class);

            Session session = sessions.createSession().block();
            sessions.save(session).block();
            String id = session.getId();
            assertThat(sessions.findById(id).block()).isNotNull();

            // What WebSessionServerLogoutHandler triggers on logout. If session
            // deletion did not reach Redis, logging out would leave a live
            // session key behind and logout would be cosmetic.
            sessions.deleteById(id).block();

            assertThat(sessions.findById(id).block()).isNull();
        }
    }

    private static ConfigurableApplicationContext startGateway() {
        return new SpringApplicationBuilder(SessionOnlyApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        // Points config loading at a name that does not exist, so
                        // the gateway's own application.yml is NOT picked up off
                        // the classpath. Without this the context tries to reach
                        // Eureka and to resolve the routes' userKeyResolver, and
                        // fails for reasons that have nothing to do with sessions.
                        "spring.config.name=session-store-test",
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "eureka.client.enabled=false",
                        "spring.cloud.discovery.enabled=false",
                        "spring.data.redis.host=" + REDIS.getHost(),
                        "spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "spring.session.timeout=30m",
                        // spring.session.DATA.redis.namespace - see the note in
                        // the gateway's application.yml; the shorter form binds
                        // to nothing and keys land under "spring:session".
                        "spring.session.data.redis.namespace=easyshop:gateway:session")
                .run();
    }

    /**
     * Only Redis and Spring Session - deliberately NOT the gateway application
     * class, which would drag in Eureka, the routes and the OAuth2 client and
     * make this test about everything except sessions.
     */
    @Configuration
    @EnableAutoConfiguration
    static class SessionOnlyApp {
    }
}
