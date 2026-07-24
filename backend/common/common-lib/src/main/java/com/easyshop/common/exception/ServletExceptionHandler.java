package com.easyshop.common.exception;

import com.easyshop.common.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handlers split out of GlobalExceptionHandler deliberately: both exception
 * types here extend jakarta.servlet.ServletException under the hood -
 * HttpRequestMethodNotSupportedException directly, MissingRequestValueException
 * via its ServletRequestBindingException parent - even though both classes
 * live in the servlet-agnostic spring-web module. api-gateway is pure
 * WebFlux/Netty and never puts jakarta.servlet-api on its classpath, so when
 * these handlers lived on GlobalExceptionHandler, Spring's controller-advice
 * cache eagerly reflected over every method via Class.getDeclaredMethods() -
 * which resolves every parameter type up front - and NoClassDefFoundError on
 * jakarta.servlet.ServletException failed the WHOLE bean, crash-looping the
 * gateway at startup. Same reasoning as SecurityExceptionHandler and
 * ValidationExceptionHandler; these two handlers were missed the first time.
 *
 * CommonAutoConfiguration gates this bean on jakarta.servlet.ServletException
 * itself being resolvable, since that is the actual missing link on
 * WebFlux-only services.
 */
@RestControllerAdvice
public class ServletExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ex.getMessage(), "METHOD_NOT_ALLOWED"));
    }

    @ExceptionHandler(MissingRequestValueException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingValue(MissingRequestValueException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage(), "VALIDATION_ERROR"));
    }
}
