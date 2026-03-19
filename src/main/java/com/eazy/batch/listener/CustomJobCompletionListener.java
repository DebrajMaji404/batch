package com.eazy.batch.listener;


import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * Interface for custom job completion listeners.
 * Extend this interface to create your own custom listener logic.
 */
public interface CustomJobCompletionListener extends JobExecutionListener {

    /**
     * Called before the job starts
     * Override this to add custom pre-job logic
     */
    @Override
    default void beforeJob(JobExecution jobExecution) {
        // Default implementation - can be overridden
    }

    /**
     * Called after the job completes
     * Override this to add custom post-job logic
     */
    @Override
    default void afterJob(JobExecution jobExecution) {
        // Default implementation - can be overridden
    }
}