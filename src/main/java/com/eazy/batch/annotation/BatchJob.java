package com.eazy.batch.annotation;

import com.eazy.batch.enums.FileType;
import com.eazy.batch.enums.ReaderType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a class as a Batch Job configuration.
 * The annotation processor will generate the required Spring Batch beans.
 *
 * Usage:
 * <pre>
 * {@code
 * @Component
 * @BatchJob(
 *     jobName = "myJob",
 *     stepName = "myStep",
 *     dtoClass = MyDTO.class,
 *     wrapperClass = MyWrapper.class
 * )
 * public class MyJobConfig implements SimpleBatchProcessor<MyDTO, MyWrapper> {
 *     // Implementation
 * }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BatchJob {

    /**
     * Unique name for the batch job
     * This will be the Spring bean name for the Job
     */
    String jobName();

    /**
     * Unique name for the step
     * This will be used as prefix for all generated beans
     */
    String stepName();

    /**
     * Display name for the batch (optional)
     * Used in logging and monitoring
     */
    String batchName() default "";

    /**
     * DTO class to be processed
     * This is the input type for the batch job
     */
    Class<?> dtoClass();

    /**
     * Wrapper class for processed items
     * This is the output type after processing
     */
    Class<?> wrapperClass();

    /**
     * Chunk size for batch processing
     * Default: -1, which means "use eazy.batch.default-chunk-size from
     * application properties" (itself defaulting to 100). Set an explicit
     * positive value here to override the property for this specific job.
     */
    int chunkSize() default -1;

    /**
     * Skip limit for failed items
     * Default: -1, which means "use eazy.batch.default-skip-limit from
     * application properties" (itself defaulting to 10). Set an explicit
     * positive value here to override the property for this specific job.
     */
    int skipLimit() default -1;

    /**
     * File type for input (Excel, CSV)
     * Default is EXCEL
     */
    FileType fileType() default FileType.EXCEL;

    /**
     * Reader type (FILE, DATABASE)
     * Default is FILE
     */
    ReaderType readerType() default ReaderType.FILE;

    /**
     * Enable multi-threaded step execution (each chunk processed on its own
     * thread from the shared batchTaskExecutor pool).
     * <p>NOTE: the reader is automatically wrapped in Spring Batch's
     * {@code SynchronizedItemStreamReader}, since the built-in CSV/Excel
     * readers hold internal state and are not thread-safe on their own.
     * Default is false.
     */
    boolean parallelProcessing() default false;

    /**
     * Documentation-only for now: concurrency is actually controlled by the
     * shared {@code batchTaskExecutor} bean's pool size
     * (eazy.batch.thread-pool-size), not by this value. Only used if
     * parallelProcessing = true.
     */
    int threadPoolSize() default 4;

    /**
     * Enable retry logic
     * Default is false
     */
    boolean enableRetry() default false;

    /**
     * Retry limit for failed items
     * Only used if enableRetry = true
     */
    int retryLimit() default 3;

    /**
     * Retryable exceptions (fully qualified class names)
     * Only used if enableRetry = true
     */
    String[] retryableExceptions() default {};

    /**
     * Enable email notifications
     * Default is false
     */
    boolean notifyOnCompletion() default false;

    /**
     * Enable email notifications on failure
     * Default is false
     */
    boolean notifyOnFailure() default false;

    /**
     * Email recipients for notifications
     * Only used if notifyOnCompletion or notifyOnFailure = true
     */
    String[] recipients() default {};

    /**
     * Excel sheet name to read (for multi-sheet support)
     * Default is empty (reads first sheet)
     */
    String sheetName() default "";

    /**
     * Excel sheet index to read (0-based)
     * Default is 0
     * Only used if sheetName is empty
     */
    int sheetIndex() default 0;

    /**
     * Required job parameters - the Job will refuse to launch (throwing
     * JobParametersInvalidException) if any of these keys are missing at
     * launch time, enforced via a generated DefaultJobParametersValidator.
     * Defaults to {"filePath"} since the built-in file readers require it.
     */
    String[] requiredParameters() default {"filePath"};

    /**
     * Optional job parameters. Combined with requiredParameters() to build
     * the full accepted parameter set - any parameter NOT listed in either
     * array will also cause launch to fail (this is DefaultJobParametersValidator's
     * standard behavior: only known keys are ever accepted).
     */
    String[] optionalParameters() default {};

    /**
     * Enable partitioning for large datasets.
     * <p><b>Not implemented yet</b> - setting this to true fails compilation
     * with a clear error rather than silently doing nothing. See README
     * "Known limitations".
     * Default is false
     */
    boolean partitioned() default false;

    /**
     * Number of partitions
     * Only used if partitioned = true
     */
    int partitions() default 4;

    /**
     * Enable incremental processing (resume from last checkpoint).
     * <p><b>Not implemented yet</b> - setting this to true fails compilation
     * with a clear error rather than silently doing nothing. See README
     * "Known limitations". (Note: restart-from-last-committed-chunk within a
     * single failed run IS supported now via ItemStream on the file readers -
     * this attribute is about a different feature, incremental extraction
     * keyed off a checkpoint column across separate runs.)
     * Default is false
     */
    boolean incremental() default false;

    /**
     * Checkpoint column for incremental processing
     * Only used if incremental = true
     */
    String checkpointColumn() default "id";

    /**
     * Dry run mode - validate but don't persist
     * Default is false
     */
    boolean dryRun() default false;

    /**
     * NEW: Cache Jakarta Bean Validation results within a job run, keyed by
     * the DTO's toString(). Real-world files (Excel/CSV exports from other
     * systems) very often contain repeated rows/lookup values, and
     * re-running reflection-based validator.validate() on identical content
     * is wasted work. Enabled by default; disable if your DTO's toString()
     * doesn't reflect its full field content (e.g. a custom/partial
     * toString()), since that would make the cache key unreliable.
     */
    boolean cacheValidation() default true;
}