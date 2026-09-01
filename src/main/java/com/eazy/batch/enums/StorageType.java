package com.eazy.batch.enums;

/**
 * Defines where the exported file will be saved.
 *
 * <ul>
 *   <li><b>LOCAL</b>  — saved to local disk. URL = absolute file path.
 *       Built-in {@code LocalExportStorageService} is used automatically.</li>
 *   <li><b>CUSTOM</b> — you provide your own {@code ExportStorageService} bean
 *       (e.g. S3, Firebase, GCS, FTP, etc). Your service saves the file
 *       and returns any URL string you want passed to {@code onSaveComplete()}.</li>
 * </ul>
 */
public enum StorageType {

    /** Save to local disk. No extra setup needed. */
    LOCAL,

    /**
     * Use your own storage service.
     * Register a Spring bean that implements {@code ExportStorageService}
     * with the qualifier {@code "customExportStorage"}.
     *
     * <pre>{@code
     * @Bean("customExportStorage")
     * public ExportStorageService myStorage() {
     *     return new MyS3Service(...);       // or Firebase, GCS, FTP, etc.
     * }
     * }</pre>
     */
    CUSTOM
}