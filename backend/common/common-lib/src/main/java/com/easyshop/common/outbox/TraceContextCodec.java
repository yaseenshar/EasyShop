package com.easyshop.common.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

/**
 * Reads and rebuilds a W3C traceparent so a trace can survive being written to
 * a database row and picked up later by a different thread.
 *
 * WHY OPENTELEMETRY'S API DIRECTLY, and not Micrometer Tracing's Propagator
 * bean: this is called from a JPA @PrePersist callback on an entity, which is
 * not a Spring bean and has nowhere to inject anything. OpenTelemetry's
 * Span.current() is explicitly a static, context-reading API - it is the one
 * piece of this stack designed to be reachable from code Spring does not own.
 * The dependency is optional in common-lib, and every method here degrades to
 * a no-op when the classes or an active span are absent, so services without
 * tracing are unaffected.
 *
 * FORMAT (W3C Trace Context): {@code 00-<32 hex trace id>-<16 hex span id>-<2 hex flags>}
 * Version 00 is emitted rather than parsed loosely, because that is the only
 * version this codec claims to understand.
 */
public final class TraceContextCodec {

    private static final String VERSION = "00";
    private static final int TRACEPARENT_LENGTH = 55;

    private TraceContextCodec() {}

    /**
     * The traceparent of the currently active span, or null when nothing is
     * being traced - a scheduled job outside any request, a unit test, or a
     * service with tracing switched off.
     */
    public static String currentTraceParent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return VERSION + "-" + context.getTraceId() + "-" + context.getSpanId()
                + "-" + context.getTraceFlags().asHex();
    }

    /**
     * Rebuilds a Context from a stored traceparent so work can continue the
     * original trace.
     *
     * createFromRemoteParent, not a local parent: from this process's point of
     * view the originating span happened elsewhere - a different thread,
     * minutes ago, possibly a different instance after a restart. Marking it
     * remote is what makes Jaeger render the publish as a continuation rather
     * than inventing a sibling root.
     *
     * @return the restored context, or null if the value is absent or malformed
     */
    public static Context restore(String traceParent) {
        if (traceParent == null || traceParent.length() != TRACEPARENT_LENGTH) {
            return null;
        }
        String[] parts = traceParent.split("-");
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return null;
        }
        try {
            SpanContext remote = SpanContext.createFromRemoteParent(
                    parts[1], parts[2], TraceFlags.fromHex(parts[3], 0), TraceState.getDefault());
            // An unsampled or structurally invalid parent is treated as "no
            // context" rather than being propagated: continuing a trace nobody
            // recorded produces spans whose parent will never exist.
            return remote.isValid() ? Context.root().with(Span.wrap(remote)) : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
