package com.easyshop.review.config;

import com.easyshop.review.client.OrderPurchaseClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Consumer-driven HTTP service registration - the verified-current Spring
 * Framework 7 / Boot 4 pattern. @ImportHttpServices tells Spring: "I am a
 * CONSUMER of these interfaces; generate client proxies and register them
 * as beans." No manual HttpServiceProxyFactory wiring, no @Bean per client.
 *
 * The interfaces are organized into named GROUPS; all clients in a group
 * share HTTP client configuration, set in application.yml under
 * spring.http.client.service.group.<name>.* (base-url, read-timeout, etc).
 *
 * Load balancing: the official Spring blog confirms Spring Cloud 2025.1
 * provides transparent load-balancing support for HTTP service groups -
 * meaning the group's base-url can resolve order-service through Eureka
 * rather than a hardcoded host. FLAGGED AS PARTIALLY VERIFIED: the
 * capability is confirmed, but I have not verified the exact property
 * syntax for a load-balanced base-url (whether it's base-url:
 * lb://order-service or a separate lb flag). Check the Spring Cloud
 * 2025.1 release notes / commons docs for the precise form; the
 * application.yml here uses lb://order-service with a fallback comment
 * showing the direct-host form that definitely works.
 *
 * IMPORT-PATH CAVEAT: the package for @ImportHttpServices
 * (org.springframework.web.service.registry) is my best reading of
 * current sources, not something I've compiled against - if the import
 * fails, search the spring-web javadoc for ImportHttpServices.
 */
@Configuration
@ImportHttpServices(group = "order-service", types = OrderPurchaseClient.class)
public class HttpClientConfig {
}