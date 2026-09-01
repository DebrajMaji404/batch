package com.eazy.batch.service;

import java.io.InputStream;

/**
 * Strategy interface for saving exported files to a storage destination.
 *
 * <p>Only {@code LocalExportStorageService} (disk) ships built in. For anything
 * else - S3, Firebase Storage, GCS, FTP, etc. - implement this interface
 * yourself and register it as a {@code @Bean("customExportStorage")}; see
 * {@link com.eazy.batch.enums.StorageType#CUSTOM}.</p>
 *
 * <p>Whatever implementation is used, it returns a URL/path string after
 * saving, which is then passed to {@code SimpleExportProcessor.onSaveComplete(String url)}.</p>
 */
public interface ExportStorageService {

    /**
     * Save the file and return the accessible URL or path.
     *
     * @param inputStream File content as stream
     * @param fileName    Full file name including extension, e.g. "employees_20240101.xlsx"
     * @param contentType MIME type, e.g. "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
     * @return URL or path where the file was saved (e.g. an absolute file path for the
     *         built-in LOCAL implementation, or whatever URL scheme your own CUSTOM
     *         implementation returns)
     * @throws Exception if saving fails
     */
    String save(InputStream inputStream, String fileName, String contentType) throws Exception;
}