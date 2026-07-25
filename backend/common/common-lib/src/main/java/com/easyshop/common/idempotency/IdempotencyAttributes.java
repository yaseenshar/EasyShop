package com.easyshop.common.idempotency; // align with common-lib layout

/** Request-attribute keys shared between the filter, interceptor, and response advice. */
final class IdempotencyAttributes {
    static final String KEY = "easyshop.idem.key";
    static final String FINGERPRINT = "easyshop.idem.fingerprint";
    static final String TTL_SECONDS = "easyshop.idem.ttl";
    static final String OVERSIZED = "easyshop.idem.oversized";
    /**
     * The exact bytes IdempotencyResponseBodyAdvice serialized for a @ResponseBody
     * return value, stashed here for afterCompletion() to persist. Needed because
     * ContentCachingResponseWrapper.getContentAsByteArray() is unreliable for this
     * path - see IdempotencyResponseBodyAdvice's javadoc.
     */
    static final String RESPONSE_BODY_BYTES = "easyshop.idem.responseBodyBytes";
    private IdempotencyAttributes() {}
}