package com.eazy.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for tracking batch job progress
 */
@Slf4j
@Service
public class ProgressTrackingService {

    private final int updateInterval;
    private final ConcurrentHashMap<Long, ProgressInfo> progressMap = new ConcurrentHashMap<>();

    public ProgressTrackingService(int updateInterval) {
        this.updateInterval = updateInterval;
    }

    public void initProgress(Long jobExecutionId, long totalItems) {
        progressMap.put(jobExecutionId, new ProgressInfo(totalItems));
        log.info("Progress tracking initialized for job {}: {} total items",
                jobExecutionId, totalItems);
    }

    public void updateProgress(Long jobExecutionId, long processedItems) {
        ProgressInfo info = progressMap.get(jobExecutionId);
        if (info == null) return;

        info.processedItems = processedItems;

        // Only log at intervals
        if (processedItems % updateInterval == 0) {
            double percentage = info.totalItems > 0
                    ? (double) processedItems / info.totalItems * 100
                    : 0;
            log.info("Job {} progress: {}/{} ({:.2f}%)",
                    jobExecutionId, processedItems, info.totalItems, percentage);
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