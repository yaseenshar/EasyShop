// ============================================================================
// review-service — propagate MDC (log/trace context) across the thread-pool
// bulkhead boundary.
//
// WHY: @Bulkhead(type = THREADPOOL) executes on a pool thread that has a BLANK
// ThreadLocal. Without this, every log line emitted during the order call loses
// its trace/correlation id — which quietly breaks the §8.4 OpenTelemetry ->
// Jaeger work before it starts. Register a ContextPropagator that snapshots the
// MDC on the calling thread and reinstates it on the pool thread.
//
// SCOPE NOTE: this propagates MDC only. SecurityContext is intentionally NOT
// propagated here because this bulkhead wraps an M2M call that uses its own
// token. If you ever put a THREADPOOL bulkhead around code that needs the
// USER's SecurityContext, you must add a SecurityContext propagator too (the
// well-known context-propagation libraries support it) — or, better, don't:
// use a SEMAPHORE bulkhead / @ConcurrencyLimit there so the context stays on
// the calling thread. That choice is in the README matrix.
//
// KNOWN QUIRK (verified in R4j issue history): on the thread-pool path MDC can
// be cleared before a fallback runs, and retries lose it entirely. We avoid the
// retry side by not stacking @Retry on the bulkhead method (see PurchaseVerifier).
// ============================================================================

package com.easyshop.review.config; // align with your package layout

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.common.bulkhead.configuration.ThreadPoolBulkheadConfigCustomizer;
import io.github.resilience4j.core.ContextPropagator;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BulkheadContextConfig {

    /**
     * Snapshot-restore-clear for the SLF4J MDC. One instance is registered on
     * the bulkhead's executor so every submitted task carries the caller's MDC.
     */
    public static final class MdcContextPropagator implements ContextPropagator<Map<String, String>> {

        @Override
        public java.util.function.Supplier<Optional<Map<String, String>>> retrieve() {
            // Runs on the CALLING thread: capture.
            return () -> Optional.ofNullable(MDC.getCopyOfContextMap());
        }

        @Override
        public java.util.function.Consumer<Optional<Map<String, String>>> copy() {
            // Runs on the POOL thread before the task: reinstate.
            return ctx -> ctx.ifPresent(map -> {
                MDC.clear();
                MDC.setContextMap(new HashMap<>(map));
            });
        }

        @Override
        public java.util.function.Consumer<Optional<Map<String, String>>> clear() {
            // Runs on the POOL thread after the task: clean up (no leakage
            // between reused pool threads).
            return ctx -> MDC.clear();
        }
    }

    /**
     * Attach the propagator to the "orderInternal" thread-pool bulkhead.
     * Customizer targets the instance by name; keep the name in sync with the
     * annotation and the yml.
     */
    @Bean
    public ThreadPoolBulkheadConfigCustomizer orderInternalContextCustomizer() {
        return ThreadPoolBulkheadConfigCustomizer.of("orderInternal", builder ->
                builder.contextPropagator(new MdcContextPropagator()));
    }

    // §9 FLAG (verify against the resilience4j version on your classpath — the
    // servlet starter currently resolves 2.2.0 autoconfig with 2.3.0 core):
    //   - ThreadPoolBulkheadConfigCustomizer.of(name, ...) signature
    //   - ThreadPoolBulkheadConfig.builder().contextPropagator(...) method name
    // Both are stable in recent 2.x, but confirm they resolve; if the customizer
    // API differs, the fallback is a ContextAwareScheduledThreadPoolExecutor or
    // Micrometer's context-propagation integration wired on the executor.
    static ThreadPoolBulkheadConfig referenceConfigForCompileCheck() {
        return ThreadPoolBulkheadConfig.custom()
                .contextPropagator(new MdcContextPropagator())
                .build();
    }
}