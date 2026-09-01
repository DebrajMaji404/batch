package com.eazy.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Saves exported files to the local file system.
 *
 * <p>The returned "URL" is the absolute path to the saved file,
 * e.g. {@code /exports/employees_20240101_120000.xlsx}</p>
 *
 * <h3>Configuration (application.properties):</h3>
 * <pre>
 * batch.export.local.directory=/var/exports
 * </pre>
 *
 * <p>If {@code directory} is not set, the system temp directory is used.</p>
 *
 * <p>Register this bean when {@code storageType = StorageType.LOCAL}:</p>
 * <pre>{@code
 * @Bean
 * @Primary
 * public ExportStorageService exportStorageService() {
 *     return new LocalExportStorageService("/var/exports");
 * }
 * }</pre>
 */
@Slf4j
@Service("localExportStorage")
public class LocalExportStorageService implements ExportStorageService {

    private final String directory;

    public LocalExportStorageService() {
        this.directory = System.getProperty("java.io.tmpdir");
    }

    public LocalExportStorageService(String directory) {
        this.directory = directory;
    }

    @Override
    public String save(InputStream inputStream, String fileName, String contentType) throws Exception {
        Path dir = Paths.get(directory);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("Created export directory: {}", dir);
        }

        Path filePath = dir.resolve(fileName);

        try (OutputStream outputStream = Files.newOutputStream(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            inputStream.transferTo(outputStream);
        }

        String absolutePath = filePath.toAbsolutePath().toString();
        log.info("File saved locally: {}", absolutePath);
        return absolutePath;
    }
}