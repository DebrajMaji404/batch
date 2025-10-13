package com.eazy.batch.listener;

import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.utility.BatchUtility;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Job completion listener to log job execution details
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
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            log.info("Job {} completed successfully", jobExecution.getJobInstance().getJobName());
        } else if (jobExecution.getStatus() == BatchStatus.FAILED) {
            log.error("Job {} failed", jobExecution.getJobInstance().getJobName());
        }

        // Log skipped items
        List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems();
        if (!skipped.isEmpty()) {
            log.warn("Job {} had {} skipped items",
                    jobExecution.getJobInstance().getJobName(),
                    skipped.size());

            skipped.forEach(item ->
                    log.warn("Skipped in {}: {}", item.getPhase(), item.getReason())
            );
        }

        log.info("Job {} execution time:  ms",
                jobExecution.getJobInstance().getJobName()
//                jobExecution.getEndTime().getTime() - jobExecution.getStartTime().getTime()
);

        BatchUtility.clearSkippedItems();
    }
}