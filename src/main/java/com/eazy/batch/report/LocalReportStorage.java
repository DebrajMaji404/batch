package com.eazy.batch.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Built-in {@link BatchReportStorage}: keeps reports on local disk and returns a URL served by
 * the built-in download endpoint. Active only with {@code eazy.batch.report.local-enabled=true}
 * and when the application has no storage bean of its own.
 */
public class LocalReportStorage implements BatchReportStorage {

    private final Path root;
    private final String urlPrefix;

    /** @param urlPrefix everything before {@code /{jobExecutionId}/{fileName}} in the returned URL */
    public LocalReportStorage(String directory, String urlPrefix) {
        this.root = directory == null || directory.isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"), "eazy-batch-reports")
                : Paths.get(directory);
        this.urlPrefix = urlPrefix;
    }

    @Override
    public String store(BatchReportFile file) {
        try {
            Path dir = root.resolve(String.valueOf(file.jobExecutionId())).normalize();
            Files.createDirectories(dir);
            Files.write(dir.resolve(safeName(file.fileName())), file.content());
            return urlPrefix + "/" + file.jobExecutionId() + "/" + safeName(file.fileName());
        } catch (IOException e) {
            throw new IllegalStateException("Could not store report " + file.fileName() + ": " + e.getMessage(), e);
        }
    }

    /** @return the stored file, or null if the id/name would escape the report directory or the file is missing */
    public Path resolve(String jobExecutionId, String fileName) {
        // id and name are single path segments: anything with a separator or "." / ".." is refused outright
        if (jobExecutionId == null || !jobExecutionId.matches("\\d+")
                || fileName == null || fileName.isBlank() || fileName.equals(".") || fileName.equals("..")
                || fileName.contains("/") || fileName.contains("\\")) {
            return null;
        }
        Path file = root.resolve(jobExecutionId).resolve(fileName).normalize();
        if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) return null;
        return file;
    }

    static String safeName(String name) {
        return name == null ? "report.xlsx" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }
}
