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

    /**
     * Default local directory for @BatchExportJob(storageType = LOCAL) output
     * when the job itself doesn't set localDirectory(). Falls back to the
     * system temp directory if left blank.
     */
    private String exportLocalDirectory = "";

    /**
     * Enable the built-in WebSocket (STOMP) progress + error-report push.
     * When true, a STOMP endpoint is registered at websocketEndpoint (default
     * "/ws-batch") and every job broadcasts progress after each chunk, plus
     * a final message on completion/failure - including a base64-encoded
     * Excel file of any skipped rows - to /topic/batch-progress/{jobExecutionId}.
     */
    private boolean websocketEnabled = true;

    /**
     * STOMP endpoint path clients connect to (with SockJS fallback enabled).
     */
    private String websocketEndpoint = "/ws-batch";

    /**
     * Destination prefix jobs broadcast to. The full destination for a given
     * run is "{websocketTopicPrefix}/{jobExecutionId}".
     */
    private String websocketTopicPrefix = "/topic/batch-progress";

    /**
     * Also push every progress/final message to the user who started the job
     * (job parameter "username"), on "/user{websocketUserQueue}". Clients
     * subscribe to one fixed destination - no job id needed.
     */
    private boolean websocketUserDestinationEnabled = true;

    /**
     * Per-user destination (relative to "/user"). Subscribe to
     * "/user/queue/batch-progress" to follow the jobs you started.
     */
    private String websocketUserQueue = "/queue/batch-progress";

    /**
     * Save job/step executions to the Spring Batch metadata tables
     * (BATCH_JOB_EXECUTION, ...). Off by default: Spring Batch 6 does not need
     * them to run jobs, and the Excel reports and live progress do not use them.
     * Turn on for run history, restart-from-failure and duplicate-launch protection.
     * Experimental: the library's schema script is applied for you on start-up.
     */
    private boolean persistJobMetadata = false;

    /**
     * Run each failed chunk's database writes in separate transactions
     * (REQUIRES_NEW) so one bad row cannot roll back its good neighbours.
     * Applies to every job; a single job can opt in with
     * {@code @BatchJob(rowIsolation = true)}. Needs two free connections per
     * writing thread.
     */
    private boolean rowIsolation = false;

    /** Built-in REST endpoints (upload, status, stop, template download). */
    private final Api api = new Api();

    /** Rules applied to uploaded files before a job is launched. */
    private final Upload upload = new Upload();

    /** Where finished reports go when the application has no BatchReportStorage bean. */
    private final Report report = new Report();

    @Data
    public static class Api {
        /**
         * Register the built-in controller: POST {basePath}/{jobName}/upload,
         * GET {basePath}/{jobName}/template, GET {basePath}/{jobExecutionId}/status,
         * POST {basePath}/{jobExecutionId}/stop. Secure these paths like any other
         * endpoint of your application.
         */
        private boolean enabled = false;

        /** Path prefix of the built-in endpoints. */
        private String basePath = "/batch";
    }

    @Data
    public static class Upload {
        /** Where uploaded files are stored for the job to read. Blank = system temp directory. */
        private String directory = "";

        /** Delete the uploaded file once the job - and its report - are finished. */
        private boolean deleteAfterJob = true;

        /** Largest accepted upload, in megabytes. 0 = unlimited. */
        private int maxFileSizeMb = 50;

        /** Most data rows accepted in one upload (header excluded). 0 = unlimited. */
        private int maxRows = 0;
    }

    @Data
    public static class Report {
        /**
         * Keep finished reports on local disk and serve them from
         * GET {basePath}/reports/{jobExecutionId}/{fileName}. Ignored when the
         * application defines its own BatchReportStorage bean.
         */
        private boolean localEnabled = false;

        /** Directory for local reports. Blank = system temp directory. */
        private String directory = "";

        /**
         * Scheme and host put in front of the download path so the client gets a
         * full URL, e.g. https://api.example.com. Blank = a relative path.
         */
        private String publicBaseUrl = "";
    }
}