package com.eazy.batch.writer;

import com.eazy.batch.model.ExportColumn;
import com.eazy.batch.service.ExportStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Spring Batch ItemWriter that writes entity data to a CSV file.
 *
 * <p>Columns are defined via {@link ExportColumn} using method references —
 * no raw SQL rowMappers needed.</p>
 *
 * <p>NEW: rows are written straight to a temp file on disk as each chunk
 * arrives, rather than accumulated in an in-memory StringBuilder/StringWriter.
 * Only the temp file's contents are streamed to storage once, in
 * {@link #finalizeAndSave()}, and the temp file is deleted afterward - so a
 * very large export no longer holds its entire CSV content in heap memory at
 * once (the same class of fix already applied to
 * {@link ExcelExportItemWriter} via POI's streaming SXSSFWorkbook).</p>
 *
 * @param <T> Entity type to export
 */
@Slf4j
public class CsvExportItemWriter<T> implements ItemWriter<T> {

    private static final String CONTENT_TYPE = "text/csv";

    private final List<ExportColumn<T>> columns;
    private final String fileName;
    private final ExportStorageService storageService;
    private final Consumer<String> onSaveComplete;
    private final Consumer<Throwable> onSaveFailure;

    private final Path tempFile;
    private final BufferedWriter writer;
    private int rowCount = 0;

    public CsvExportItemWriter(List<ExportColumn<T>> columns,
                               String fileName,
                               ExportStorageService storageService,
                               Consumer<String> onSaveComplete,
                               Consumer<Throwable> onSaveFailure) {
        this.columns = columns;
        this.fileName = fileName;
        this.storageService = storageService;
        this.onSaveComplete = onSaveComplete;
        this.onSaveFailure = onSaveFailure;

        try {
            this.tempFile = Files.createTempFile("eazy-batch-export-", ".csv");
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(tempFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    StandardCharsets.UTF_8));
            writeHeaderRow();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temp file for CSV export: " + e.getMessage(), e);
        }
    }

    private void writeHeaderRow() throws IOException {
        String header = columns.stream()
                .map(ExportColumn::getHeader)
                .map(this::escapeCsv)
                .collect(Collectors.joining(","));
        writer.write(header);
        writer.write("\n");
    }

    @Override
    public void write(Chunk<? extends T> chunk) throws IOException {
        for (T entity : chunk) {
            String row = columns.stream()
                    .map(col -> escapeCsv(String.valueOf(col.getValue(entity))))
                    .collect(Collectors.joining(","));
            writer.write(row);
            writer.write("\n");
            rowCount++;
        }
        log.debug("Wrote {} rows (total: {})", chunk.size(), rowCount);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Flushes the temp file, streams it to storage, fires the callback, then
     * always deletes the temp file (success or failure) so nothing is leaked.
     */
    public void finalizeAndSave() {
        try {
            writer.flush();
            writer.close();

            long bytes = Files.size(tempFile);
            log.info("Saving CSV file: {} ({} rows, {} bytes)", fileName, rowCount, bytes);

            try (InputStream inputStream = Files.newInputStream(tempFile)) {
                String url = storageService.save(inputStream, fileName, CONTENT_TYPE);
                log.info("Export saved successfully. URL: {}", url);
                onSaveComplete.accept(url);
            }
        } catch (Exception e) {
            log.error("Failed to save CSV export: {}", e.getMessage(), e);
            onSaveFailure.accept(e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp CSV export file {}: {}", tempFile, e.getMessage());
            }
        }
    }
}
