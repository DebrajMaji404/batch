package com.eazy.batch.listener;

import com.eazy.batch.dto.BatchProgressMessage;
import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.service.BatchWebSocketNotifier;
import com.eazy.batch.service.MetricsService;
import com.eazy.batch.utility.BatchUtility;
import com.eazy.batch.utility.ErrorReportExcelGenerator;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

/**
 * Job completion listener to log job execution details.
 * Registered exclusively via BatchProcessorAutoConfiguration#jobCompletionListener
 * and attached directly to every generated Job - intentionally NOT annotated
 * with @Component; see MetricsService for why.
 * FIXED: Execution time calculation now works properly
 * FIXED: now records metrics via MetricsService when it's active
 * NEW: now pushes a final COMPLETED/FAILED message over WebSocket, with a
 * base64-encoded error-report Excel attached whenever items were skipped.
 */
@Slf4j
public class JobCompletionListener implements JobExecutionListener {

    private final MetricsService metricsService;
    private final BatchWebSocketNotifier webSocketNotifier;

    public JobCompletionListener(MetricsService metricsService, BatchWebSocketNotifier webSocketNotifier) {
        this.metricsService = metricsService;
        this.webSocketNotifier = webSocketNotifier;
    }

    @Override
    public void beforeJob(@NotNull JobExecution jobExecution) {
        log.info("════════════════════════════════════════════════════════════");
        log.info("🚀 Starting Job: {}", jobExecution.getJobInstance().getJobName());
        log.info("🆔 Job Execution ID: {}", jobExecution.getId());
        log.info("📋 Job Parameters: {}", jobExecution.getJobParameters());
        log.info("⏰ Start Time: {}", LocalDateTime.now());
        log.info("════════════════════════════════════════════════════════════");

        BatchUtility.clearSkippedItems();
    }

    @Override
    public void afterJob(@NotNull JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        BatchStatus status = jobExecution.getStatus();

        // Calculate execution time - FIXED
        Duration duration = Duration.ZERO;
        if (jobExecution.getStartTime() != null && jobExecution.getEndTime() != null) {
            duration = Duration.between(
                    jobExecution.getStartTime().toInstant(ZoneOffset.UTC),
                    jobExecution.getEndTime().toInstant(ZoneOffset.UTC)
            );
        }

        log.info("════════════════════════════════════════════════════════════");

        // Status-specific logging
        if (status == BatchStatus.COMPLETED) {
            log.info("✅ Job '{}' completed successfully", jobName);
            metricsService.recordJobSuccess(jobName);
        } else if (status == BatchStatus.FAILED) {
            log.error("❌ Job '{}' failed", jobName);
            metricsService.recordJobFailure(jobName);
            if (jobExecution.getAllFailureExceptions() != null &&
                    !jobExecution.getAllFailureExceptions().isEmpty()) {
                log.error("Failure reasons:");
                jobExecution.getAllFailureExceptions().forEach(throwable ->
                        log.error("  - {}", throwable.getMessage())
                );
            }
        } else if (status == BatchStatus.STOPPED) {
            log.warn("⏸️ Job '{}' was stopped", jobName);
        } else {
            log.info("Job '{}' ended with status: {}", jobName, status);
        }

        metricsService.recordJobDuration(jobName, duration);

        // Execution statistics
        log.info("📊 Execution Statistics:");
        log.info("  ⏱️  Duration: {}", BatchUtility.formatDuration(duration));
        log.info("  📝 Read Count: {}", jobExecution.getStepExecutions().stream()
                .mapToLong(se -> se.getReadCount())
                .sum());
        log.info("  ✍️  Write Count: {}", jobExecution.getStepExecutions().stream()
                .mapToLong(se -> se.getWriteCount())
                .sum());
        log.info("  ⚠️  Skip Count: {}", jobExecution.getStepExecutions().stream()
                .mapToLong(se -> se.getSkipCount())
                .sum());

        // Log skipped items
        List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems();
        if (!skipped.isEmpty()) {
            log.warn("⚠️ Job '{}' had {} skipped items:", jobName, skipped.size());

            // Group by phase
            long readSkips = skipped.stream().filter(s -> "READ".equals(s.getPhase())).count();
            long processSkips = skipped.stream().filter(s -> "PROCESS".equals(s.getPhase())).count();
            long writeSkips = skipped.stream().filter(s -> "WRITE".equals(s.getPhase())).count();

            log.warn("  📖 READ phase: {} items", readSkips);
            log.warn("  ⚙️ PROCESS phase: {} items", processSkips);
            log.warn("  💾 WRITE phase: {} items", writeSkips);

            // Log first 10 skipped items details
            skipped.stream().limit(10).forEach(item ->
                    log.warn("  - [{}] {}", item.getPhase(), item.getReason())
            );

            if (skipped.size() > 10) {
                log.warn("  ... and {} more items", skipped.size() - 10);
            }
        }

        log.info("🏁 End Time: {}", LocalDateTime.now());
        log.info("════════════════════════════════════════════════════════════");

        // Cache stats
        log.debug("Cache Stats: {}", BatchUtility.getCacheStats());

        // NEW: final WebSocket push - completion/failure status plus, if any
        // rows were skipped, a base64-encoded Excel error report built from
        // the same `skipped` list just logged above.
        sendFinalWebSocketMessage(jobExecution, jobName, status, duration, skipped);

        // Clear skipped items for this job
        BatchUtility.clearSkippedItems();
    }

    private void sendFinalWebSocketMessage(JobExecution jobExecution, String jobName, BatchStatus status,
                                            Duration duration, List<BatchSkippedItem<?>> skipped) {
        long readCount = jobExecution.getStepExecutions().stream().mapToLong(se -> se.getReadCount()).sum();
        long writeCount = jobExecution.getStepExecutions().stream().mapToLong(se -> se.getWriteCount()).sum();
        long skipCount = jobExecution.getStepExecutions().stream().mapToLong(se -> se.getSkipCount()).sum();

        BatchProgressMessage.BatchProgressMessageBuilder builder = BatchProgressMessage.builder()
                .type(status == BatchStatus.FAILED ? BatchProgressMessage.Type.FAILED : BatchProgressMessage.Type.COMPLETED)
                .jobExecutionId(jobExecution.getId())
                .jobName(jobName)
                .readCount(readCount)
                .writeCount(writeCount)
                .skipCount(skipCount)
                .durationMs(duration.toMillis());

        if (!skipped.isEmpty()) {
            byte[] excelBytes = ErrorReportExcelGenerator.generate(skipped);
            if (excelBytes != null) {
                builder.errorFileName(jobName + "_errors.xlsx")
                        .errorFileBase64(Base64.getEncoder().encodeToString(excelBytes))
                        .errorFileSizeBytes(excelBytes.length);
            }
        }

        webSocketNotifier.send(jobExecution.getId(), builder.build());
    }
}