package com.easyshop.common.exception;

import com.easyshop.common.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 403/401 mapping, split out of GlobalExceptionHandler deliberately.
 *
 * WHY A SEPARATE CLASS: common-lib declares spring-boot-starter-oauth2-
 * resource-server as OPTIONAL (each consuming service brings its own copy
 * if it needs Spring Security at all). GlobalExceptionHandler USED to
 * reference AccessDeniedException/AuthenticationException directly - fine
 * for every service that already has a security starter (order-service,
 * catalog-service, user-service, cart-service, review-service, payment-
 * service, api-gateway), but inventory-service and notification-service are
 * Kafka-driven only, with no security starter on their classpath at all.
 * CommonAutoConfiguration registers the advice bean for any @ConditionalOn-
 * WebApplication service (both of them qualify - they expose Actuator), and
 * Spring's controller-advice cache eagerly reflects over EVERY method on the
 * class via Class.getDeclaredMethods() - which resolves every parameter
 * type up front, so one missing class fails the WHOLE bean, not just the
 * offending method. Result: NoClassDefFoundError at startup, both services
 * crash-looping (confirmed empirically - see the project-structure-refactor
 * verification pass that rebuilt every service fresh and caught it).
 *
 * Splitting this out and gating it on the actual Spring Security class
 * being present is what makes it safe for the whole fleet regardless of
 * which services opt into a security starter.
 *
 * @Order(HIGHEST_PRECEDENCE): must resolve before GlobalExceptionHandler's
 * Exception.class catch-all — see that class's javadoc for why order
 * matters here at all.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        // 403 = authenticated but not authorized. Keep the message generic:
        // do not echo which role was required (that's free reconnaissance).
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied", "FORBIDDEN"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        // Filter-chain authentication failures never reach the advice (the entry
        // point answers first, with WWW-Authenticate). This covers authentication
        // exceptions raised later, during controller/service execution — kept for
        // symmetry so the 401/403 split stays intact end to end.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication required", "UNAUTHORIZED"));
    }
}
