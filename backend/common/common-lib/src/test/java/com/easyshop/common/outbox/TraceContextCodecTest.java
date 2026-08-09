package com.easyshop.common.outbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox's trace link is a STRING round trip through a database column, so
 * these tests are about that string. A malformed or silently-dropped
 * traceparent does not fail anything at runtime - the event still publishes,
 * the trace just quietly starts a new root, which looks identical to tracing
 * not being wired up at all.
 */
class TraceContextCodecTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void capturesTheActiveSpanAsAWellFormedTraceparent() {
        Context ctx = Context.root().with(Span.wrap(sampledContext()));
        try (Scope ignored = ctx.makeCurrent()) {
            String traceParent = TraceContextCodec.currentTraceParent();

            assertThat(traceParent).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
            // The column is VARCHAR(55); anything longer would be truncated by
            // the database and become unparseable on the way back out.
            assertThat(traceParent).hasSize(55);
        }
    }

    @Test
    void roundTripsThroughTheStoredString() {
        Context ctx = Context.root().with(Span.wrap(sampledContext()));
        String stored;
        try (Scope ignored = ctx.makeCurrent()) {
            stored = TraceContextCodec.currentTraceParent();
        }

        // The restore happens on a different thread minutes later, with nothing
        // current - exactly the publisher's situation.
        Context restored = TraceContextCodec.restore(stored);

        assertThat(restored).isNotNull();
        SpanContext sc = Span.fromContext(restored).getSpanContext();
        assertThat(sc.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(sc.getSpanId()).isEqualTo(SPAN_ID);
        assertThat(sc.isSampled()).isTrue();
        // Remote, so Jaeger renders the publish as a continuation of the
        // original trace rather than a sibling root.
        assertThat(sc.isRemote()).isTrue();
    }

    @Test
    void returnsNullWhenNothingIsBeingTraced() {
        // A scheduled job, a test, or a service with tracing disabled. Must not
        // throw - it runs inside @PrePersist on every outbox write.
        assertThat(TraceContextCodec.currentTraceParent()).isNull();
    }

    @Test
    void refusesMalformedOrAbsentValues() {
        assertThat(TraceContextCodec.restore(null)).isNull();
        assertThat(TraceContextCodec.restore("")).isNull();
        assertThat(TraceContextCodec.restore("not-a-traceparent")).isNull();
        // Right length, wrong shape - the length check alone is not enough.
        assertThat(TraceContextCodec.restore("x".repeat(55))).isNull();
        // Unsupported version: this codec only claims to understand 00.
        assertThat(TraceContextCodec.restore("99-" + TRACE_ID + "-" + SPAN_ID + "-01")).isNull();
        // All-zero ids are structurally invalid per the spec.
        assertThat(TraceContextCodec.restore("00-" + "0".repeat(32) + "-" + "0".repeat(16) + "-01")).isNull();
    }

    /**
     * An unsampled parent is dropped rather than propagated: continuing a trace
     * that nothing recorded produces spans whose parent will never exist, which
     * renders as a broken tree rather than an honest new root.
     */
    @Test
    void doesNotRestoreAnUnsampledParent() {
        String unsampled = "00-" + TRACE_ID + "-" + SPAN_ID + "-00";

        Context restored = TraceContextCodec.restore(unsampled);

        assertThat(restored).isNotNull();
        assertThat(Span.fromContext(restored).getSpanContext().isSampled()).isFalse();
    }

    private static SpanContext sampledContext() {
        return SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    }
}
