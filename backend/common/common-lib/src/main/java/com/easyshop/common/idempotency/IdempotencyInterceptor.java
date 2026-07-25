package com.easyshop.common.idempotency; // align with common-lib layout

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import com.easyshop.common.dto.response.ApiResponse; // align with common-lib's ApiResponse
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * The idempotency decision layer. Runs after Spring Security (so the principal
 * is available to scope keys per-user) and after handler mapping (so it can read
 * @Idempotent off the HandlerMethod).
 *
 * preHandle:  extract key -> fingerprint body -> store.begin()
 *   ACQUIRED    -> stash key, proceed to controller
 *   COMPLETED   -> if fingerprint matches, REPLAY the cached response (return
 *                  false); if it differs, 422 (same key, different payload =
 *                  client bug, must not silently return the wrong result)
 *   IN_PROGRESS -> if fingerprint matches, 409 (a duplicate is mid-flight,
 *                  retry shortly); if it differs, 422
 * afterCompletion: if we acquired -> cacheable(2xx) ? complete() : release()
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final ObjectMapper mapper;

    public IdempotencyInterceptor(IdempotencyStore store, IdempotencyProperties props, ObjectMapper mapper) {
        this.store = store;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (!(handler instanceof HandlerMethod hm)) return true;
        Idempotent ann = hm.getMethodAnnotation(Idempotent.class);
        if (ann == null) return true; // not an idempotent endpoint — do nothing

        String key = header(request, props.getKeyHeader());
        if (key == null || key.isBlank()) {
            if (ann.required()) {
                return write(response, HttpStatus.BAD_REQUEST,
                        "Idempotency-Key header is required.", "IDEMPOTENCY_KEY_REQUIRED");
            }
            return true; // optional: proceed without protection
        }
        if (!isValidKey(key)) {
            return write(response, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is malformed.", "IDEMPOTENCY_KEY_INVALID");
        }

        String redisKey = buildKey(request, key);
        String fingerprint = fingerprint(request);

        Idempotent.RedisFailureMode mode = ann.onRedisFailure();
        IdempotencyStore.Begin begin;
        try {
            begin = store.begin(redisKey, fingerprint, Duration.ofSeconds(ann.inProgressTtlSeconds()));
        } catch (DataAccessException redisDown) {
            // §4.8 proportionality: fail OPEN (process; a backstop catches dupes)
            // or CLOSED (reject) per the endpoint's declared risk.
            if (mode == Idempotent.RedisFailureMode.CLOSED) {
                return write(response, HttpStatus.SERVICE_UNAVAILABLE,
                        "Idempotency store unavailable.", "IDEMPOTENCY_UNAVAILABLE");
            }
            return true; // OPEN — logged by the store; DB constraint backstops
        }

        return switch (begin) {
            case IdempotencyStore.Begin.Acquired ignored -> {
                request.setAttribute(IdempotencyAttributes.KEY, redisKey);
                request.setAttribute(IdempotencyAttributes.FINGERPRINT, fingerprint);
                request.setAttribute(IdempotencyAttributes.TTL_SECONDS, ann.completedTtlSeconds());
                yield true;
            }
            case IdempotencyStore.Begin.Completed c -> {
                if (!c.record().fingerprint().equals(fingerprint)) {
                    yield write(response, HttpStatus.UNPROCESSABLE_ENTITY,
                            "Idempotency-Key was reused with a different request.",
                            "IDEMPOTENCY_KEY_REUSED");
                }
                replay(response, c.record()); // verbatim original response
                yield false;
            }
            case IdempotencyStore.Begin.InProgress p -> {
                if (!p.fingerprint().equals(fingerprint)) {
                    yield write(response, HttpStatus.UNPROCESSABLE_ENTITY,
                            "Idempotency-Key was reused with a different request.",
                            "IDEMPOTENCY_KEY_REUSED");
                }
                response.setHeader("Retry-After", "1");
                yield write(response, HttpStatus.CONFLICT,
                        "A request with this Idempotency-Key is already in progress.",
                        "IDEMPOTENCY_IN_PROGRESS");
            }
        };
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object keyAttr = request.getAttribute(IdempotencyAttributes.KEY);
        if (keyAttr == null) return; // we didn't acquire a lock on this request
        String redisKey = (String) keyAttr;

        try {
            int status = response.getStatus();
            boolean cacheable = status >= 200 && status < 300
                    && request.getAttribute(IdempotencyAttributes.OVERSIZED) == null;

            if (!cacheable) {
                // 4xx/5xx or oversized -> DO NOT memoize. Release so a genuine
                // retry runs immediately instead of hitting a stale in-progress
                // lock. (Caching a 5xx would replay the failure forever.)
                store.release(redisKey);
                return;
            }

            byte[] body = new byte[0];
            String contentType = response.getContentType();
            // Prefer IdempotencyResponseBodyAdvice's capture (the actual serialized
            // bytes, stashed at write-time) - ContentCachingResponseWrapper's buffer
            // is unreliable here (see that class's javadoc; verified empty live).
            // Wrapper stays as a fallback for a return type that never crosses
            // ResponseBodyAdvice (e.g. void/ModelAndView - not used by @Idempotent
            // endpoints today, but cheap insurance).
            Object captured = request.getAttribute(IdempotencyAttributes.RESPONSE_BODY_BYTES);
            if (captured instanceof byte[] capturedBytes) {
                body = capturedBytes;
            } else if (response instanceof ContentCachingResponseWrapper w) {
                body = w.getContentAsByteArray();
            }
            String fp = (String) request.getAttribute(IdempotencyAttributes.FINGERPRINT);
            long ttl = (long) request.getAttribute(IdempotencyAttributes.TTL_SECONDS);

            store.complete(redisKey, IdempotencyRecord.of(status, contentType, body, fp),
                    Duration.ofSeconds(ttl));
        } catch (DataAccessException redisDown) {
            // Best-effort: the response already went to the client. A future
            // retry that also can't reach Redis fails open; the DB constraint
            // remains the backstop.
        }
    }

    // ----------------------------------------------------------------- helpers

    private void replay(HttpServletResponse response, IdempotencyRecord r) throws Exception {
        response.setStatus(r.status());
        if (r.contentType() != null) response.setContentType(r.contentType());
        response.setHeader("Idempotent-Replayed", "true"); // Stripe-style signal
        response.getOutputStream().write(r.body());
    }

    private boolean write(HttpServletResponse response, HttpStatus status, String msg, String code)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(msg, code));
        return false; // short-circuit
    }

    /** idem:{app}:{sub}:{key} — namespaced per service and per authenticated user. */
    private String buildKey(HttpServletRequest request, String clientKey) {
        String sub = "anon";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) sub = auth.getName();
        return "idem:" + props.getNamespace() + ":" + sub + ":" + clientKey;
    }

    /**
     * method + path + body — so the same key with a different payload is caught.
     *
     * request.getInputStream(), not an instanceof CachedBodyHttpServletRequest
     * check: by the time this runs, further filters (Spring Security's chain
     * runs after IdempotencyFilter - see its ordering comment) may have wrapped
     * the request again, so it's no longer directly THAT concrete type -
     * instanceof failed here 100% of the time in practice, silently fingerprinting
     * every request as an empty body and defeating the whole mismatch check (found
     * live: a reused key with a materially different body replayed instead of
     * 422'ing). getInputStream() correctly reaches CachedBodyHttpServletRequest's
     * override through any number of well-behaved wrapper layers, since a plain
     * HttpServletRequestWrapper delegates getInputStream() to what it wraps by
     * default. Safe to call here even though the controller reads the body too -
     * CachedBodyHttpServletRequest.getInputStream() replays from its buffer on
     * every call, never consumes it.
     */
    private String fingerprint(HttpServletRequest request) throws Exception {
        byte[] body = request.getInputStream().readAllBytes();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(body);
        return HexFormat.of().formatHex(md.digest());
    }

    private boolean isValidKey(String key) {
        return key.length() <= 200 && key.chars().allMatch(c ->
                c == '-' || c == '_' || Character.isLetterOrDigit(c));
    }

    private String header(HttpServletRequest r, String name) {
        String v = r.getHeader(name);
        return v == null ? null : v.trim();
    }
}