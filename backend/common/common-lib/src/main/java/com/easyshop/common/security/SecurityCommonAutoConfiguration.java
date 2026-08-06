package com.easyshop.common.security; // align with your common-lib package layout

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Fleet-wide security auto-configuration — the SAME mechanism as
 * CommonAutoConfiguration/GlobalExceptionHandler (§5.2): drop common-lib on the
 * classpath and the bean appears, zero per-service wiring for the shared part.
 *
 * INSTALL: append this line to the EXISTING imports file
 *   common-lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 *
 *   com.easyshop.common.security.SecurityCommonAutoConfiguration
 *
 * (Exact filename matters — anything else is silently ignored, as you already
 * learned the first time.)
 *
 * CONDITIONS, and why each one is there:
 *   - SERVLET-only: the api-gateway is reactive; a servlet-stack
 *     JwtAuthenticationConverter bean must never leak into it. The gateway keeps
 *     its own reactive configuration and stays authentication-only (see README §1).
 *   - ConditionalOnClass: notification-service and other non-resource-server
 *     modules that pull common-lib must not fail on a missing security class.
 *   - ConditionalOnMissingBean: any service may override with its own converter
 *     without fighting the auto-configuration.
 *
 * DELIBERATELY SHARED vs DELIBERATELY LOCAL (the common-lib boundary rule, §4.14):
 * the claim->authority mapping is a wire-contract concern — identical everywhere,
 * so it lives here. The endpoint rules are business authorization — they differ
 * per service and stay in each service's SecurityConfig. This is the first step
 * of the deferred §8.6 SecurityConfig consolidation, taken only as far as the
 * genuinely-identical part.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(JwtAuthenticationConverter.class)
@EnableConfigurationProperties(JwksProperties.class)
public class SecurityCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRolesConverter());
        return converter;
    }

    /**
     * Bounds how often a token with an unrecognised "kid" can make this service
     * call Keycloak - see JwksProperties for the measured amplification this
     * closes, and ThrottlingJwkSetRestTemplate for why the fix sits on the
     * transport rather than on the JwtDecoder.
     *
     * Boot applies this customizer to BOTH decoder paths (jwk-set-uri and
     * issuer-uri), so every service picks it up from common-lib with no local
     * wiring - the same "drop it on the classpath" mechanism as the converter
     * above. The api-gateway is reactive and gets its own equivalent; this
     * configuration is servlet-only and must never leak into it.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwkSetUriJwtDecoderBuilderCustomizer throttledJwkSetFetchCustomizer(JwksProperties properties) {
        ThrottlingJwkSetRestTemplate restTemplate =
                new ThrottlingJwkSetRestTemplate(properties.minRefreshInterval());
        return builder -> builder.restOperations(restTemplate);
    }
}