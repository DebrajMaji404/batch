package com.eazy.batch.autoconfigure;

import com.eazy.batch.listener.BatchProgressChunkListener;
import com.eazy.batch.listener.JobCompletionListener;
import com.eazy.batch.service.BatchCleanupService;
import com.eazy.batch.service.BatchWebSocketNotifier;
import com.eazy.batch.service.EmailNotificationService;
import com.eazy.batch.service.ExportStorageService;
import com.eazy.batch.service.LocalExportStorageService;
import com.eazy.batch.service.MetricsService;
import com.eazy.batch.service.ProgressTrackingService;
import com.eazy.batch.utility.BatchUtility;
import com.eazy.batch.websocket.BatchWebSocketConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.sql.DataSource;

/**
 * Auto-configuration for batch processor starter
 * FIXED: Properties now properly read from application.properties
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(EnableBatchProcessing.class)
@ConditionalOnProperty(prefix = "eazy.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BatchProcessorProperties.class)
@EnableBatchProcessing
@EnableScheduling
@Import(BatchWebSocketConfig.class)
public class BatchProcessorAutoConfiguration {

    private final BatchProcessorProperties properties;

    public BatchProcessorAutoConfiguration(BatchProcessorProperties properties) {
        this.properties = properties;
        log.info("✅ Batch Processor Auto-Configuration initialized");
        log.info("📋 Configuration: {}", properties);

        // FIXED: cleanupAfterHours was previously ignored (TTL was hardcoded
        // to 24h in BatchUtility). Push the configured value into the cache.
        BatchUtility.configureCleanupTtl(properties.getCleanupAfterHours());
    }

    /**
     * Smart batch table initializer - only creates tables if they don't exist
     * FIXED: Better error handling and PostgreSQL-specific check
     */
    @Bean
    public CommandLineRunner initializeBatchTables(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                // Check if batch tables already exist
                String checkTableQuery =
                        "SELECT COUNT(*) FROM information_schema.tables " +
                                "WHERE table_schema = 'public' AND table_name = 'batch_job_instance'";

                Integer count = jdbcTemplate.queryForObject(checkTableQuery, Integer.class);

                if (count != null && count > 0) {
                    log.info("✅ Spring Batch tables already exist. Skipping initialization.");
                    return;
                }

                log.info("📦 Spring Batch tables not found. Creating tables...");

                ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
                populator.addScript(new ClassPathResource("schema-postgresql.sql"));
                populator.setContinueOnError(false);
                populator.execute(dataSource);

                log.info("✅ Spring Batch tables created successfully!");

            } catch (Exception e) {
                log.error("❌ Error during batch table initialization", e);
                throw new RuntimeException("Failed to initialize batch tables", e);
            }
        };
    }

    /**
     * Task executor for batch jobs
     * FIXED: Now reads from properties correctly
     */
    @Bean(name = "batchTaskExecutor")
    @ConditionalOnMissingBean(name = "batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getThreadPoolSize());
        executor.setMaxPoolSize(properties.getThreadPoolSize() * 2);
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("batch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("✅ Batch Task Executor configured: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                properties.getThreadPoolSize(),
                properties.getThreadPoolSize() * 2,
                properties.getQueueCapacity());
        return executor;
    }

    /**
     * Job launcher with task executor
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskExecutorJobLauncher jobLauncher(
            JobRepository jobRepository,
            @Qualifier("batchTaskExecutor") TaskExecutor taskExecutor) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(taskExecutor);
        launcher.afterPropertiesSet();
        log.info("✅ Job Launcher configured");
        return launcher;
    }

    /**
     * Default Job completion listener.
     * FIXED: now depends on MetricsService, which is therefore always
     * registered below (not conditional) so this wiring never fails
     * regardless of whether metrics are actually enabled.
     * NEW: also depends on BatchWebSocketNotifier for the final
     * COMPLETED/FAILED WebSocket push with the error-report Excel attached.
     */
    @Bean
    @ConditionalOnMissingBean(JobCompletionListener.class)
    public JobCompletionListener jobCompletionListener(MetricsService metricsService, BatchWebSocketNotifier webSocketNotifier) {
        log.info("✅ Default JobCompletionListener registered");
        return new JobCompletionListener(metricsService, webSocketNotifier);
    }

    /**
     * Validator bean for DTO validation
     */
    @Bean
    @ConditionalOnMissingBean(Validator.class)
    public Validator validator() {
        log.info("✅ Creating default Validator bean");
        LocalValidatorFactoryBean validatorFactory = new LocalValidatorFactoryBean();
        validatorFactory.afterPropertiesSet();
        return validatorFactory;
    }

    /**
     * Batch cleanup service - periodically cleans old job data
     * FIXED: Prevents memory leaks
     */
    @Bean
    @ConditionalOnProperty(prefix = "eazy.batch", name = "enabled", havingValue = "true")
    public BatchCleanupService batchCleanupService() {
        log.info("✅ Batch Cleanup Service configured (cleanup after {} hours)",
                properties.getCleanupAfterHours());
        return new BatchCleanupService(properties.getCleanupAfterHours());
    }

    /**
     * Metrics service for monitoring.
     * FIXED: now always registered (JobCompletionListener and generated
     * per-job code depend on it unconditionally), with metrics-enabled
     * governing whether it actually records anything internally, and
     * MeterRegistry now properly injected via ObjectProvider (previously
     * always null - it was constructed with `new MetricsService()`).
     */
    @Bean
    @ConditionalOnMissingBean(MetricsService.class)
    public MetricsService metricsService(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        boolean enabled = properties.isMetricsEnabled();
        log.info("✅ Metrics Service registered (enabled={})", enabled);
        return new MetricsService(meterRegistryProvider.getIfAvailable(), enabled);
    }

    /**
     * Progress tracking service
     */
    @Bean
    @ConditionalOnProperty(prefix = "eazy.batch", name = "progress-tracking-enabled", havingValue = "true", matchIfMissing = true)
    public ProgressTrackingService progressTrackingService() {
        log.info("✅ Progress Tracking Service enabled (update interval: {} items)",
                properties.getProgressUpdateInterval());
        return new ProgressTrackingService(properties.getProgressUpdateInterval());
    }

    /**
     * Email notification service.
     * FIXED: now always registered (a generated per-job NotificationListener
     * can depend on it whenever notifyOnCompletion/notifyOnFailure is set on
     * @BatchJob), with email-notifications-enabled governing whether it
     * actually sends anything internally.
     */
    @Bean
    @ConditionalOnMissingBean(EmailNotificationService.class)
    public EmailNotificationService emailNotificationService() {
        log.info("✅ Email Notification Service registered (enabled={}, SMTP: {}:{})",
                properties.isEmailNotificationsEnabled(),
                properties.getSmtpHost(),
                properties.getSmtpPort());
        return new EmailNotificationService(properties);
    }

    /**
     * Local export storage - the built-in target for
     * @BatchExportJob(storageType = StorageType.LOCAL).
     * FIXED: previously a component-scanned @Service with only a no-arg
     * constructor, so eazy.batch.export.local-directory had no effect and
     * every LOCAL export always went to the system temp directory.
     */
    @Bean("localExportStorage")
    @ConditionalOnMissingBean(name = "localExportStorage")
    public ExportStorageService localExportStorage() {
        String dir = properties.getExportLocalDirectory();
        log.info("✅ Local export storage registered (directory={})",
                (dir == null || dir.isBlank()) ? "<system temp dir>" : dir);
        return new LocalExportStorageService(dir);
    }

    /**
     * NEW: pushes job progress + a final completion/failure message (with an
     * embedded error-report Excel when rows were skipped) over WebSocket.
     * Always registered - it's a thin wrapper around an optional
     * SimpMessagingTemplate and no-ops entirely when
     * eazy.batch.websocket-enabled=false or the STOMP infrastructure isn't
     * active, so nothing that depends on it needs a conditional.
     */
    @Bean
    @ConditionalOnMissingBean(BatchWebSocketNotifier.class)
    public BatchWebSocketNotifier batchWebSocketNotifier(ObjectProvider<SimpMessagingTemplate> templateProvider) {
        boolean enabled = properties.isWebsocketEnabled();
        log.info("✅ Batch WebSocket Notifier registered (enabled={}, topicPrefix={})",
                enabled, properties.getWebsocketTopicPrefix());
        return new BatchWebSocketNotifier(templateProvider.getIfAvailable(), enabled, properties.getWebsocketTopicPrefix());
    }

    /**
     * NEW: shared ChunkListener attached to every generated Step (both
     * @BatchJob and @BatchExportJob) that broadcasts a PROGRESS message
     * after each chunk. See BatchProgressChunkListener for details.
     */
    @Bean
    @ConditionalOnMissingBean(BatchProgressChunkListener.class)
    public BatchProgressChunkListener batchProgressChunkListener(BatchWebSocketNotifier webSocketNotifier) {
        return new BatchProgressChunkListener(webSocketNotifier);
    }
}