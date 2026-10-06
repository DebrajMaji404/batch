package com.eazy.batch.listener;

import com.eazy.batch.dto.BatchProgressMessage;
import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.report.BatchReportService;
import com.eazy.batch.report.BatchRowTracker;
import com.eazy.batch.service.BatchContext;
import com.eazy.batch.service.BatchRunRegistry;
import com.eazy.batch.service.BatchWebSocketNotifier;
import com.eazy.batch.service.MetricsService;
import com.eazy.batch.utility.BatchUtility;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final BatchReportService reportService;

    /** Reports are embedded as base64 (no {@code BatchReportStorage}). */
    public JobCompletionListener(MetricsService metricsService, BatchWebSocketNotifier webSocketNotifier) {
        this(metricsService, webSocketNotifier, new BatchReportService(null));
    }

    public JobCompletionListener(MetricsService metricsService, BatchWebSocketNotifier webSocketNotifier,
                                 BatchReportService reportService) {
        this.metricsService = metricsService;
        this.webSocketNotifier = webSocketNotifier;
        this.reportService = reportService;
    }

    @Override
    public void beforeJob(@NotNull JobExecution jobExecution) {
        log.info("════════════════════════════════════════════════════════════");
        log.info("🚀 Starting Job: {}", jobExecution.getJobInstance().getJobName());
        log.info("🆔 Job Execution ID: {}", jobExecution.getId());
        log.info("📋 Job Parameters: {}", jobExecution.getJobParameters());
        log.info("⏰ Start Time: {}", LocalDateTime.now());
        log.info("════════════════════════════════════════════════════════════");

        // FIXED: same context-lookup bug as afterJob() below - the no-arg
        // overload depends on a step context that hasn't been registered
        // yet this early (beforeJob fires before any step starts), so this
        // could never actually clear anything. jobExecution.getId() is
        // right here as a parameter; no context lookup needed.
        BatchUtility.clearSkippedItems(jobExecution.getId());
        BatchRowTracker.clear(jobExecution.getId());
        BatchRunRegistry.started(jobExecution);
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
        // FIXED: was calling the no-arg, step-context-based getSkippedItems(),
        // which can never succeed here - afterJob() runs after the step's
        // StepSynchronizationManager context has already been torn down, so
        // this always logged the WARN "No job execution ID available" and
        // silently returned an empty list, regardless of whether anything
        // was actually skipped. Use the explicit jobExecutionId overload,
        // which is already right here as a method parameter.
        List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems(jobExecution.getId());
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
        try {
            sendFinalWebSocketMessage(jobExecution, jobName, status, duration, skipped);
        } finally {
            BatchRunRegistry.finished(jobExecution.getId());
            com.eazy.batch.utility.BatchUniqueKeys.clear(jobExecution.getId());
            deleteUploadedFileIfRequested(jobExecution);
        }

        // Clear skipped items for this job
        // FIXED: same bug as above - the no-arg overload could never find
        // this job's cache entries from here, so they were never actually
        // cleared (a slow cache leak, one stale entry per completed job
        // that had skips, until the Caffeine cache's own TTL/size eviction
        // eventually caught up).
        BatchUtility.clearSkippedItems(jobExecution.getId());
        BatchRowTracker.clear(jobExecution.getId());
    }

    /**
     * Removes the uploaded file once the report (which re-reads it) has been built, when the
     * run was started with {@code deleteFileAfterJob=true} (the built-in upload endpoint does
     * this by default, see {@code eazy.batch.upload.delete-after-job}).
     */
    private void deleteUploadedFileIfRequested(JobExecution jobExecution) {
        try {
            if (!"true".equalsIgnoreCase(jobExecution.getJobParameters().getString(BatchContext.P_DELETE_FILE))) return;
            String path = jobExecution.getJobParameters().getString(BatchContext.P_FILE_PATH);
            if (path != null && java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path))) {
                log.info("Deleted uploaded file {}", path);
            }
        } catch (Exception e) {
            log.warn("Could not delete uploaded file for job execution {}: {}", jobExecution.getId(), e.getMessage());
        }
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

        // NEW: surface WHY a job failed. Not every failure produces skipped
        // rows - a template/header mismatch, missing file, or bad query fails
        // the job before or outside item processing, leaving skipCount at 0
        // and no error report to attach. Previously those reached the client
        // as a bare FAILED with everything else zero/null, so the user had no
        // idea what went wrong.
        if (status == BatchStatus.FAILED) {
            builder.failureMessage(failureCause(jobExecution, status));
        }

        // The report (ERRORS or ALL, per @BatchJob.reportType). A job that did
        // not complete always gets one, even with no row-level skips (header
        // mismatch, missing file, infrastructure failure...): it then holds a
        // single row explaining why. Report problems never block this message.
        String cause = status == BatchStatus.COMPLETED ? null : failureCause(jobExecution, status);
        BatchReportService.PublishedReport report = reportService.publish(jobExecution, skipped, cause);
        if (report != null) {
            builder.errorFileName(report.fileName())
                    .errorFileUrl(report.url())
                    .errorFileBase64(report.base64())
                    .errorFileSizeBytes(report.sizeBytes())
                    .reportType(report.reportType())
                    .reportSuccessRows(report.successRows())
                    .reportFailedRows(report.failedRows())
                    .reportNotImportedRows(report.notImportedRows());
        }

        webSocketNotifier.send(jobExecution.getId(),
                jobExecution.getJobParameters().getString(BatchContext.P_USERNAME), builder.build());
    }

    /**
     * Why a job that did not complete stopped, in one line.
     *
     * <p>Usually the root-cause message. When the job died of
     * {@code SkipLimitExceededException} that is not enough on its own - its
     * root cause is merely the LAST row failure - so the skip-limit message
     * leads: {@code Skip limit of '5' exceeded - last failure: ...}.</p>
     */
    private String failureCause(JobExecution jobExecution, BatchStatus status) {
        List<Throwable> failures = jobExecution.getAllFailureExceptions();
        if (failures == null || failures.isEmpty()) {
            return "job ended with status " + status;
        }

        String root = rootCauseMessage(jobExecution);
        int depth = 0;
        for (Throwable t = failures.get(0); t != null && depth++ < 20; t = (t.getCause() == t ? null : t.getCause())) {
            if ("SkipLimitExceededException".equals(t.getClass().getSimpleName()) && t.getMessage() != null) {
                return t.getMessage().equals(root) ? root : t.getMessage() + " - last failure: " + root;
            }
        }
        return root;
    }

    /**
     * Digs out the most specific message available for a failed job.
     *
     * <p>Spring wraps the real cause several layers deep - a template
     * validation failure arrives as BeanCreationException -&gt;
     * BeanInstantiationException -&gt; InvalidTemplateException, and only the
     * innermost one carries the message a user can act on ("Missing headers:
     * [Description]. Extra headers: [Descriptionaaa]."). Walking to the root
     * cause gets that instead of the noisy wrapper text.</p>
     */
    private String rootCauseMessage(JobExecution jobExecution) {
        List<Throwable> failures = jobExecution.getAllFailureExceptions();
        if (failures == null || failures.isEmpty()) {
            return "Job failed with no recorded exception";
        }

        Throwable root = failures.get(0);
        // Guard against a self-referencing/cyclic cause chain.
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 20) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return message != null && !message.isBlank()
                ? message
                : root.getClass().getSimpleName();
    }
}