package com.eazy.batch.service;

import com.eazy.batch.dto.BatchProgressMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of recent job runs. Spring Batch 6 uses a resourceless job
 * repository by default, so there is nothing to query for "how is job 42
 * doing?" - this registry keeps the latest {@link BatchProgressMessage} per
 * job execution (including the final one with the report), and the live
 * {@link JobExecution}s so a run can be stopped.
 *
 * <p>Process-local and bounded (24h / 1000 entries); it is a convenience for
 * the status/stop endpoints, the Actuator endpoint and tests, not a store.</p>
 */
public final class BatchRunRegistry {

    private static final Cache<Long, BatchProgressMessage> LAST = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24)).maximumSize(1000).build();
    private static final Map<Long, JobExecution> RUNNING = new ConcurrentHashMap<>();

    private BatchRunRegistry() {
    }

    public static void record(BatchProgressMessage message) {
        if (message == null || message.getJobExecutionId() == null) return;
        LAST.put(message.getJobExecutionId(), message);
    }

    public static BatchProgressMessage last(Long jobExecutionId) {
        return jobExecutionId == null ? null : LAST.getIfPresent(jobExecutionId);
    }

    /** Most recent runs first, at most {@code max}. */
    public static List<BatchProgressMessage> recent(int max) {
        List<BatchProgressMessage> all = new ArrayList<>(LAST.asMap().values());
        all.sort(Comparator.comparing(BatchProgressMessage::getJobExecutionId).reversed());
        return all.size() > max ? all.subList(0, max) : all;
    }

    public static void started(JobExecution execution) {
        if (execution != null) RUNNING.put(execution.getId(), execution); // getId() is a primitive long in Batch 6
    }

    public static void finished(Long jobExecutionId) {
        if (jobExecutionId != null) RUNNING.remove(jobExecutionId);
    }

    public static boolean isRunning(Long jobExecutionId) {
        return jobExecutionId != null && RUNNING.containsKey(jobExecutionId);
    }

    /**
     * Asks a running job to stop: every step execution is flagged
     * terminate-only, so the step ends after the current chunk.
     *
     * @return false when no such job is running in this JVM
     */
    public static boolean stop(Long jobExecutionId) {
        JobExecution execution = jobExecutionId == null ? null : RUNNING.get(jobExecutionId);
        if (execution == null) return false;
        for (StepExecution step : execution.getStepExecutions()) {
            step.setTerminateOnly();
        }
        return true;
    }
}
