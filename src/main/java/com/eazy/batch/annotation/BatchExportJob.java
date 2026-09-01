package com.eazy.batch.annotation;

import com.eazy.batch.enums.ExportFileType;
import com.eazy.batch.enums.StorageType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a class as a Batch Export Job.
 * The annotation processor auto-generates all Spring Batch beans for the export.
 *
 * <h3>LOCAL storage (built-in):</h3>
 * <pre>{@code
 * @BatchExportJob(
 *     jobName        = "exportEmployeeJob",
 *     stepName       = "exportEmployeeStep",
 *     entityClass    = Employee.class,
 *     storageType    = StorageType.LOCAL,
 *     localDirectory = "/var/exports",
 *     fileName       = "employees"
 * )
 * }</pre>
 *
 * <h3>CUSTOM storage (S3, Firebase, GCS, etc.):</h3>
 * <pre>{@code
 * // 1. Register your own storage bean:
 * @Bean("customExportStorage")
 * public ExportStorageService myStorage() {
 *     return (inputStream, fileName, contentType) -> {
 *         // upload anywhere, return the URL
 *         s3Client.upload(fileName, inputStream);
 *         return "https://s3.amazonaws.com/my-bucket/" + fileName;
 *     };
 * }
 *
 * // 2. Use CUSTOM in the annotation:
 * @BatchExportJob(
 *     jobName     = "exportEmployeeJob",
 *     stepName    = "exportEmployeeStep",
 *     entityClass = Employee.class,
 *     storageType = StorageType.CUSTOM,
 *     fileName    = "employees"
 * )
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BatchExportJob {

    /** Unique name for the Spring Batch Job bean */
    String jobName();

    /** Unique name for the Step — used as prefix for all generated beans */
    String stepName();

    /** Display name for logging and monitoring (optional) */
    String exportName() default "";

    /**
     * JPA entity class being exported.
     * This is what JpaCursorItemReader reads from the database.
     */
    Class<?> entityClass();

    /**
     * Where to save the output file.
     *
     * <ul>
     *   <li>{@code LOCAL}  — built-in, saves to disk, no setup needed</li>
     *   <li>{@code CUSTOM} — you provide a {@code @Bean("customExportStorage")}
     *       that implements {@code ExportStorageService}</li>
     * </ul>
     */
    StorageType storageType() default StorageType.LOCAL;

    /**
     * Base name for the output file (no extension).
     * A timestamp is appended automatically, e.g. {@code employees_20240101_120000.xlsx}
     */
    String fileName() default "export";

    /** Output file format: EXCEL (.xlsx) or CSV */
    ExportFileType fileType() default ExportFileType.EXCEL;

    /**
     * Excel sheet name (for EXCEL format only).
     * Defaults to the {@code fileName} value if not set.
     */
    String sheetName() default "";

    /** Number of rows to process per chunk */
    int chunkSize() default 500;

    /** How many errors to tolerate before the job fails */
    int skipLimit() default 10;

    /**
     * Local directory path for saving the file.
     * Only used when {@code storageType = StorageType.LOCAL}.
     * Defaults to the system temp directory if not set.
     */
    String localDirectory() default "";

    /**
     * Run job in background thread (recommended).
     * Controller returns immediately; use {@code onSaveComplete()} to get notified when done.
     */
    boolean async() default true;

    /**
     * NEW: Dry run mode - read and validate rows but skip building/uploading
     * the actual output file. Useful for verifying a JPQL query and column
     * mappings against real data without producing a file. Default is false.
     */
    boolean dryRun() default false;
}