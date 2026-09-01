package com.eazy.batch.listener;


import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * Interface for custom job completion listeners.
 *
 * <p><b>Known limitation:</b> implementing this interface and registering it
 * as a {@code @Component}/{@code @Bean} does NOT automatically attach it to
 * generated jobs. Every generated {@code Job} wires in exactly one concrete
 * {@link JobCompletionListener} bean (constructor-injected by type, not a
 * {@code List<JobExecutionListener>}), so an unrelated
 * {@code CustomJobCompletionListener} implementation is never picked up.
 * If you want custom completion logic, override {@link JobCompletionListener}
 * itself and replace the {@code jobCompletionListener} bean - see the
 * "Overriding the default JobCompletionListener" section in the README.</p>
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