package com.eazy.batch.autoconfigure;

import com.eazy.batch.report.BatchReportStorage;
import com.eazy.batch.report.LocalReportStorage;
import com.eazy.batch.web.BatchApiController;
import com.eazy.batch.web.ReportDownloadController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Optional web layer: the built-in upload/status/stop/template endpoints
 * ({@code eazy.batch.api.enabled=true}) and local report storage with its download endpoint
 * ({@code eazy.batch.report.local-enabled=true}).
 */
@Slf4j
@AutoConfiguration
@AutoConfigureBefore(BatchProcessorAutoConfiguration.class)
@EnableConfigurationProperties(BatchProcessorProperties.class)
@ConditionalOnProperty(prefix = "eazy.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BatchWebAutoConfiguration {

    /** Registered before the main configuration so {@code BatchReportService} finds it as its storage. */
    @Bean
    @ConditionalOnMissingBean(BatchReportStorage.class)
    @ConditionalOnProperty(prefix = "eazy.batch.report", name = "local-enabled", havingValue = "true")
    public LocalReportStorage localReportStorage(BatchProcessorProperties properties) {
        String base = properties.getReport().getPublicBaseUrl();
        base = base == null ? "" : base.replaceAll("/+$", "");
        String prefix = base + properties.getApi().getBasePath() + "/reports";
        log.info("✅ Local report storage enabled (urls like {}/{{jobExecutionId}}/{{file}})", prefix);
        return new LocalReportStorage(properties.getReport().getDirectory(), prefix);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "eazy.batch.api", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(BatchApiController.class)
    public BatchApiController batchApiController(ApplicationContext context, JobLauncher jobLauncher,
                                                BatchProcessorProperties properties) {
        log.info("✅ Batch REST API enabled at {}", properties.getApi().getBasePath());
        return new BatchApiController(context, jobLauncher, properties);
    }

    /** Nested so the Actuator classes are only loaded when Actuator is on the classpath. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class ActuatorConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public com.eazy.batch.web.BatchActuatorEndpoint eazyBatchEndpoint(BatchProcessorProperties properties) {
            return new com.eazy.batch.web.BatchActuatorEndpoint(properties);
        }
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBean(LocalReportStorage.class)
    @ConditionalOnMissingBean(ReportDownloadController.class)
    public ReportDownloadController reportDownloadController(LocalReportStorage storage) {
        return new ReportDownloadController(storage);
    }
}
