package com.eazy.batch.service;

// MetricsService.java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for collecting batch processing metrics
 */
@Slf4j
@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> itemCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> skipCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> jobTimers = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public MetricsService() {
        this.meterRegistry = null;
    }

    public void recordItemProcessed(String jobName) {
        if (meterRegistry == null) return;

        itemCounters.computeIfAbsent(jobName, name ->
                Counter.builder("batch.items.processed")
                        .tag("job", name)
                        .description("Number of items processed")
                        .register(meterRegistry)
        ).increment();
    }

    public void recordItemSkipped(String jobName, String phase) {
        if (meterRegistry == null) return;

        skipCounters.computeIfAbsent(jobName + "_" + phase, key ->
                Counter.builder("batch.items.skipped")
                        .tag("job", jobName)
                        .tag("phase", phase)
                        .description("Number of items skipped")
                        .register(meterRegistry)
        ).increment();
    }

    public void recordJobDuration(String jobName, Duration duration) {
        if (meterRegistry == null) return;

        jobTimers.computeIfAbsent(jobName, name ->
                Timer.builder("batch.job.duration")
                        .tag("job", name)
                        .description("Job execution duration")
                        .register(meterRegistry)
        ).record(duration);
    }

    public void recordJobSuccess(String jobName) {
        if (meterRegistry == null) return;

        Counter.builder("batch.job.success")
                .tag("job", jobName)
                .description("Successful job completions")
                .register(meterRegistry)
                .increment();
    }

    public void recordJobFailure(String jobName) {
        if (meterRegistry == null) return;

        Counter.builder("batch.job.failure")
                .tag("job", jobName)
                .description("Failed job executions")
                .register(meterRegistry)
                .increment();
    }
}