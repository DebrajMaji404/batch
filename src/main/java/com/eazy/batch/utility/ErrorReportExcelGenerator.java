package com.eazy.batch.utility;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.dto.BatchSkippedItem;
import com.poiji.annotation.ExcelCellName;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a small in-memory "error report" .xlsx from a job's skipped items.
 *
 * <p>The report mirrors the ORIGINAL upload template: one column per
 * {@code @ExcelCellName}-annotated field on the DTO, in the same declared-field
 * order the readers use, populated with that row's actual values - followed by
 * two extra diagnostic columns, Phase and Reason.</p>
 *
 * <p>This is deliberate: the user deletes the two trailing Phase/Reason
 * columns, fixes the flagged rows in place, and re-uploads the same file as a
 * valid template. (Deleting them is required, not optional - the readers'
 * header validation rejects extra columns as well as missing ones, so an
 * unedited error report will fail with "Extra headers: [Phase, Reason]".)</p>
 *
 * <p>Called once per job (from {@link com.eazy.batch.listener.JobCompletionListener})
 * against a list bounded by the job's skipLimit, so a plain (non-streaming)
 * XSSFWorkbook is fine here - this is not the main export path.</p>
 */
@Slf4j
public final class ErrorReportExcelGenerator {

    private static final String PHASE_HEADER = "Phase";
    private static final String REASON_HEADER = "Reason";

    private ErrorReportExcelGenerator() {
    }

    /**
     * @return the .xlsx bytes, or {@code null} if {@code skippedItems} is empty
     */
    public static byte[] generate(List<BatchSkippedItem<?>> skippedItems) {
        if (skippedItems == null || skippedItems.isEmpty()) {
            return null;
        }

        // Derive the template's columns from whichever skipped item carries
        // the DTO. PROCESS-phase skips hold the DTO itself; READ-phase skips
        // hold null (nothing was parsed yet) and WRITE-phase skips hold the
        // mapped entity, neither of which has @ExcelCellName fields - so scan
        // for the first item that actually does.
        List<Field> templateFields = findTemplateFields(skippedItems);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Errors");

            CellStyle headerStyle = boldStyle(workbook);
            CellStyle diagnosticHeaderStyle = diagnosticHeaderStyle(workbook);

            Row header = sheet.createRow(0);
            int col = 0;

            if (templateFields.isEmpty()) {
                // No DTO was available on any skipped item (e.g. a job whose
                // only failures were READ-phase parse errors). Fall back to a
                // single generic column rather than emitting a file with
                // nothing but Phase/Reason in it.
                createCell(header, col++, "Item", headerStyle);
            } else {
                for (Field field : templateFields) {
                    createCell(header, col++, field.getAnnotation(ExcelCellName.class).value(), headerStyle);
                }
            }

            int phaseCol = col;
            int reasonCol = col + 1;
            createCell(header, phaseCol, PHASE_HEADER, diagnosticHeaderStyle);
            createCell(header, reasonCol, REASON_HEADER, diagnosticHeaderStyle);

            int rowIndex = 1;
            for (BatchSkippedItem<?> item : skippedItems) {
                Row row = sheet.createRow(rowIndex++);
                Object rawItem = item.getItem();

                if (templateFields.isEmpty()) {
                    row.createCell(0).setCellValue(rawItem != null ? rawItem.toString()
                            : "READ".equals(item.getPhase()) ? "(none - read failure)" : "(not row-specific)");
                } else {
                    for (int i = 0; i < templateFields.size(); i++) {
                        row.createCell(i).setCellValue(readFieldValue(rawItem, templateFields.get(i)));
                    }
                }

                row.createCell(phaseCol).setCellValue(item.getPhase() != null ? item.getPhase() : "");
                row.createCell(reasonCol).setCellValue(item.getReason() != null ? item.getReason() : "");
            }

            for (int c = 0; c <= reasonCol; c++) {
                sheet.autoSizeColumn(c);
            }
            // Keep the header visible while scrolling a long error list.
            sheet.createFreezePane(0, 1);

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                workbook.write(baos);
                return baos.toByteArray();
            }
        } catch (IOException e) {
            log.error("Failed to build error report Excel: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Returns the @ExcelCellName-annotated fields, in declared order, of the
     * first skipped item that has any - matching exactly how CSVItemReader and
     * ExcelItemReaderWithHeaderValidation derive their expected headers, so the
     * generated file passes their validation on re-upload.
     */
    private static List<Field> findTemplateFields(List<BatchSkippedItem<?>> skippedItems) {
        for (BatchSkippedItem<?> item : skippedItems) {
            Object raw = item.getItem();
            if (raw == null) {
                continue;
            }
            List<Field> fields = annotatedFields(raw.getClass());
            if (!fields.isEmpty()) {
                return fields;
            }
        }
        return List.of();
    }

    private static List<Field> annotatedFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(ExcelCellName.class)) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Reads one template column's value off a skipped item. Returns "" when the
     * item is null (READ-phase failure) or isn't the DTO type (WRITE-phase
     * failure, where the item is the mapped entity) - those rows still carry
     * their Phase and Reason, they just can't show the original column values.
     */
    private static String readFieldValue(Object item, Field field) {
        if (item == null || !field.getDeclaringClass().isInstance(item)) {
            return "";
        }
        try {
            Object value = field.get(item);
            if (value == null) {
                return "";
            }
            // Round-trip dates using the same pattern the reader parses with,
            // so a corrected row re-uploads cleanly.
            ExcelDateFormat dateFormat = field.getAnnotation(ExcelDateFormat.class);
            String pattern = dateFormat != null ? dateFormat.pattern() : "yyyy-MM-dd";
            if (value instanceof LocalDate date) {
                return date.format(DateTimeFormatter.ofPattern(pattern));
            }
            if (value instanceof LocalDateTime dateTime) {
                return dateTime.format(DateTimeFormatter.ofPattern(pattern));
            }
            return String.valueOf(value);
        } catch (Exception e) {
            log.warn("Could not read field '{}' for error report: {}", field.getName(), e.getMessage());
            return "";
        }
    }

    private static void createCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static CellStyle boldStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /**
     * Phase/Reason headers get a distinct fill so it's visually obvious which
     * two columns are the added diagnostics to delete before re-uploading.
     */
    private static CellStyle diagnosticHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = boldStyle(workbook);
        style.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
