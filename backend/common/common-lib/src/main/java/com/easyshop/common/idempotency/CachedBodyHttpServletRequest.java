package com.easyshop.common.idempotency; // align with common-lib layout

import java.io.ByteArrayInputStream;
import java.io.IOException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Buffers the request body into a byte[] and REPLAYS it on every getInputStream()
 * call.
 *
 * WHY NOT Spring's ContentCachingRequestWrapper: that one only records bytes as
 * they are read downstream — it does NOT let you re-read. The idempotency engine
 * must read the body TWICE: once in the interceptor to fingerprint it, and again
 * in the controller to bind it. If we consumed the stream in the interceptor
 * with a non-replaying wrapper, the controller would receive an empty body. This
 * wrapper replays, so both readers see the full payload.
 *
 * jakarta.servlet, not javax (Jakarta EE 11, §6.1).
 *
 * The buffering is bounded upstream by the filter (maxCachedBodyBytes) — this
 * wrapper is only constructed for requests that opted in with an Idempotency-Key
 * header AND fit under the cap, so large uploads never land here.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return buffer.read(); }
            @Override public boolean isFinished() { return buffer.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { /* sync only */ }
        };
    }

    @Override
    public java.io.BufferedReader getReader() throws IOException {
        return new java.io.BufferedReader(
                new java.io.InputStreamReader(getInputStream(), getCharacterEncoding() == null
                        ? "UTF-8" : getCharacterEncoding()));
    }
}