package com.eazy.batch.service;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;

/**
 * Who/what the current batch run is for, available anywhere on the batch
 * thread - most usefully inside your {@code process()}:
 *
 * <pre>
 * BatchContext ctx = BatchContext.current();
 * String tenant = ctx.getString("tenantId");   // any extra request param of the upload endpoint
 * String user   = ctx.username();
 * </pre>
 *
 * Returns an empty context (never null) outside a running step.
 */
public final class BatchContext {

    public static final String P_USERNAME = "username";
    public static final String P_DRY_RUN = "dryRun";
    public static final String P_FILE_PATH = "filePath";
    public static final String P_ORIGINAL_FILE_NAME = "originalFileName";
    public static final String P_TIMESTAMP = "timestamp";
    public static final String P_DELETE_FILE = "deleteFileAfterJob";

    private final JobExecution jobExecution;

    private BatchContext(JobExecution jobExecution) {
        this.jobExecution = jobExecution;
    }

    public static BatchContext current() {
        try {
            StepContext step = StepSynchronizationManager.getContext();
            if (step != null) return new BatchContext(step.getStepExecution().getJobExecution());
        } catch (RuntimeException ignored) {
            // no step bound to this thread
        }
        return new BatchContext(null);
    }

    public static BatchContext of(JobExecution jobExecution) {
        return new BatchContext(jobExecution);
    }

    public boolean isActive() {
        return jobExecution != null;
    }

    public Long jobExecutionId() {
        return jobExecution == null ? null : jobExecution.getId();
    }

    public String jobName() {
        return jobExecution == null ? null : jobExecution.getJobInstance().getJobName();
    }

    private JobParameters params() {
        return jobExecution == null ? null : jobExecution.getJobParameters();
    }

    public boolean has(String key) {
        return params() != null && params().getParameter(key) != null;
    }

    public String getString(String key) {
        return params() == null ? null : params().getString(key);
    }

    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : value;
    }

    public Long getLong(String key) {
        return params() == null ? null : params().getLong(key);
    }

    /** The user who started the run (the {@code username} job parameter), or null. */
    public String username() {
        return getString(P_USERNAME);
    }

    /** True when the run was started with {@code dryRun=true}: rows are validated, nothing is saved. */
    public boolean isDryRun() {
        return "true".equalsIgnoreCase(getString(P_DRY_RUN));
    }

    /** Static shortcut for generated code. */
    public static boolean dryRunRequested() {
        return current().isDryRun();
    }
}
