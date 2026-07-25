package com.easyshop.common.idempotency; // align with common-lib layout

import java.util.Base64;

/**
 * The memoized response for a completed idempotent request, plus the request
 * fingerprint used to detect key reuse across DIFFERENT payloads.
 *
 * Body is stored base64 so arbitrary bytes survive the JSON round trip. Keep
 * bodies bounded (see IdempotencyProperties.maxCachedBodyBytes): Redis also
 * holds carts (primary store, §4.9) and the cache on the same 8 GB box, so
 * unbounded response bodies would eat the memory budget the KRaft/heap work
 * fought to reclaim (§7). Above the cap, the engine still DEDUPS (the lock and
 * fingerprint work) but does not REPLAY the body — a duplicate gets a 409
 * rather than a cached 200.
 */
public record IdempotencyRecord(
        int status,
        String contentType,
        String bodyBase64,
        String fingerprint) {

    public static IdempotencyRecord of(int status, String contentType, byte[] body, String fingerprint) {
        String b64 = body == null ? "" : Base64.getEncoder().encodeToString(body);
        return new IdempotencyRecord(status, contentType, b64, fingerprint);
    }

    public byte[] body() {
        return bodyBase64 == null || bodyBase64.isEmpty()
                ? new byte[0]
                : Base64.getDecoder().decode(bodyBase64);
    }
}