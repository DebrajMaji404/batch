package com.eazy.batch.listener;

import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.utility.BatchUtility;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Job completion listener to log job execution details
 * FIXED: Execution time calculation now works properly
 */
@Slf4j
@Component
public class JobCompletionListener implements JobExecutionListener {

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

        // Calculate execution time - Spring Batch 5 returns LocalDateTime directly
        Duration duration = Duration.ZERO;
        if (jobExecution.getStartTime() != null && jobExecution.getEndTime() != null) {
            duration = Duration.between(
                    jobExecution.getStartTime(),
                    jobExecution.getEndTime()
            );
        }

        log.info("════════════════════════════════════════════════════════════");

        // Status-specific logging
        if (status == BatchStatus.COMPLETED) {
            log.info("✅ Job '{}' completed successfully", jobName);
        } else if (status == BatchStatus.FAILED) {
            log.error("❌ Job '{}' failed", jobName);
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

        // Clear skipped items for this job
        BatchUtility.clearSkippedItems();
    }
}