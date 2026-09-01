package com.eazy.batch.service;

import java.io.InputStream;

/**
 * Strategy interface for saving exported files to a storage destination.
 *
 * <p>Three built-in implementations are provided:</p>
 * <ul>
 *   <li>{@code LocalExportStorageService}  — saves to local disk</li>
 *   <li>{@code S3ExportStorageService}     — uploads to AWS S3</li>
 *   <li>{@code FirebaseExportStorageService} — uploads to Firebase Storage</li>
 * </ul>
 *
 * <p>All implementations return a URL/path string after saving, which is then
 * passed to {@code SimpleExportProcessor.onSaveComplete(String url)}.</p>
 */
public interface ExportStorageService {

    /**
     * Save the file and return the accessible URL or path.
     *
     * @param inputStream File content as stream
     * @param fileName    Full file name including extension, e.g. "employees_20240101.xlsx"
     * @param contentType MIME type, e.g. "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
     * @return URL or path where the file was saved:
     *         <ul>
     *           <li>LOCAL    → absolute file path</li>
     *           <li>S3       → public or pre-signed URL</li>
     *           <li>FIREBASE → download URL</li>
     *         </ul>
     * @throws Exception if saving fails
     */
    String save(InputStream inputStream, String fileName, String contentType) throws Exception;
}