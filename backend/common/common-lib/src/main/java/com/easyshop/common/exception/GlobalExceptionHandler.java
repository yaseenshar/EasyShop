package com.easyshop.common.exception;

import com.easyshop.common.dto.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.stream.Collectors;

/**
 * Fleet-wide exception -> HTTP mapping. Lives in common-lib so all eight
 * services share one error contract instead of each inventing its own.
 *
 * WHY THIS EXISTS: without it, every domain exception becomes a bare 500.
 * That is not just cosmetic - a 500 tells a client "server broke, maybe
 * retry", while a 404/409 tells it "your request was understood and is
 * wrong, do not retry". Getting these codes right IS the API contract,
 * and it matters doubly here: order-service's saga distinguishes
 * retryable infrastructure failures from terminal business failures, and
 * a mislabeled 500 makes that distinction unreachable for any HTTP caller.
 *
 * DESIGN CHOICE - envelope vs ProblemDetail: we return the existing
 * ApiResponse envelope (Phase 1) for consistency with every success
 * response. Spring 6+ also offers RFC 7807 ProblemDetail, which is the
 * emerging standard and worth naming in an interview; mixing both would
 * mean two error shapes on one API, so we pick one deliberately.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        // 404, not 500: the request was valid, the resource simply is not there.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), "RESOURCE_NOT_FOUND"));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        // 409 Conflict: state collision (already reviewed, already registered).
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "DUPLICATE_RESOURCE"));
    }

    /**
     * DB-level unique/FK constraint violations that slipped past the
     * application-level DuplicateResourceException check (e.g. a race
     * between the existence check and the insert, or JIT provisioning
     * hitting an email already owned by a different keycloak_id). 409, not
     * 500 - the request collided with existing state, it did not break
     * the server.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Request conflicts with existing data", "DUPLICATE_RESOURCE"));
    }

    /**
     * Bean-validation failures on @Valid @RequestBody. Returns WHICH fields
     * failed - a 400 that does not say what was wrong just forces the
     * client into guesswork.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Validation failed - " + details, "VALIDATION_ERROR"));
    }

    // ConstraintViolationException handler lives in ValidationExceptionHandler,
    // NOT here - same NoClassDefFoundError reasoning as SecurityExceptionHandler
    // (jakarta.validation-api is optional in common-lib's pom; notification-
    // service has no spring-boot-starter-validation on its classpath at all).

    /**
     * Invalid state transitions - Order.transitionTo(), Review.approve() on
     * an already-moderated review, ProductStock.confirmReservation() beyond
     * what was reserved. 409, because the request is legal in general but
     * illegal against current state.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "INVALID_STATE"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getMessage(), "INVALID_ARGUMENT"));
    }

    /**
     * Catch-all. Deliberately does NOT leak ex.getMessage() to the client -
     * stack traces and internal messages are an information-disclosure
     * vector (DB structure, file paths, library versions). Log the detail
     * server-side, return something generic. This is the one handler where
     * being unhelpful to the caller is the correct security posture.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred", "INTERNAL_ERROR"));
    }

    /**
     * Malformed/unparseable request bodies (missing required primitive field,
     * unparseable JSON, wrong type). A 400, not the catch-all 500 - the client
     * sent something the server correctly rejected as unreadable.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Malformed request body", "VALIDATION_ERROR"));
    }

    // HttpRequestMethodNotSupportedException and MissingRequestValueException
    // handlers live in ServletExceptionHandler, NOT here - both transitively
    // extend jakarta.servlet.ServletException, so on a pure-WebFlux service
    // (api-gateway) with no servlet API on the classpath, referencing them
    // here crashes the whole bean with NoClassDefFoundError at startup. See
    // ServletExceptionHandler's javadoc for the full story.

    // AccessDeniedException/AuthenticationException handlers live in
    // SecurityExceptionHandler, NOT here - see its javadoc for why splitting
    // them out was necessary (NoClassDefFoundError on Kafka-only services with
    // no Spring Security on the classpath).
}