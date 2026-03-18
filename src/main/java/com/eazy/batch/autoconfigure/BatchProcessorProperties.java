package com.eazy.batch.autoconfigure;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for batch processor
 * FIXED: Properly bound to application.properties via @ConfigurationProperties
 */
@Data
@ToString
@ConfigurationProperties(prefix = "eazy.batch")
public class BatchProcessorProperties {

    /**
     * Thread pool size for batch processing
     */
    private int threadPoolSize = 5;

    /**
     * Queue capacity for thread pool
     */
    private int queueCapacity = 100;

    /**
     * Default chunk size for batch jobs
     */
    private int defaultChunkSize = 100;

    /**
     * Default skip limit for batch jobs
     */
    private int defaultSkipLimit = 10;

    /**
     * Enable batch processing
     */
    private boolean enabled = true;

    /**
     * Enable metrics collection
     */
    private boolean metricsEnabled = false;

    /**
     * Cleanup old job data after hours
     */
    private int cleanupAfterHours = 24;

    /**
     * Enable email notifications
     */
    private boolean emailNotificationsEnabled = false;

    /**
     * SMTP host for email notifications
     */
    private String smtpHost;

    /**
     * SMTP port for email notifications
     */
    private int smtpPort = 587;

    /**
     * SMTP username
     */
    private String smtpUsername;

    /**
     * SMTP password
     */
    private String smtpPassword;

    /**
     * From email address
     */
    private String fromEmail = "noreply@batch.com";

    /**
     * Enable retry logic globally
     */
    private boolean retryEnabled = false;

    /**
     * Default retry limit
     */
    private int defaultRetryLimit = 3;

    /**
     * Enable parallel processing globally
     */
    private boolean parallelProcessingEnabled = false;

    /**
     * Default thread pool size for parallel processing
     */
    private int defaultParallelThreads = 4;

    /**
     * Enable progress tracking
     */
    private boolean progressTrackingEnabled = true;

    /**
     * Progress update interval (in items)
     */
    private int progressUpdateInterval = 100;

    /**
     * Enable dry run mode globally
     */
    private boolean dryRunMode = false;
}