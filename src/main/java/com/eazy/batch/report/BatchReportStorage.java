package com.eazy.batch.report;

/**
 * Where finished error/full reports go. Register ONE bean of this type and
 * every {@code @BatchJob} report is uploaded through it:
 *
 * <pre>{@code
 * @Bean
 * BatchReportStorage batchReportStorage(S3Client s3) {
 *     return file -> {
 *         String key = "batch-reports/" + file.jobExecutionId() + "/" + file.fileName();
 *         s3.putObject(b -> b.bucket("my-bucket").key(key), RequestBody.fromBytes(file.content()));
 *         return "https://my-bucket.s3.amazonaws.com/" + key;
 *     };
 * }
 * }</pre>
 *
 * <p>The returned full URL is sent to the client in the final WebSocket
 * message as {@code errorFileUrl}; the file bytes are then NOT embedded in the
 * message. If no bean is registered, or {@link #store} throws, the report is
 * embedded as base64 ({@code errorFileBase64}) instead, so the user always
 * gets the file.</p>
 */
@FunctionalInterface
public interface BatchReportStorage {

    /**
     * Upload the report and return where it can be downloaded.
     *
     * @return the full, client-reachable URL of the stored file
     * @throws Exception if the upload fails (the library then falls back to base64)
     */
    String store(BatchReportFile file) throws Exception;
}
