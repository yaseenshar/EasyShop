package com.easyshop.cart;

import com.easyshop.cart.config.CartProperties;
import com.easyshop.cart.repository.CartRepository;
import com.easyshop.cart.service.CartService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Shared context for the non-web cart tests: Redis plus the cart beans, and
 * nothing else.
 *
 * Deliberately NOT CartServiceApplication - component scanning it would drag in
 * the web layer and the OAuth2 resource server, which then wants a reachable
 * Keycloak at startup. The tests that DO need the security chain
 * (GuestCartWebIntegrationTest) boot the real application and stub the decoder
 * instead; everything else is faster and less brittle without it.
 */
@Configuration
@EnableAutoConfiguration
@EnableConfigurationProperties(CartProperties.class)
@Import({CartRepository.class, CartService.class})
public class CartTestApp {
}
