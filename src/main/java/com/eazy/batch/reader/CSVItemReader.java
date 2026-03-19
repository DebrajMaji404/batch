package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.exception.InvalidTemplateException;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.poiji.annotation.ExcelCellName;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.Resource;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CSV Item Reader with header validation.
 *
 * KEY FIX: All initialization (opening the file, reading headers, validating headers)
 * is deferred from the constructor into lazyInitialize(), which is called on the
 * first read() call. This ensures that InvalidTemplateException (missing/extra
 * headers, empty file, etc.) is thrown from read() — the only place Spring Batch's
 * skip mechanism and onSkipInRead listeners can intercept it.
 *
 * Exceptions thrown from a constructor bypass Spring Batch's skip/listener
 * infrastructure entirely.
 */
@Slf4j
public class CSVItemReader<T> implements ItemReader<T>, DisposableBean {

    private final Resource resource;
    private final Class<T> type;
    private final String datePattern;
    private final Map<String, Field> fieldMap;
    private final Map<String, Integer> headerIndexMap = new HashMap<>();

    private CSVReader csvReader;
    private String[] headers;
    private int currentRowIndex = 0;
    private boolean initialized = false;

    public CSVItemReader(@NotNull Resource resource, Class<T> type) {
        // Only store lightweight metadata in the constructor — no I/O, no validation.
        // Any exception here would escape Spring Batch's skip/listener infrastructure.
        this.resource = resource;
        this.type = type;
        this.datePattern = detectDatePattern(type);
        this.fieldMap = buildFieldMap(type);
    }

    /**
     * Opens the file, reads and validates headers on the first read() call.
     * Any InvalidTemplateException thrown here propagates up through read()
     * and is properly caught by Spring Batch's skip/listener infrastructure.
     */
    private void lazyInitialize() {
        if (!initialized) {
            try {
                this.csvReader = new CSVReader(new FileReader(resource.getFile()));

                this.headers = csvReader.readNext();
                if (headers == null || headers.length == 0) {
                    throw new InvalidTemplateException("CSV file does not contain headers");
                }

                for (int i = 0; i < headers.length; i++) {
                    headerIndexMap.put(headers[i].trim(), i);
                }

                // Validate headers here (deferred from constructor) so exceptions
                // surface in read() and are routed to the skip listener.
                validateHeaders();

                this.initialized = true;
                log.info("✅ CSV reader initialized with {} columns", headers.length);

            } catch (InvalidTemplateException e) {
                closeReader();
                throw e; // re-throw as-is; read() wraps it in FlatFileParseException
            } catch (IOException | CsvValidationException e) {
                closeReader();
                throw new RuntimeException("Failed to read CSV file", e);
            }
        }
    }

    @Override
    public T read() {
        try {
            lazyInitialize();

            String[] row = csvReader.readNext();

            if (row == null) {
                closeReader();
                return null;
            }

            currentRowIndex++;

            if (isEmptyRow(row)) {
                log.debug("Skipping empty row at index {}", currentRowIndex);
                return read();
            }

            return parseRow(row, currentRowIndex);

        } catch (FlatFileParseException e) {
            throw e; // already wrapped, don't double-wrap
        } catch (Exception e) {
            // Wrap ALL exceptions (including InvalidTemplateException from lazyInitialize)
            // in FlatFileParseException so Spring Batch routes them to onSkipInRead.
            throw new FlatFileParseException(
                    "Error parsing CSV row " + currentRowIndex + ": " + e.getMessage(),
                    e,
                    "",
                    currentRowIndex
            );
        }
    }

    private boolean isEmptyRow(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private T parseRow(String[] row, int rowNum) throws Exception {
        T instance = type.getDeclaredConstructor().newInstance();

        for (Map.Entry<String, Field> entry : fieldMap.entrySet()) {
            String headerName = entry.getKey();
            Field field = entry.getValue();
            Integer columnIndex = headerIndexMap.get(headerName);

            if (columnIndex == null || columnIndex >= row.length) continue;

            String cellValue = row[columnIndex];
            if (cellValue == null || cellValue.trim().isEmpty()) continue;

            field.setAccessible(true);
            Object value = parseCellValue(cellValue.trim(), field, rowNum, columnIndex);
            field.set(instance, value);
        }

        return instance;
    }

    private Object parseCellValue(String value, Field field, int rowNum, int columnIndex) {
        Class<?> fieldType = field.getType();

        try {
            if (fieldType == String.class) {
                return value;
            } else if (fieldType == Integer.class || fieldType == int.class) {
                return Integer.valueOf(value);
            } else if (fieldType == Long.class || fieldType == long.class) {
                return Long.valueOf(value);
            } else if (fieldType == Double.class || fieldType == double.class) {
                return Double.valueOf(value);
            } else if (fieldType == Boolean.class || fieldType == boolean.class) {
                return Boolean.valueOf(value);
            } else if (fieldType == LocalDate.class) {
                String pattern = datePattern;
                if (field.isAnnotationPresent(ExcelDateFormat.class)) {
                    pattern = field.getAnnotation(ExcelDateFormat.class).pattern();
                }
                return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern));
            } else if (fieldType.isEnum()) {
                return parseEnum(fieldType, value, rowNum, columnIndex);
            }

            return value;
        } catch (Exception e) {
            String headerName = headers[columnIndex];
            throw new RuntimeException(
                    String.format(
                            "Failed to parse value '%s' for field '%s' (column '%s') " +
                            "at row %d, column %d: %s",
                            value, field.getName(), headerName,
                            rowNum, columnIndex, e.getMessage()
                    ),
                    e
            );
        }
    }

    private Object parseEnum(Class<?> enumType, String value, int rowNum, int columnIndex) {
        try {
            var method = enumType.getMethod("fromDisplayName", String.class);
            Object result = method.invoke(null, value);
            if (result != null) return result;
        } catch (Exception ignored) {}

        try {
            var method = enumType.getMethod("valueOf", String.class);
            return method.invoke(null, value);
        } catch (Exception e1) {
            try {
                var normalized = value.toUpperCase().replace(" ", "_");
                var method = enumType.getMethod("valueOf", String.class);
                return method.invoke(null, normalized);
            } catch (Exception e2) {
                for (Object constant : enumType.getEnumConstants()) {
                    if (constant.toString().equalsIgnoreCase(value)) {
                        return constant;
                    }
                }
            }
        }

        throw new IllegalArgumentException(
                String.format(
                        "Cannot convert '%s' to enum %s at row %d, column %d. Valid values: %s",
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

    /**
     * Validates that the CSV headers match the fields annotated with @ExcelCellName.
     * Called from lazyInitialize() (not the constructor) so exceptions surface in read().
     */
    private void validateHeaders() {
        Set<String> csvHeaders = new HashSet<>();
        for (String header : headers) {
            csvHeaders.add(header.trim());
        }

        List<String> expectedHeaders = getExpectedHeaders(type);

        List<String> missingHeaders = expectedHeaders.stream()
                .filter(h -> !csvHeaders.contains(h))
                .toList();

        List<String> extraHeaders = csvHeaders.stream()
                .filter(h -> !expectedHeaders.contains(h))
                .toList();

        if (!missingHeaders.isEmpty() || !extraHeaders.isEmpty()) {
            StringBuilder errorMsg = new StringBuilder("Invalid CSV template. ");
            if (!missingHeaders.isEmpty()) {
                errorMsg.append("Missing headers: ").append(missingHeaders).append(". ");
            }
            if (!extraHeaders.isEmpty()) {
                errorMsg.append("Extra headers: ").append(extraHeaders).append(".");
            }
            throw new InvalidTemplateException(errorMsg.toString().trim());
        }

        log.info("✅ CSV headers validated successfully");
    }

    private String detectDatePattern(@NotNull Class<T> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(ExcelDateFormat.class)) {
                return field.getAnnotation(ExcelDateFormat.class).pattern();
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

    private void closeReader() {
        if (csvReader != null) {
            try {
                csvReader.close();
                log.debug("CSV reader closed successfully");
            } catch (IOException e) {
                log.warn("Failed to close CSV reader: {}", e.getMessage());
            } finally {
                csvReader = null;
            }
        }
    }

    @Override
    public void destroy() {
        closeReader();
    }
}