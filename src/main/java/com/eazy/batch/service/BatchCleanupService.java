package com.eazy.batch.service;

import com.eazy.batch.utility.BatchUtility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Service to periodically clean up old batch job data.
 * Registered exclusively via BatchProcessorAutoConfiguration#batchCleanupService -
 * intentionally NOT annotated with @Service; see MetricsService for why -
 * and its constructor takes a plain int with no @Value, so component
 * scanning could never have satisfied it anyway.
 */
@Slf4j
public class BatchCleanupService {

    private final int cleanupAfterHours;

    public BatchCleanupService(int cleanupAfterHours) {
        this.cleanupAfterHours = cleanupAfterHours;
    }

    /**
     * Runs every hour to clean up expired job data
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void cleanupExpiredJobData() {
        log.debug("Running scheduled cleanup of batch job data");
        BatchUtility.cleanupOldJobData(cleanupAfterHours);
        log.debug("Cleanup completed. {}", BatchUtility.getCacheStats());
    }
}