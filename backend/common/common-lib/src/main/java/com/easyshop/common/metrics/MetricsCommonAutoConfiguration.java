package com.easyshop.common.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;

/**
 * Fleet-wide meter configuration - the same "drop common-lib on the classpath"
 * mechanism as SecurityCommonAutoConfiguration, applied to metrics.
 *
 * Doing this here rather than as YAML in nine services is not just about
 * repetition: latency buckets and tag names are what dashboards and alerts are
 * written against, so they have to be IDENTICAL everywhere. Nine copies of a
 * property drift, and the drift is invisible until a dashboard silently stops
 * matching one service.
 *
 * DELIBERATELY NOT servlet-conditional, unlike the security auto-configuration
 * next door. The api-gateway is reactive and is the one box every request
 * passes through - excluding it would blind exactly the service whose latency
 * matters most.
 */
/*
 * ORDERING IS LOAD-BEARING for the @ConditionalOnBean below. Auto-configuration
 * conditions are evaluated in order, against the beans registered SO FAR - so a
 * @ConditionalOnBean(MeterRegistry.class) that happens to run before Boot's
 * metrics auto-configuration silently sees no registry and quietly contributes
 * nothing. The failure has no error message; the bean is simply absent, and the
 * first thing anyone notices is a service failing to start on a missing
 * BusinessMetrics dependency. afterName is used rather than after because these
 * classes live in a module common-lib deliberately does not depend on, and Boot
 * 4 relocated them into org.springframework.boot.micrometer.metrics.autoconfigure.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnClass(MeterRegistry.class)
public class MetricsCommonAutoConfiguration {

    /**
     * Tags every meter with the service that emitted it.
     *
     * Prometheus already attaches a "job" label from the scrape config, but that
     * is a property of how the target was configured, not of the process. When a
     * service is scraped under a different job name, sits behind a relabel rule,
     * or is queried through a recording rule, "job" no longer identifies it.
     * An application tag emitted BY the application always does, which makes
     * cross-service queries (sum by (application)) reliable.
     *
     * Expressed as a MeterFilter rather than a MeterRegistryCustomizer purely so
     * common-lib needs micrometer-core alone: MeterRegistryCustomizer lives in
     * actuator's autoconfigure module, and pulling actuator in here to add one
     * tag would push it onto every consumer of this library.
     */
    /**
     * Available to any service that wants outcome counters; see BusinessMetrics
     * for why a plain registry.counter() is the wrong tool inside a
     * transaction. Conditional on a registry BEAN (not just the class) so a
     * service without metrics configured doesn't fail to start on a missing
     * dependency.
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public BusinessMetrics businessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }

    @Bean
    public MeterFilter commonMetricTags(
            @Value("${spring.application.name:unknown}") String applicationName) {
        return MeterFilter.commonTags(List.of(Tag.of("application", applicationName)));
    }

    /**
     * Publishes latency BUCKETS for HTTP server requests, without which no
     * percentile is computable in Prometheus.
     *
     * A Micrometer Timer publishes count and sum by default. That yields a mean
     * and nothing else - and a mean is precisely the statistic that hides the
     * problem, since a handful of 5-second requests vanish into an average
     * dominated by fast ones. histogram_quantile() needs _bucket series, and
     * _bucket series only exist if something asks for them.
     *
     * EXPLICIT SLO BUCKETS RATHER THAN percentilesHistogram(true). The latter
     * turns on Micrometer's full default bucket set - dozens of buckets, each
     * one multiplied by every uri x method x status combination the service
     * serves. That is the standard way to accidentally put a six-figure series
     * count into Prometheus. These seven boundaries are enough to compute a
     * useful p95/p99 and to alert on "share of requests slower than 500ms",
     * at a seventh of the cardinality.
     *
     * Client-side percentiles (publishPercentiles) are deliberately NOT enabled:
     * they are pre-computed per instance and CANNOT be aggregated across
     * replicas - averaging two instances' p99 does not give you the fleet p99.
     * Buckets aggregate correctly, which is the whole reason to prefer them.
     */
    @Bean
    public MeterFilter httpServerLatencyBuckets() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().startsWith("http.server.requests")) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(
                                Duration.ofMillis(50).toNanos(),
                                Duration.ofMillis(100).toNanos(),
                                Duration.ofMillis(200).toNanos(),
                                Duration.ofMillis(500).toNanos(),
                                Duration.ofSeconds(1).toNanos(),
                                Duration.ofSeconds(2).toNanos(),
                                Duration.ofSeconds(5).toNanos())
                        .build()
                        .merge(config);
            }
        };
    }
}
