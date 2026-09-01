package com.eazy.batch.writer;

import com.eazy.batch.model.ExportColumn;
import com.eazy.batch.service.ExportStorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spring Batch ItemWriter that writes entity data to an Excel (.xlsx) file.
 *
 * <p>Columns are defined via {@link ExportColumn} using method references —
 * no raw SQL rowMappers needed.</p>
 *
 * <p>After all chunks are written, call {@link #finalizeAndSave()} to upload
 * to storage and trigger the {@code onSaveComplete} callback with the URL.</p>
 *
 * <p>This class is auto-instantiated by {@code BatchExportJobAnnotationProcessor}.
 * You don't need to create it manually.</p>
 *
 * @param <T> Entity type to export
 */
@Slf4j
public class ExcelExportItemWriter<T> implements ItemWriter<T> {

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final List<ExportColumn<T>> columns;
    private final String fileName;
    private final String sheetName;
    private final ExportStorageService storageService;
    private final Consumer<String> onSaveComplete;   // receives the URL after save
    private final Consumer<Throwable> onSaveFailure;

    private final Workbook workbook;
    private final Sheet sheet;
    private int rowIndex = 1; // row 0 = header

    public ExcelExportItemWriter(List<ExportColumn<T>> columns,
                                 String fileName,
                                 String sheetName,
                                 ExportStorageService storageService,
                                 Consumer<String> onSaveComplete,
                                 Consumer<Throwable> onSaveFailure) {
        this.columns = columns;
        this.fileName = fileName;
        this.sheetName = sheetName;
        this.storageService = storageService;
        this.onSaveComplete = onSaveComplete;
        this.onSaveFailure = onSaveFailure;

        this.workbook = new XSSFWorkbook();
        this.sheet = workbook.createSheet(sheetName);
        writeHeaderRow();
    }

    // ─────────────────────────────────────────────────────────────────
    // Write header
    // ─────────────────────────────────────────────────────────────────

    private void writeHeaderRow() {
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            header.createCell(i).setCellValue(columns.get(i).getHeader());
        }
        log.debug("Header row written with {} columns", columns.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // Write each chunk
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void write(Chunk<? extends T> chunk) {
        for (T entity : chunk) {
            Row row = sheet.createRow(rowIndex++);
            for (int col = 0; col < columns.size(); col++) {
                Object value = columns.get(col).getValue(entity);
                setCellValue(row.createCell(col), value);
            }
        }
        log.debug("Wrote {} rows (total so far: {})", chunk.size(), rowIndex - 1);
    }

    private void setCellValue(org.apache.poi.ss.usermodel.Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Finalize: save workbook to storage, trigger callback
    // ─────────────────────────────────────────────────────────────────

    /**
     * Called by the StepExecutionListener after all chunks are written.
     * Serializes the workbook, uploads it to storage, and fires the callback.
     */
    public void finalizeAndSave() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            workbook.write(baos);
            workbook.close();

            byte[] bytes = baos.toByteArray();
            log.info("Saving Excel file: {} ({} rows, {} bytes)", fileName, rowIndex - 1, bytes.length);

            String url = storageService.save(
                    new ByteArrayInputStream(bytes), fileName, CONTENT_TYPE);

            log.info("Export saved successfully. URL: {}", url);
            onSaveComplete.accept(url);  // ← fires SimpleExportProcessor.onSaveComplete(url)

        } catch (Exception e) {
            log.error("Failed to save export file: {}", e.getMessage(), e);
            onSaveFailure.accept(e);     // ← fires SimpleExportProcessor.onSaveFailure(error)
        }
    }
}