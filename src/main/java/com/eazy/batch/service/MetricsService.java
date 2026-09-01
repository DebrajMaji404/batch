package com.eazy.batch.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for collecting batch processing metrics.
 * Registered exclusively via BatchProcessorAutoConfiguration#metricsService -
 * intentionally NOT annotated with @Service, since this package sits under
 * com.eazy.batch and would otherwise get double-registered by component
 * scanning whenever a consumer's base package covers com.eazy.batch (e.g.
 * this module's own BatchApplication).
 */
@Slf4j
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final ConcurrentHashMap<String, Counter> itemCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> skipCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> jobTimers = new ConcurrentHashMap<>();

    public MetricsService(MeterRegistry meterRegistry, boolean enabled) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    private boolean isActive() {
        return enabled && meterRegistry != null;
    }

    public void recordItemProcessed(String jobName) {
        if (!isActive()) return;

        itemCounters.computeIfAbsent(jobName, name ->
                Counter.builder("batch.items.processed")
                        .tag("job", name)
                        .description("Number of items processed")
                        .register(meterRegistry)
        ).increment();
    }

    public void recordItemSkipped(String jobName, String phase) {
        if (!isActive()) return;

        skipCounters.computeIfAbsent(jobName + "_" + phase, key ->
                Counter.builder("batch.items.skipped")
                        .tag("job", jobName)
                        .tag("phase", phase)
                        .description("Number of items skipped")
                        .register(meterRegistry)
        ).increment();
    }

    public void recordJobDuration(String jobName, Duration duration) {
        if (!isActive()) return;

        jobTimers.computeIfAbsent(jobName, name ->
                Timer.builder("batch.job.duration")
                        .tag("job", name)
                        .description("Job execution duration")
                        .register(meterRegistry)
        ).record(duration);
    }

    public void recordJobSuccess(String jobName) {
        if (!isActive()) return;

        Counter.builder("batch.job.success")
                .tag("job", jobName)
                .description("Successful job completions")
                .register(meterRegistry)
                .increment();
    }

    public void recordJobFailure(String jobName) {
        if (!isActive()) return;

        Counter.builder("batch.job.failure")
                .tag("job", jobName)
                .description("Failed job executions")
                .register(meterRegistry)
                .increment();
    }
}
