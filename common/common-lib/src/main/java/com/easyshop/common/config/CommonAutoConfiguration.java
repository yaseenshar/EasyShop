package com.easyshop.common.config;

import com.easyshop.common.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

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
}