package com.eazy.batch.autoconfigure;

import com.eazy.batch.listener.JobCompletionListener;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.sql.DataSource;

/**
 * Auto-configuration for batch processor starter
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(EnableBatchProcessing.class)
@ConditionalOnProperty(prefix = "eazy.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BatchProcessorProperties.class)
@EnableBatchProcessing
public class BatchProcessorAutoConfiguration {

    private final BatchProcessorProperties properties;

    public BatchProcessorAutoConfiguration(BatchProcessorProperties properties) {
        this.properties = properties;
        log.info("✅ Batch Processor Auto-Configuration initialized with properties: {}", properties);
    }



    /**
     * Smart batch table initializer - only creates tables if they don't exist
     */
    @Bean
    public CommandLineRunner initializeBatchTables(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                // Check if batch tables already exist using pg_tables
                String checkTableQuery =
                        "SELECT COUNT(*) FROM pg_tables " +
                                "WHERE schemaname = 'public' AND tablename = 'batch_job_instance'";

                Integer count = jdbcTemplate.queryForObject(checkTableQuery, Integer.class);

                if (count != null && count > 0) {
                    log.info("Spring Batch tables already exist. Skipping initialization.");
                    return;
                }

                log.info("Spring Batch tables not found. Creating tables...");

                ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
                populator.addScript(new ClassPathResource("schema-postgresql.sql"));
                populator.setContinueOnError(false);
                populator.execute(dataSource);

                log.info("Spring Batch tables created successfully!");

            } catch (Exception e) {
                log.error("Error during batch table initialization: ", e);
                throw new RuntimeException("Failed to initialize batch tables", e);
            }
        };
    }


    /**
     * Task executor for batch jobs
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
        log.info("✅ Batch Task Executor configured with pool size: {}", properties.getThreadPoolSize());
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
     * Users can override this by creating their own bean with @Primary
     */
    @Bean
    @ConditionalOnMissingBean(JobCompletionListener.class)
    public JobCompletionListener jobCompletionListener() {
        log.info("✅ Using default JobCompletionListener. Override by creating your own @Bean with @Primary");
        return new JobCompletionListener();
    }

    /**
     * Validator bean for DTO validation
     * Users can override this by creating their own bean
     */
    @Bean
    @ConditionalOnMissingBean(Validator.class)
    public Validator validator() {
        log.info("✅ Using default Validator bean");
        log.info("✅ Creating default Validator bean");
        LocalValidatorFactoryBean validatorFactory = new LocalValidatorFactoryBean();
        validatorFactory.afterPropertiesSet();
        return validatorFactory;
    }
}