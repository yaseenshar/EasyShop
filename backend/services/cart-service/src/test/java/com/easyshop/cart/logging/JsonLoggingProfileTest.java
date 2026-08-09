package com.easyshop.cart.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real service with the json-logging profile and inspects what Logback
 * actually assembled.
 *
 * WHY THIS EXISTS. common-lib now ships a logback-spring.xml that every service
 * inherits, which is exactly the leverage that makes it dangerous: a typo in
 * that one file breaks the logging subsystem of the entire fleet at startup,
 * and an XML config is not compiled, so nothing else would catch it. This test
 * is the compile step that file never gets.
 *
 * It also pins the profile switch itself - the difference between "we configured
 * JSON logging" and "the containers are actually emitting JSON" is one profile
 * name, and getting it wrong produces plain text with no error anywhere.
 *
 * A full web context is booted because that is the shape every service runs in;
 * the logging subsystem is assembled during startup either way.
 */
@SpringBootTest(
        classes = com.easyshop.cart.CartServiceApplication.class,
        // RANDOM_PORT, not NONE: common-lib's security auto-configuration is
        // @ConditionalOnWebApplication(SERVLET), so a non-web context leaves
        // SecurityConfig without its JwtAuthenticationConverter and the
        // application fails to start for reasons unrelated to logging.
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.data.redis.password=",
                // No Redis container needed: nothing here touches it, and the
                // connection is lazy.
                "spring.data.redis.host=localhost",
                "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
                "management.otlp.metrics.export.enabled=false"
        })
@ActiveProfiles("json-logging")
@org.springframework.context.annotation.Import(JsonLoggingProfileTest.StubJwtDecoder.class)
class JsonLoggingProfileTest {

    /**
     * application.yml points the resource server at Keycloak; supplying a
     * decoder keeps this test from depending on one being reachable. Never
     * invoked - nothing here authenticates.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class StubJwtDecoder {
        @org.springframework.context.annotation.Bean
        org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("no real tokens in this test");
            };
        }
    }

    private static List<Appender<?>> rootAppenders() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        List<Appender<?>> appenders = new ArrayList<>();
        for (Iterator<?> it = root.iteratorForAppenders(); it.hasNext(); ) {
            appenders.add((Appender<?>) it.next());
        }
        return appenders;
    }

    /**
     * If the shared XML were malformed, the context would not have started and
     * this test would never run - which is most of its value.
     */
    @Test
    void theSharedLogbackConfigurationIsValidAndApplied() {
        assertThat(rootAppenders())
                .as("common-lib's logback-spring.xml must have been picked up from the classpath")
                .isNotEmpty();
    }

    /**
     * The profile actually swaps the appender. Asserting the ENCODER type, not
     * the appender name, because the name is cosmetic while the encoder is what
     * decides whether a shipper sees JSON or prose.
     */
    @Test
    void theJsonProfileSelectsTheLogstashEncoder() {
        var encoders = rootAppenders().stream()
                .filter(a -> a instanceof ch.qos.logback.core.OutputStreamAppender<?>)
                .map(a -> ((ch.qos.logback.core.OutputStreamAppender<?>) a).getEncoder())
                .toList();

        assertThat(encoders)
                .as("with json-logging active the console must use the Logstash JSON encoder, "
                        + "not Boot's pattern encoder")
                .anyMatch(e -> e instanceof LogstashEncoder);
    }

    /**
     * Only one console appender is attached. Both are declared in the shared
     * config, and attaching both would emit every line twice - once as prose and
     * once as JSON - doubling log volume and giving the shipper duplicate
     * records that are hard to spot because each looks correct on its own.
     */
    @Test
    void exactlyOneConsoleAppenderIsAttached() {
        assertThat(rootAppenders())
                .filteredOn(a -> a instanceof ch.qos.logback.core.ConsoleAppender<?>)
                .hasSize(1);
    }
}
