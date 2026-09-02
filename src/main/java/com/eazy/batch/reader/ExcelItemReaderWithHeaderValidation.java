package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.exception.InvalidTemplateException;
import com.poiji.annotation.ExcelCellName;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Row-by-row Excel reader that properly handles skippable exceptions
 * FIXED: Resource leak - now implements DisposableBean
 * FIXED: Empty workbook handling
 *
 * <p>NEW: implements {@link ItemStreamReader} for restart support. Excel
 * sheets support direct row-index access, so on restart {@link #open}
 * simply resumes {@code currentRowIndex} from the checkpoint stored by
 * {@link #update} - no need to re-read and discard rows the way the
 * line-based {@link CSVItemReader} does. Previously this reader only
 * implemented plain {@code ItemReader}, so a step failure always restarted
 * the whole file from row 1 regardless of how much had already been
 * committed.</p>
 */
@Slf4j
public class ExcelItemReaderWithHeaderValidation<T> implements ItemStreamReader<T> {

    private static final String ROW_INDEX_KEY = "excel.reader.row.index";

    private final File file;
    private final Class<T> type;
    private final String datePattern;
    private final Map<String, Field> fieldMap;
    private final int sheetIndex;
    private final String sheetName;

    private Workbook workbook;
    private Sheet sheet;
    private int currentRowIndex = 1; // Start at 1 (skip header at 0)

    public ExcelItemReaderWithHeaderValidation(@NotNull Resource resource, Class<T> type) {
        this(resource, type, 0, null);
    }

    public ExcelItemReaderWithHeaderValidation(@NotNull Resource resource, Class<T> type,
                                               int sheetIndex, String sheetName) {
        try {
            this.file = resource.getFile();
            this.type = type;
            this.sheetIndex = sheetIndex;
            this.sheetName = sheetName;
            this.datePattern = detectDatePattern(type);
            this.fieldMap = buildFieldMap(type);

            // Validate headers immediately - fail fast for template issues.
            // This opens and closes its own short-lived Workbook and does
            // NOT set the `workbook`/`sheet` fields used for actual reading -
            // those are only opened in open(), per the ItemStream contract.
            validateHeaders(file, type);

        } catch (IOException | InvalidFormatException e) {
            throw new RuntimeException("Failed to read Excel file", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ItemStream lifecycle
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void open(@NonNull ExecutionContext executionContext) throws ItemStreamException {
        try {
            this.workbook = WorkbookFactory.create(file);

            if (workbook.getNumberOfSheets() == 0) {
                throw new InvalidTemplateException("Excel file has no sheets");
            }

            if (sheetName != null && !sheetName.isEmpty()) {
                this.sheet = workbook.getSheet(sheetName);
                if (this.sheet == null) {
                    throw new InvalidTemplateException(
                            "Sheet '" + sheetName + "' not found in workbook"
                    );
                }
            } else {
                if (sheetIndex >= workbook.getNumberOfSheets()) {
                    throw new InvalidTemplateException(
                            "Sheet index " + sheetIndex + " out of bounds. " +
                            "Workbook has " + workbook.getNumberOfSheets() + " sheets"
                    );
                }
                this.sheet = workbook.getSheetAt(sheetIndex);
            }

            log.info("Excel reader initialized: {} rows to process", sheet.getLastRowNum());

            // Restart support: resume from the checkpointed row.
            if (executionContext.containsKey(ROW_INDEX_KEY)) {
                this.currentRowIndex = (int) executionContext.getLong(ROW_INDEX_KEY);
                log.info("Restarting Excel reader at row {}", currentRowIndex);
            }
        } catch (Exception e) {
            closeWorkbook();
            throw new ItemStreamException("Failed to open Excel file: " + e.getMessage(), e);
        }
    }

    @Override
    public void update(@NonNull ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(ROW_INDEX_KEY, currentRowIndex);
    }

    @Override
    public void close() throws ItemStreamException {
        closeWorkbook();
    }

    // ─────────────────────────────────────────────────────────────────
    // Reading
    // ─────────────────────────────────────────────────────────────────

    @Override
    public T read() {
        if (sheet == null) {
            throw new IllegalStateException("Excel reader not open - open() must be called before read()");
        }

        try {
            if (currentRowIndex > sheet.getLastRowNum()) {
                return null;
            }

            Row row = sheet.getRow(currentRowIndex);
            int rowNum = currentRowIndex;
            currentRowIndex++;

            if (row == null || isEmptyRow(row)) {
                // Skip empty rows
                log.debug("Skipping empty row at index {}", rowNum);
                return read();
            }

            return parseRow(row, rowNum);
        } catch (Exception e) {
            // Wrap in FlatFileParseException so Spring Batch can handle it
            throw new FlatFileParseException(
                    "Error parsing row " + currentRowIndex + ": " + e.getMessage(),
                    e,
                    "",
                    currentRowIndex
            );
        }
    }

    private boolean isEmptyRow(Row row) {
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String value = getCellStringValue(cell);
                if (value != null && !value.trim().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private T parseRow(Row row, int rowNum) throws Exception {
        T instance = type.getDeclaredConstructor().newInstance();
        Row headerRow = sheet.getRow(0);

        for (Cell cell : row) {
            int columnIndex = cell.getColumnIndex();
            Cell headerCell = headerRow.getCell(columnIndex);

            if (headerCell == null) {
                continue;
            }

            String headerName = headerCell.getStringCellValue();
            Field field = fieldMap.get(headerName);

            if (field == null) {
                continue;
            }

            field.setAccessible(true);
            Object value = parseCellValue(cell, field, rowNum, columnIndex);
            field.set(instance, value);
        }

        return instance;
    }

    private Object parseCellValue(Cell cell, Field field, int rowNum, int columnIndex) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        String stringValue = getCellStringValue(cell);
        if (stringValue == null || stringValue.isBlank()) {
            return null;
        }

        Class<?> fieldType = field.getType();

        try {
            if (fieldType == String.class) {
                return stringValue;
            } else if (fieldType == Integer.class || fieldType == int.class) {
                return Integer.valueOf(stringValue.trim());
            } else if (fieldType == Long.class || fieldType == long.class) {
                return Long.valueOf(stringValue.trim());
            } else if (fieldType == Double.class || fieldType == double.class) {
                return Double.valueOf(stringValue.trim());
            } else if (fieldType == Boolean.class || fieldType == boolean.class) {
                return Boolean.valueOf(stringValue.trim());
            } else if (fieldType == LocalDate.class) {
                // Check for custom date format on field
                String pattern = datePattern;
                if (field.isAnnotationPresent(ExcelDateFormat.class)) {
                    pattern = field.getAnnotation(ExcelDateFormat.class).pattern();
                }
                return LocalDate.parse(stringValue.trim(),
                        DateTimeFormatter.ofPattern(pattern));
            } else if (fieldType == LocalDateTime.class) {
                // Check for custom date format on field
                String pattern = datePattern;
                if (field.isAnnotationPresent(ExcelDateFormat.class)) {
                    pattern = field.getAnnotation(ExcelDateFormat.class).pattern();
                }
                // Try LocalDateTime parse first, fall back to LocalDate + atStartOfDay
                try {
                    return LocalDateTime.parse(stringValue.trim(),
                            DateTimeFormatter.ofPattern(pattern));
                } catch (Exception e) {
                    return LocalDate.parse(stringValue.trim(),
                            DateTimeFormatter.ofPattern(pattern)).atStartOfDay();
                }
            } else if (fieldType.isEnum()) {
                return parseEnum(fieldType, stringValue.trim(), rowNum, columnIndex);
            }

            return stringValue;
        } catch (Exception e) {
            String headerName = sheet.getRow(0).getCell(columnIndex).getStringCellValue();
            throw new RuntimeException(
                    String.format(
                            "Failed to parse value '%s' for field '%s' (column '%s') " +
                            "at row %d, column %d: %s",
                            stringValue, field.getName(), headerName,
                            rowNum, columnIndex, e.getMessage()
                    ),
                    e
            );
        }
    }

    private String getCellStringValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate()
                            .format(DateTimeFormatter.ofPattern(datePattern));
                }
                double numericValue = cell.getNumericCellValue();
                // Check if it's a whole number
                if (numericValue == Math.floor(numericValue)) {
                    yield String.valueOf((long) numericValue);
                }
                yield String.valueOf(numericValue);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }

    private Object parseEnum(Class<?> enumType, String value, int rowNum, int columnIndex) {
        // Try fromDisplayName method
        try {
            var method = enumType.getMethod("fromDisplayName", String.class);
            Object result = method.invoke(null, value);
            if (result != null) return result;
        } catch (Exception ignored) {}

        // Try valueOf
        try {
            var method = enumType.getMethod("valueOf", String.class);
            return method.invoke(null, value);
        } catch (Exception e1) {
            // Try normalized (uppercase with underscores)
            try {
                var normalized = value.toUpperCase().replace(" ", "_");
                var method = enumType.getMethod("valueOf", String.class);
                return method.invoke(null, normalized);
            } catch (Exception e2) {
                // Try case-insensitive match
                for (Object constant : enumType.getEnumConstants()) {
                    if (constant.toString().equalsIgnoreCase(value)) {
                        return constant;
                    }
                }
            }
        }

        throw new IllegalArgumentException(
                String.format(
                        "Cannot convert '%s' to enum %s at row %d, column %d. " +
                        "Valid values: %s",
                        value, enumType.getSimpleName(), rowNum, columnIndex,
                        Arrays.toString(enumType.getEnumConstants())
                )
        );
    }

    private Map<String, Field> buildFieldMap(Class<T> type) {
        Map<String, Field> map = new HashMap<>();
        for (Field field : type.getDeclaredFields()) {
            ExcelCellName annotation = field.getAnnotation(ExcelCellName.class);
            if (annotation != null) {
                map.put(annotation.value(), field);
            }
        }
        return map;
    }

    private void validateHeaders(File file, Class<T> type) throws IOException, InvalidFormatException {
        try (Workbook workbook = WorkbookFactory.create(file)) {
            // FIXED: Check if workbook has sheets
            if (workbook.getNumberOfSheets() == 0) {
                throw new InvalidTemplateException("Excel file has no sheets");
            }

            Sheet sheet;
            if (sheetName != null && !sheetName.isEmpty()) {
                sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    throw new InvalidTemplateException(
                            "Sheet '" + sheetName + "' not found in workbook"
                    );
                }
            } else {
                if (sheetIndex >= workbook.getNumberOfSheets()) {
                    throw new InvalidTemplateException(
                            "Sheet index " + sheetIndex + " out of bounds"
                    );
                }
                sheet = workbook.getSheetAt(sheetIndex);
            }

            Row headerRow = sheet.getRow(0);

            if (headerRow == null) {
                throw new InvalidTemplateException(
                        "Excel file does not contain a header row in sheet: " +
                        (sheetName != null ? sheetName : "index " + sheetIndex)
                );
            }

            List<String> excelHeaders = new ArrayList<>();
            for (Cell cell : headerRow) {
                String header = cell.getStringCellValue();
                if (header != null && !header.trim().isEmpty()) {
                    excelHeaders.add(header.trim());
                }
            }

            List<String> expectedHeaders = getExpectedHeaders(type);
            List<String> missingHeaders = expectedHeaders.stream()
                    .filter(header -> !excelHeaders.contains(header))
                    .toList();

            List<String> extraHeaders = excelHeaders.stream()
                    .filter(header -> !expectedHeaders.contains(header))
                    .toList();

            if (!missingHeaders.isEmpty() || !extraHeaders.isEmpty()) {
                StringBuilder errorMsg = new StringBuilder("Invalid template. ");
                if (!missingHeaders.isEmpty()) {
                    errorMsg.append("Missing headers: ").append(missingHeaders).append(". ");
                }
                if (!extraHeaders.isEmpty()) {
                    errorMsg.append("Extra headers: ").append(extraHeaders).append(".");
                }
                throw new InvalidTemplateException(errorMsg.toString().trim());
            }

            log.info("✅ Excel headers validated successfully for sheet: {}",
                    sheetName != null ? sheetName : "index " + sheetIndex);
        }
    }

    private String detectDatePattern(@NotNull Class<T> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(ExcelDateFormat.class)) {
                ExcelDateFormat dateFormat = field.getAnnotation(ExcelDateFormat.class);
                return dateFormat.pattern();
            }
        }
        return "yyyy-MM-dd";
    }

    private @NotNull List<String> getExpectedHeaders(@NotNull Class<T> type) {
        List<String> headers = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            ExcelCellName annotation = field.getAnnotation(ExcelCellName.class);
            if (annotation != null) {
                headers.add(annotation.value());
            }
        }
        return headers;
    }

    private void closeWorkbook() {
        if (workbook != null) {
            try {
                workbook.close();
                log.debug("Workbook closed successfully");
            } catch (IOException e) {
                log.warn("Failed to close workbook: {}", e.getMessage());
            } finally {
                workbook = null;
            }
        }
    }
}
