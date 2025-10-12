package com.eazy.batch.autoconfigure;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for batch processor
 * Note: Do NOT add @Component here - it's registered via @EnableConfigurationProperties
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
}