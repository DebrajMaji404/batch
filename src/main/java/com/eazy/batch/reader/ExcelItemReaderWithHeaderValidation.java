package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.exception.InvalidTemplateException;
import com.poiji.annotation.ExcelCellName;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Row-by-row Excel reader that properly handles skippable exceptions
 */
public class ExcelItemReaderWithHeaderValidation<T> implements ItemReader<T> {
    private final File file;
    private final Class<T> type;
    private final String datePattern;
    private final Map<String, Field> fieldMap;

    private Workbook workbook;
    private Sheet sheet;
    private int currentRowIndex = 1; // Start at 1 (skip header at 0)
    private boolean initialized = false;

    public ExcelItemReaderWithHeaderValidation(@NotNull Resource resource, Class<T> type) {
        try {
            this.file = resource.getFile();
            this.type = type;
            this.datePattern = detectDatePattern(type);
            this.fieldMap = buildFieldMap(type);

            // Validate headers immediately - fail fast for template issues
            validateHeaders(file, type);

        } catch (IOException | InvalidFormatException e) {
            throw new RuntimeException("Failed to read Excel file", e);
        }
    }

    private void lazyInitialize() {
        if (!initialized) {
            try {
                this.workbook = WorkbookFactory.create(file);
                this.sheet = workbook.getSheetAt(0);
                this.initialized = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to open Excel file", e);
            }
        }
    }

    @Override
    public T read() {
        lazyInitialize();

        if (currentRowIndex > sheet.getLastRowNum()) {
            // Close workbook when done
            closeWorkbook();
            return null;
        }

        Row row = sheet.getRow(currentRowIndex);
        int rowNum = currentRowIndex;
        currentRowIndex++;

        if (row == null) {
            // Skip empty rows
            return read();
        }

        try {
            return parseRow(row, rowNum);
        } catch (Exception e) {
            // Wrap in FlatFileParseException so Spring Batch can handle it
            throw new FlatFileParseException(
                    "Error parsing row " + rowNum + ": " + e.getMessage(),
                    e,
                    "",
                    rowNum
            );
        }
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
                return LocalDate.parse(stringValue.trim(), DateTimeFormatter.ofPattern(datePattern));
            } else if (fieldType.isEnum()) {
                return parseEnum(fieldType, stringValue.trim(), rowNum, columnIndex);
            }

            return stringValue;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to parse value '" + stringValue + "' for field '" +
                            field.getName() + "' at row " + rowNum + ", column " + columnIndex +
                            ": " + e.getMessage(),
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
                yield String.valueOf((long) cell.getNumericCellValue());
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
                "Cannot convert '" + value + "' to enum " + enumType.getSimpleName() +
                        " at row " + rowNum + ", column " + columnIndex
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
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            if (headerRow == null) {
                throw new InvalidTemplateException("Excel file does not contain a header row.");
            }

            List<String> excelHeaders = new ArrayList<>();
            for (Cell cell : headerRow) {
                excelHeaders.add(cell.getStringCellValue());
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
            } catch (IOException e) {
                // Log but don't throw
            }
        }
    }
}