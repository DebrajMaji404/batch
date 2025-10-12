package com.eazy.batch.annotation;

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
}