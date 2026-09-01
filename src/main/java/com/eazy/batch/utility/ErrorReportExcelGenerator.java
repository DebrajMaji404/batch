package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Builds a small in-memory "error report" .xlsx from a job's skipped items -
 * three columns: Item, Phase, Reason. Called once per job (from
 * {@link com.eazy.batch.listener.JobCompletionListener}) against a list
 * bounded by the job's skipLimit, so a plain (non-streaming) XSSFWorkbook
 * is fine here - this is not the main export path.
 */
@Slf4j
public final class ErrorReportExcelGenerator {

    private ErrorReportExcelGenerator() {
    }

    /**
     * @return the .xlsx bytes, or {@code null} if {@code skippedItems} is empty
     */
    public static byte[] generate(List<BatchSkippedItem<?>> skippedItems) {
        if (skippedItems == null || skippedItems.isEmpty()) {
            return null;
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Errors");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Phase");
            header.createCell(2).setCellValue("Reason");

            int rowIndex = 1;
            for (BatchSkippedItem<?> item : skippedItems) {
                Row row = sheet.createRow(rowIndex++);
                Object rawItem = item.getItem();
                row.createCell(0).setCellValue(rawItem != null ? rawItem.toString() : "(none - read failure)");
                row.createCell(1).setCellValue(item.getPhase() != null ? item.getPhase() : "");
                row.createCell(2).setCellValue(item.getReason() != null ? item.getReason() : "");
            }

            for (int col = 0; col < 3; col++) {
                sheet.autoSizeColumn(col);
            }

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                workbook.write(baos);
                return baos.toByteArray();
            }
        } catch (IOException e) {
            log.error("Failed to build error report Excel: {}", e.getMessage(), e);
            return null;
        }
    }
}
