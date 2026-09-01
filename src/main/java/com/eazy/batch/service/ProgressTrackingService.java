package com.eazy.batch.service;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for tracking batch job progress.
 * Registered exclusively via BatchProcessorAutoConfiguration#progressTrackingService -
 * intentionally NOT annotated with @Service; see MetricsService for why.
 */
@Slf4j
public class ProgressTrackingService {

    private final int updateInterval;
    private final ConcurrentHashMap<Long, ProgressInfo> progressMap = new ConcurrentHashMap<>();

    public ProgressTrackingService(int updateInterval) {
        // FIXED: guard against a misconfigured (zero or negative) interval,
        // which previously caused an ArithmeticException (divide by zero) in
        // updateProgress()'s modulo check.
        this.updateInterval = updateInterval > 0 ? updateInterval : 100;
    }

    public void initProgress(Long jobExecutionId, long totalItems) {
        progressMap.put(jobExecutionId, new ProgressInfo(totalItems));
        log.info("Progress tracking initialized for job {}: {} total items",
                jobExecutionId, totalItems > 0 ? totalItems : "unknown");
    }

    public void updateProgress(Long jobExecutionId, long processedItems) {
        ProgressInfo info = progressMap.get(jobExecutionId);
        if (info == null) return;

        info.processedItems = processedItems;

        // Only log at intervals
        if (processedItems % updateInterval == 0) {
            if (info.totalItems > 0) {
                double percentage = (double) processedItems / info.totalItems * 100;
                // FIXED: SLF4J only supports bare "{}" placeholders, not
                // String.format-style specifiers like "{:.2f}" - that text
                // was being printed literally and the percentage argument
                // silently dropped. Pre-format the value instead.
                log.info("Job {} progress: {}/{} ({}%)",
                        jobExecutionId, processedItems, info.totalItems,
                        String.format("%.2f", percentage));
            } else {
                log.info("Job {} progress: {} items processed", jobExecutionId, processedItems);
            }
        }
    }

    public void completeProgress(Long jobExecutionId) {
        ProgressInfo info = progressMap.remove(jobExecutionId);
        if (info != null) {
            log.info("Job {} completed: {}/{} items processed",
                    jobExecutionId, info.processedItems, info.totalItems);
        }
    }

    public ProgressInfo getProgress(Long jobExecutionId) {
        return progressMap.get(jobExecutionId);
    }

    public static class ProgressInfo {
        public final long totalItems;
        public long processedItems;
        public final long startTime;

        public ProgressInfo(long totalItems) {
            this.totalItems = totalItems;
            this.processedItems = 0;
            this.startTime = System.currentTimeMillis();
        }

        public double getPercentage() {
            return totalItems > 0 ? (double) processedItems / totalItems * 100 : 0;
        }

        public long getElapsedTimeMs() {
            return System.currentTimeMillis() - startTime;
        }

        public long getEstimatedRemainingMs() {
            if (processedItems == 0) return 0;
            long avgTimePerItem = getElapsedTimeMs() / processedItems;
            return avgTimePerItem * (totalItems - processedItems);
        }
    }
}
