// ApiResponse.java — every REST endpoint returns this shape
package com.easyshop.common.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Standardized API response envelope.
 *
 * Design decision: wrapping responses in an envelope gives you a stable
 * contract for clients even as internal data structures change.
 * It also lets you add metadata (pagination, correlation IDs) without
 * breaking existing clients.
 *
 * Using Java 21 record — immutable, concise, serializes perfectly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)  // Don't serialize null fields
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        String errorCode,
        Instant timestamp
) {
    // Static factory methods — no need for a separate builder
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Success", data, null, Instant.now());
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return new ApiResponse<>(false, message, null, errorCode, Instant.now());
    }
}