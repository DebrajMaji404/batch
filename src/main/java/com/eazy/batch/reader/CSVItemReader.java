package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.exception.InvalidTemplateException;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.poiji.annotation.ExcelCellName;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CSV Item Reader with header validation.
 *
 * <p>NEW: implements {@link ItemStreamReader} for restart support. Row
 * position is checkpointed into the {@link ExecutionContext} after every
 * chunk (via Spring Batch's own step lifecycle calling {@link #update}), and
 * on a restart {@link #open} fast-forwards past already-processed rows by
 * re-reading and discarding them (CSV has no native random-seek, so this is
 * the standard technique for line-based restartable readers). Previously
 * this reader only implemented plain {@code ItemReader}, so a step failure
 * always restarted the whole file from row 0 regardless of how much had
 * already been committed.</p>
 *
 * <p>File I/O is deferred from the constructor to {@link #open}, matching
 * the {@code ItemStream} contract (a reader should be constructible without
 * side effects, and only touch its resource once the step is actually
 * executing) - the constructor now only validates arguments and builds the
 * (pure, no I/O) field-mapping metadata.</p>
 */
@Slf4j
public class CSVItemReader<T> implements ItemStreamReader<T> {

    private static final String ROW_INDEX_KEY = "csv.reader.row.index";

    private final Resource resource;
    private final Class<T> type;
    private final String datePattern;
    private final Map<String, Field> fieldMap;
    private final Map<String, Integer> headerIndexMap = new HashMap<>();

    private CSVReader csvReader;
    private String[] headers;
    private int currentRowIndex = 0;

    public CSVItemReader(@NotNull Resource resource, Class<T> type) {
        this.resource = resource;
        this.type = type;
        this.datePattern = detectDatePattern(type);
        this.fieldMap = buildFieldMap(type);
    }

    // ─────────────────────────────────────────────────────────────────
    // ItemStream lifecycle
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void open(@NonNull ExecutionContext executionContext) throws ItemStreamException {
        try {
            this.csvReader = new CSVReader(new FileReader(resource.getFile()));

            this.headers = csvReader.readNext();
            if (headers == null || headers.length == 0) {
                throw new InvalidTemplateException("CSV file does not contain headers");
            }
            for (int i = 0; i < headers.length; i++) {
                headerIndexMap.put(headers[i].trim(), i);
            }
            validateHeaders();

            log.info("✅ CSV reader initialized with {} columns", headers.length);

            // Restart support: fast-forward past already-processed rows.
            if (executionContext.containsKey(ROW_INDEX_KEY)) {
                long alreadyRead = executionContext.getLong(ROW_INDEX_KEY);
                log.info("Restarting CSV reader - skipping {} already-processed row(s)", alreadyRead);
                for (long i = 0; i < alreadyRead; i++) {
                    if (csvReader.readNext() == null) {
                        log.warn("CSV file has fewer rows than the restart checkpoint ({}); stopping fast-forward early", alreadyRead);
                        break;
                    }
                }
                this.currentRowIndex = (int) alreadyRead;
            }
        } catch (IOException | CsvValidationException e) {
            throw new ItemStreamException("Failed to open CSV file: " + e.getMessage(), e);
        }
    }

    @Override
    public void update(@NonNull ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(ROW_INDEX_KEY, currentRowIndex);
    }

    @Override
    public void close() throws ItemStreamException {
        closeReader();
    }

    // ─────────────────────────────────────────────────────────────────
    // Reading
    // ─────────────────────────────────────────────────────────────────

    @Override
    public T read() {
        if (csvReader == null) {
            throw new IllegalStateException("CSV reader not open - open() must be called before read()");
        }

        try {
            String[] row = csvReader.readNext();

            if (row == null) {
                // End of file
                return null;
            }

            currentRowIndex++;

            // Skip empty rows
            if (isEmptyRow(row)) {
                log.debug("Skipping empty row at index {}", currentRowIndex);
                return read();
            }

            return parseRow(row, currentRowIndex);

        } catch (Exception e) {
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

            if (columnIndex == null || columnIndex >= row.length) {
                continue;
            }

            String cellValue = row[columnIndex];
            if (cellValue == null || cellValue.trim().isEmpty()) {
                continue;
            }

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
            // Try normalized
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

    private void validateHeaders() {
        Set<String> csvHeaders = new HashSet<>();
        for (String header : headers) {
            csvHeaders.add(header.trim());
        }

        List<String> expectedHeaders = getExpectedHeaders(type);
        List<String> missingHeaders = expectedHeaders.stream()
                .filter(header -> !csvHeaders.contains(header))
                .toList();

        List<String> extraHeaders = csvHeaders.stream()
                .filter(header -> !expectedHeaders.contains(header))
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
}
