package com.eazy.batch.utility;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.report.RowStatus;
import com.poiji.annotation.ExcelCellName;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the in-memory .xlsx report of a job.
 *
 * <p>The report mirrors the ORIGINAL upload template: one column per
 * {@code @ExcelCellName}-annotated field on the DTO, in the same declared-field
 * order the readers use, populated with that row's actual values - followed by
 * two diagnostic columns, {@code Status} and {@code Reason}.</p>
 *
 * <p>The user deletes those two trailing columns, fixes the flagged rows in
 * place, and re-uploads the same file as a valid template. (Deleting them is
 * required, not optional - the readers' header validation rejects extra
 * columns as well as missing ones.)</p>
 *
 * <p>Rows are streamed to disk-backed sheet storage (POI's SXSSF), so a report
 * covering every row of a large upload does not have to fit in memory.</p>
 */
@Slf4j
public final class ErrorReportExcelGenerator {

    public static final String STATUS_HEADER = "Status";
    public static final String REASON_HEADER = "Reason";
    public static final String ERRORS_SHEET = "Errors";
    public static final String ALL_ROWS_SHEET = "All rows";

    private ErrorReportExcelGenerator() {
    }

    /**
     * Row-level skips only: every item is written as a {@code FAILED} row
     * whose reason is prefixed with its phase, e.g. {@code [PROCESS] ...}.
     * Used when the job's input file can't be re-read to build the fuller
     * report (see {@code BatchReportService}).
     *
     * @return the .xlsx bytes, or {@code null} if {@code skippedItems} is empty
     */
    public static byte[] generate(List<BatchSkippedItem<?>> skippedItems) {
        if (skippedItems == null || skippedItems.isEmpty()) {
            return null;
        }

        // Derive the template's columns from whichever skipped item carries
        // the DTO. PROCESS-phase skips hold the DTO itself; READ-phase skips
        // hold null (nothing was parsed) and WRITE-phase skips hold the
        // mapped entity, neither of which has @ExcelCellName fields - so scan
        // for the first item that actually does.
        Class<?> dtoClass = findTemplateClass(skippedItems);

        try (Builder builder = new Builder(dtoClass, ERRORS_SHEET)) {
            for (BatchSkippedItem<?> item : skippedItems) {
                Object raw = item.getItem();
                Object shown = raw;
                if (dtoClass == null) {
                    shown = raw != null ? raw.toString()
                            : "READ".equals(item.getPhase()) ? "(none - read failure)" : "(not row-specific)";
                }
                String phase = item.getPhase() != null ? item.getPhase() : "";
                String reason = item.getReason() != null ? item.getReason() : "";
                builder.addRow(shown, RowStatus.FAILED, phase.isEmpty() ? reason : "[" + phase + "] " + reason);
            }
            return builder.build();
        } catch (IOException e) {
            log.error("Failed to build error report Excel: {}", e.getMessage(), e);
            return null;
        }
    }

    private static Class<?> findTemplateClass(List<BatchSkippedItem<?>> skippedItems) {
        for (BatchSkippedItem<?> item : skippedItems) {
            Object raw = item.getItem();
            if (raw != null && !annotatedFields(raw.getClass()).isEmpty()) {
                return raw.getClass();
            }
        }
        return null;
    }

    /** The {@code @ExcelCellName} fields of {@code type}, in declared order - same as the readers. */
    static List<Field> annotatedFields(Class<?> type) {
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
     * Streaming report writer. Header = template columns + Status + Reason;
     * add rows one at a time with {@link #addRow}, then {@link #build()}.
     */
    public static final class Builder implements AutoCloseable {

        private final SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        private final SXSSFSheet sheet;
        private final List<Field> templateFields;
        private final int statusCol;
        private final int reasonCol;
        private final CellStyle failedStyle;
        private final CellStyle notImportedStyle;
        private final CellStyle successStyle;
        private int nextRow = 1;

        /**
         * @param dtoClass  the upload DTO whose {@code @ExcelCellName} fields become the
         *                  leading columns; {@code null} (or a class without any) gives a
         *                  single generic "Item" column instead
         * @param sheetName name of the worksheet
         */
        public Builder(Class<?> dtoClass, String sheetName) {
            this.sheet = workbook.createSheet(sheetName);
            this.templateFields = dtoClass == null ? List.of() : annotatedFields(dtoClass);

            CellStyle headerStyle = boldStyle(workbook);
            CellStyle diagnosticHeaderStyle = diagnosticHeaderStyle(workbook);
            this.failedStyle = fillStyle(workbook, IndexedColors.ROSE);
            this.notImportedStyle = fillStyle(workbook, IndexedColors.LIGHT_ORANGE);
            this.successStyle = fillStyle(workbook, IndexedColors.LIGHT_GREEN);

            Row header = sheet.createRow(0);
            int col = 0;
            if (templateFields.isEmpty()) {
                // No DTO to mirror: fall back to a single generic column.
                createCell(header, col, "Item", headerStyle);
                sheet.setColumnWidth(col, 40 * 256);
                col++;
            } else {
                for (Field field : templateFields) {
                    String name = field.getAnnotation(ExcelCellName.class).value();
                    createCell(header, col, name, headerStyle);
                    sheet.setColumnWidth(col, Math.min(40, Math.max(14, name.length() + 4)) * 256);
                    col++;
                }
            }
            this.statusCol = col;
            this.reasonCol = col + 1;
            createCell(header, statusCol, STATUS_HEADER, diagnosticHeaderStyle);
            createCell(header, reasonCol, REASON_HEADER, diagnosticHeaderStyle);
            sheet.setColumnWidth(statusCol, 16 * 256);
            sheet.setColumnWidth(reasonCol, 70 * 256);
            // Keep the header visible while scrolling a long report.
            sheet.createFreezePane(0, 1);
        }

        /** Number of data rows added so far. */
        public int rowCount() {
            return nextRow - 1;
        }

        /**
         * @param item   the upload DTO for this row (values fill the template columns); when
         *               this builder has no template columns, whatever should be shown in the
         *               single "Item" column. May be {@code null} or not a DTO - the template
         *               columns are then left blank.
         * @param status the row's outcome
         * @param reason why it was not imported; blank for SUCCESS
         */
        public void addRow(Object item, RowStatus status, String reason) {
            Row row = sheet.createRow(nextRow++);
            if (templateFields.isEmpty()) {
                row.createCell(0).setCellValue(item != null ? String.valueOf(item) : "");
            } else {
                for (int i = 0; i < templateFields.size(); i++) {
                    row.createCell(i).setCellValue(readFieldValue(item, templateFields.get(i)));
                }
            }
            Cell statusCell = row.createCell(statusCol);
            statusCell.setCellValue(status.name());
            statusCell.setCellStyle(switch (status) {
                case FAILED -> failedStyle;
                case NOT_IMPORTED -> notImportedStyle;
                case SUCCESS -> successStyle;
            });
            row.createCell(reasonCol).setCellValue(reason != null ? reason : "");
        }

        public byte[] build() throws IOException {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                workbook.write(baos);
                return baos.toByteArray();
            }
        }

        @Override
        public void close() throws IOException {
            // dispose() deletes SXSSF's temporary spill files.
            workbook.dispose();
            workbook.close();
        }
    }

    /**
     * Reads one template column's value off a row's DTO. Returns "" when the
     * item is null (READ-phase failure) or isn't the DTO type.
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

    private static CellStyle boldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /**
     * Status/Reason headers get a distinct fill so it's visually obvious which
     * two columns are the added diagnostics to delete before re-uploading.
     */
    private static CellStyle diagnosticHeaderStyle(Workbook workbook) {
        CellStyle style = boldStyle(workbook);
        style.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle fillStyle(Workbook workbook, IndexedColors color) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
