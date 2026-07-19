package com.easyshop.common.exception;

import com.easyshop.common.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ConstraintViolationException (jakarta.validation, e.g. @Validated on a
 * @PathVariable/@RequestParam) mapping, split out of GlobalExceptionHandler.
 *
 * Same reasoning as SecurityExceptionHandler: common-lib declares spring-
 * boot-starter-validation OPTIONAL, and notification-service (no REST
 * request bodies worth validating - it only reacts to Kafka events) never
 * added it. Left inline in GlobalExceptionHandler, this one method's
 * missing parameter type would crash the WHOLE advice bean at startup via
 * Spring's eager Class.getDeclaredMethods() introspection - confirmed
 * empirically the same way the security split was (see that class's javadoc
 * for the full story).
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage(), "VALIDATION_ERROR"));
    }
}
