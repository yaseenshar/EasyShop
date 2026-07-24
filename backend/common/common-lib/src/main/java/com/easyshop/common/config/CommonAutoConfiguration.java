package com.easyshop.common.config;

import com.easyshop.common.exception.GlobalExceptionHandler;
import com.easyshop.common.exception.SecurityExceptionHandler;
import com.easyshop.common.exception.ServletExceptionHandler;
import com.easyshop.common.exception.ValidationExceptionHandler;
import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;

/**
 * THE WIRING PROBLEM THIS SOLVES: each service's @SpringBootApplication
 * sits in com.easyshop.<service> and component-scans only from there
 * downward. com.easyshop.common is a SIBLING package, so nothing in
 * common-lib is ever discovered - the @RestControllerAdvice would sit
 * there doing nothing, silently.
 *
 * Two ways to fix it:
 *   (a) @SpringBootApplication(scanBasePackages = {"com.easyshop.user",
 *       "com.easyshop.common"}) on every service - works, but must be
 *       repeated eight times and is easy to forget on service nine.
 *   (b) THIS: register an auto-configuration, the mechanism Spring Boot
 *       starters themselves use. Drop common-lib on the classpath and the
 *       handler appears. Zero per-service wiring.
 *
 * @ConditionalOnWebApplication keeps it out of non-web contexts.
 * @ConditionalOnMissingBean lets any service override with its own handler.
 */
@AutoConfiguration
@ConditionalOnWebApplication
public class CommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /**
     * Gated on the actual Spring Security class being resolvable - common-lib
     * declares its oauth2-resource-server dependency optional (§5.2), so
     * inventory-service and notification-service (Kafka-driven only, no
     * security starter) never have AccessDeniedException on the classpath at
     * all. Without this guard the bean method still runs and Spring's advice-
     * cache introspection (Class.getDeclaredMethods()) resolves every method
     * signature eagerly, so ONE missing type crashes those services at
     * startup with NoClassDefFoundError - confirmed empirically the first
     * time every service got rebuilt fresh from source in one pass. See
     * SecurityExceptionHandler's own javadoc for the full story.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(AccessDeniedException.class)
    public SecurityExceptionHandler securityExceptionHandler() {
        return new SecurityExceptionHandler();
    }

    /** Same reasoning as securityExceptionHandler(), for jakarta.validation
     *  instead of Spring Security - see ValidationExceptionHandler's javadoc. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ConstraintViolationException.class)
    public ValidationExceptionHandler validationExceptionHandler() {
        return new ValidationExceptionHandler();
    }

    /** Same reasoning again, for jakarta.servlet.ServletException (both of
     *  ServletExceptionHandler's handled types transitively extend it) - see
     *  ServletExceptionHandler's javadoc. api-gateway (pure WebFlux/Netty) is
     *  the service this actually guards today. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ServletException.class)
    public ServletExceptionHandler servletExceptionHandler() {
        return new ServletExceptionHandler();
    }
}