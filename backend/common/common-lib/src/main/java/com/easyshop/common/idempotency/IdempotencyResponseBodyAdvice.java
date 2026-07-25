package com.easyshop.common.idempotency; // align with common-lib layout

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Captures the EXACT bytes a @ResponseBody/ResponseEntity handler serializes, for
 * IdempotencyInterceptor#afterCompletion to persist as the replay record.
 *
 * WHY NOT JUST ContentCachingResponseWrapper#getContentAsByteArray(): that was the
 * original design (see the ticket README's §9 flag on this exact risk) and it
 * reliably returns EMPTY here - verified live, not assumed. The wrapper's buffer
 * is populated by whatever the message converter writes to
 * HttpServletResponse#getOutputStream(), but afterCompletion() is invoked by
 * DispatcherServlet.processDispatchResult() at a point where that write is not
 * reliably reflected in the wrapper's cache for this response-writing path
 * (HttpEntityMethodProcessor / RequestResponseBodyMethodProcessor write via
 * ServletServerHttpResponse, one more layer removed from the raw wrapper than the
 * request-side CachedBodyHttpServletRequest reads). Capturing the bytes AT THE
 * SOURCE - here, before the converter ever touches the response stream - sidesteps
 * that timing question entirely instead of chasing it further.
 *
 * Scoped to idempotency-acquired requests only: beforeBodyWrite() is a hot path
 * for EVERY @ResponseBody return value in the app, so it must be near-free when
 * IdempotencyAttributes.KEY isn't set (i.e., every non-idempotent endpoint, and
 * idempotent-endpoint replays/rejects that never reached the controller).
 */
@ControllerAdvice
public class IdempotencyResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper mapper;

    public IdempotencyResponseBodyAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true; // cheap; the real gate is the request-attribute check in beforeBodyWrite
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                   Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                   ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) return body;
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        if (httpRequest.getAttribute(IdempotencyAttributes.KEY) == null) return body; // not an Acquired idempotent request

        try {
            httpRequest.setAttribute(IdempotencyAttributes.RESPONSE_BODY_BYTES, mapper.writeValueAsBytes(body));
        } catch (Exception serializationFailed) {
            // Best-effort: afterCompletion falls back to ContentCachingResponseWrapper
            // (empty, per the known issue above) rather than fail the request over a
            // caching miss - the response the client actually gets is unaffected.
        }
        return body;
    }
}
