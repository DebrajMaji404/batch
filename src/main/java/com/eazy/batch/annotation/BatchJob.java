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
@Retention(RetentionPolicy.SOURCE)
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
     * Default is 100
     */
    int chunkSize() default 100;

    /**
     * Skip limit for failed items
     * Default is 10
     */
    int skipLimit() default 10;

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
     * Enable parallel processing
     * Default is false
     */
    boolean parallelProcessing() default false;

    /**
     * Thread pool size for parallel processing
     * Only used if parallelProcessing = true
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
     * Required job parameters
     * Job will fail if these are not provided
     */
    String[] requiredParameters() default {"filePath"};

    /**
     * Optional job parameters
     */
    String[] optionalParameters() default {};

    /**
     * Enable partitioning for large datasets
     * Default is false
     */
    boolean partitioned() default false;

    /**
     * Number of partitions
     * Only used if partitioned = true
     */
    int partitions() default 4;

    /**
     * Enable incremental processing (resume from last checkpoint)
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
}