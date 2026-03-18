package com.eazy.batch.autoconfigure;

import com.eazy.batch.listener.JobCompletionListener;
import com.eazy.batch.service.BatchCleanupService;
import com.eazy.batch.service.EmailNotificationService;
import com.eazy.batch.service.MetricsService;
import com.eazy.batch.service.ProgressTrackingService;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
public class BatchProcessorAutoConfiguration {

    private final BatchProcessorProperties properties;

    public BatchProcessorAutoConfiguration(BatchProcessorProperties properties) {
        this.properties = properties;
        log.info("✅ Batch Processor Auto-Configuration initialized");
        log.info("📋 Configuration: {}", properties);
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
     * Default Job completion listener
     */
    @Bean
    @ConditionalOnMissingBean(JobCompletionListener.class)
    public JobCompletionListener jobCompletionListener() {
        log.info("✅ Default JobCompletionListener registered");
        return new JobCompletionListener();
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
     * Metrics service for monitoring
     */
    @Bean
    @ConditionalOnProperty(prefix = "eazy.batch", name = "metrics-enabled", havingValue = "true")
    public MetricsService metricsService() {
        log.info("✅ Metrics Service enabled");
        return new MetricsService();
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
     * Email notification service
     */
    @Bean
    @ConditionalOnProperty(prefix = "eazy.batch", name = "email-notifications-enabled", havingValue = "true")
    public EmailNotificationService emailNotificationService() {
        log.info("✅ Email Notification Service enabled (SMTP: {}:{})",
                properties.getSmtpHost(),
                properties.getSmtpPort());
        return new EmailNotificationService(properties);
    }
}