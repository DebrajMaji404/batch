package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.exception.InvalidTemplateException;
import com.poiji.annotation.ExcelCellName;
import com.poiji.bind.Poiji;
import com.poiji.exception.PoijiException;
import com.poiji.option.PoijiOptions;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;


public class ExcelItemReaderWithHeaderValidation<T> implements ItemReader<T> {
    private Iterator<T> iterator;
    private List<T> data;
    private int currentIndex = 0;
    private final File file;
    private final Class<T> type;
    private final PoijiOptions options;
    private boolean initialized = false;

    public ExcelItemReaderWithHeaderValidation(@NotNull Resource resource, Class<T> type) {
        try {
            this.file = resource.getFile();
            this.type = type;

            // Read and validate headers
            validateHeaders(file, type);

            String datePattern = detectDatePattern(type);

            this.options = PoijiOptions.PoijiOptionsBuilder.settings()
                    .datePattern(datePattern)
                    .preferNullOverDefault(true)
                    .withCasting(new CustomCasting(datePattern))
                    .build();

        } catch (IOException | InvalidFormatException e) {
            throw new RuntimeException("Failed to read Excel file", e);
        }
    }

    private void lazyInitialize() {
        if (!initialized) {
            try {
                // This is where PoijiException can occur
                this.data = Poiji.fromExcel(file, type, options);
                this.iterator = data.iterator();
                this.initialized = true;
            } catch (PoijiException e) {
                // Extract row information from the exception message
                String message = e.getMessage();
                int rowNumber = extractRowNumber(message);

                // Throw FlatFileParseException which Spring Batch recognizes
                throw new FlatFileParseException(
                        "Error parsing Excel row: " + message,
                        e,
                        "", // input (not available)
                        rowNumber
                );
            }
        }
    }

    private int extractRowNumber(String message) {
        try {
            // Extract row number from message like "Cannot convert 'OBCD' to enum CasteCategories at row 3, column 14"
            if (message.contains("at row")) {
                String[] parts = message.split("at row ");
                if (parts.length > 1) {
                    String rowPart = parts[1].split(",")[0].trim();
                    return Integer.parseInt(rowPart);
                }
            }
        } catch (Exception e) {
            // If extraction fails, return -1
        }
        return -1;
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
            List<String> missingHeaders = expectedHeaders.parallelStream()
                    .filter(header -> !excelHeaders.contains(header))
                    .toList();

            // Check for extra headers (in Excel but not expected)
            List<String> extraHeaders = excelHeaders.parallelStream()
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
        // Check if any field has ExcelDateFormat annotation
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(ExcelDateFormat.class)) {
                ExcelDateFormat dateFormat = field.getAnnotation(ExcelDateFormat.class);
                return dateFormat.pattern();
            }
        }
        return "yyyy-MM-dd"; // default
    }

    private @NotNull Set<Class<?>> detectEnumTypes(@NotNull Class<T> type) {
        Set<Class<?>> enumTypes = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType().isEnum()) {
                enumTypes.add(field.getType());
            }
        }
        return enumTypes;
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

    @Override
    public T read() {
        // Lazy initialization happens here, during the read phase
        // This ensures PoijiExceptions are caught by Spring Batch's skip logic
        lazyInitialize();

        return iterator.hasNext() ? iterator.next() : null;
    }
}