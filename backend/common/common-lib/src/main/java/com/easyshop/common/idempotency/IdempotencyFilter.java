package com.easyshop.common.idempotency; // align with common-lib layout

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Sets up the wrappers the interceptor needs — and does so ONLY when it's worth
 * it, so non-idempotent traffic pays nothing.
 *
 * GATE (cheap, in order):
 *   1. mutating method (POST/PUT/PATCH) — GETs are never idempotency-tracked
 *   2. Idempotency-Key header present — no header, no engine, no buffering
 *   3. body under the cap — huge uploads pass through un-buffered (the
 *      interceptor then treats them as required-key-satisfied but un-replayable)
 *
 * Why a filter can't just do the whole job: at filter time the handler method
 * is not resolved, so the filter cannot see @Idempotent. The DECISION (is this
 * endpoint annotated? replay? 409? 422?) belongs to the interceptor, which runs
 * after handler mapping. The filter's sole job is wrapper installation + the
 * final response flush.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyProperties props;

    public IdempotencyFilter(IdempotencyProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (!isMutating(request) || request.getHeader(props.getKeyHeader()) == null) {
            chain.doFilter(request, response); // fast path: engine does not apply
            return;
        }

        // Buffer the body up to the cap. Over the cap -> don't buffer, mark the
        // request so the interceptor skips REPLAY (dedup lock can still apply).
        byte[] body = request.getInputStream().readNBytes(props.getMaxCachedBodyBytes() + 1);
        HttpServletRequest wrappedRequest;
        if (body.length > props.getMaxCachedBodyBytes()) {
            request.setAttribute(IdempotencyAttributes.OVERSIZED, Boolean.TRUE);
            // We've partially consumed the stream; to stay correct we still need
            // a replayable wrapper carrying what we read PLUS the remainder.
            byte[] rest = request.getInputStream().readAllBytes();
            byte[] full = concat(body, rest);
            wrappedRequest = new CachedBodyHttpServletRequest(request, full);
        } else {
            wrappedRequest = new CachedBodyHttpServletRequest(request, body);
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            // MUST flush — ContentCachingResponseWrapper buffers everything; the
            // interceptor read the buffer in afterCompletion (before this line).
            wrappedResponse.copyBodyToResponse();
        }
    }

    private boolean isMutating(HttpServletRequest r) {
        String m = r.getMethod();
        return "POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}