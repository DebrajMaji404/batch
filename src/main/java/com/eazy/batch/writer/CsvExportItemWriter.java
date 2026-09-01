package com.eazy.batch.writer;

import com.eazy.batch.model.ExportColumn;
import com.eazy.batch.service.ExportStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Spring Batch ItemWriter that writes entity data to a CSV file.
 *
 * <p>Columns are defined via {@link ExportColumn} using method references —
 * no raw SQL rowMappers needed.</p>
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

    private final StringWriter buffer = new StringWriter();
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

        writeHeaderRow();
    }

    private void writeHeaderRow() {
        String header = columns.stream()
                .map(ExportColumn::getHeader)
                .map(this::escapeCsv)
                .collect(Collectors.joining(","));
        buffer.write(header);
        buffer.write("\n");
    }

    @Override
    public void write(Chunk<? extends T> chunk) {
        for (T entity : chunk) {
            String row = columns.stream()
                    .map(col -> escapeCsv(String.valueOf(col.getValue(entity))))
                    .collect(Collectors.joining(","));
            buffer.write(row);
            buffer.write("\n");
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
     * Serialize CSV to bytes, upload to storage, fire callback.
     */
    public void finalizeAndSave() {
        try {
            byte[] bytes = buffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            log.info("Saving CSV file: {} ({} rows, {} bytes)", fileName, rowCount, bytes.length);

            String url = storageService.save(new ByteArrayInputStream(bytes), fileName, CONTENT_TYPE);
            log.info("Export saved successfully. URL: {}", url);
            onSaveComplete.accept(url);

        } catch (Exception e) {
            log.error("Failed to save CSV export: {}", e.getMessage(), e);
            onSaveFailure.accept(e);
        }
    }
}