package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.enums.FileType;
import com.eazy.batch.exception.InvalidTemplateException;
import com.eazy.batch.report.BatchRowTracker;
import com.poiji.annotation.ExcelCellName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reader for {@code @BatchJob(fileType = JSON)} and {@code (fileType = XML)} uploads.
 *
 * <p>The file is a list of flat records (see {@link JsonRecordSource} / {@link XmlRecordSource}).
 * A record's field names are matched to the DTO's {@code @ExcelCellName} headers - or the Java
 * field names - ignoring case, spaces and punctuation, so {@code "Student Name"},
 * {@code "student_name"} and {@code "studentName"} all find the same field. A name that matches
 * nothing rejects that record (a typo must not silently import as an empty value).</p>
 *
 * <p>Row numbers: the Nth record of the file is row N (there is no header row), in the job's
 * report as well. Restartable like the CSV reader: the record position is checkpointed per chunk.</p>
 */
@Slf4j
public class StructuredFileItemReader<T> implements ItemStreamReader<T> {

    private static final String INDEX_KEY = "structured.reader.record.index";

    private final Resource resource;
    private final Class<T> type;
    private final FileType format;
    private final Map<String, Field> fieldsByKey = new HashMap<>();
    private final Map<Field, String> labels = new LinkedHashMap<>();
    private final String datePattern;

    private RecordSource source;
    private int recordIndex;
    private int lastRowNumber;
    private boolean finished;

    public StructuredFileItemReader(Resource resource, Class<T> type, FileType format) {
        if (format != FileType.JSON && format != FileType.XML) {
            throw new IllegalArgumentException("StructuredFileItemReader handles JSON and XML, not " + format);
        }
        this.resource = resource;
        this.type = type;
        this.format = format;

        String pattern = "yyyy-MM-dd";
        for (Field field : type.getDeclaredFields()) {
            ExcelCellName name = field.getAnnotation(ExcelCellName.class);
            if (name == null) continue;
            labels.put(field, name.value());
            fieldsByKey.put(key(name.value()), field);
            if (field.isAnnotationPresent(ExcelDateFormat.class)) {
                pattern = field.getAnnotation(ExcelDateFormat.class).pattern();
            }
        }
        // the Java field name is accepted too (lower priority than a header with the same key)
        for (Field field : labels.keySet()) {
            fieldsByKey.putIfAbsent(key(field.getName()), field);
        }
        this.datePattern = pattern;
    }

    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            source = openSource(resource.getFile().toPath(), format);
            if (executionContext.containsKey(INDEX_KEY)) {
                long alreadyRead = executionContext.getLong(INDEX_KEY);
                log.info("Restarting {} reader - skipping {} already-processed record(s)", format, alreadyRead);
                for (long i = 0; i < alreadyRead; i++) {
                    if (source.next() == null) break;
                }
                recordIndex = (int) alreadyRead;
                BatchRowTracker.onRestart(recordIndex);
            }
        } catch (IOException e) {
            closeQuietly();
            throw new InvalidTemplateException(e.getMessage());
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(INDEX_KEY, recordIndex);
    }

    @Override
    public void close() throws ItemStreamException {
        closeQuietly();
    }

    /** Row (record number, first record = 1) of the record most recently returned - or failed on - by {@link #read()}. */
    public int getLastRowNumber() {
        return lastRowNumber;
    }

    @Override
    public T read() {
        if (source == null) {
            throw new IllegalStateException(format + " reader not open - open() must be called before read()");
        }
        if (finished) return null;

        int row = recordIndex + 1;
        RecordSource.Record record;
        try {
            record = source.next();
        } catch (Exception e) {
            // A malformed file cannot be read past this point: report it once, then end the data.
            finished = true;
            lastRowNumber = row;
            String message = "Cannot read " + format + " record " + row + ": " + e.getMessage();
            BatchRowTracker.onReadFailed(row, message);
            throw new FlatFileParseException(message, e, "", row);
        }
        if (record == null) return null;

        recordIndex++;
        lastRowNumber = recordIndex;
        if (record.error() == null && isEmpty(record)) {
            return read();
        }

        try {
            if (record.error() != null) throw new IllegalArgumentException(record.error());
            T item = bind(record.values());
            BatchRowTracker.onRead(item, lastRowNumber);
            return item;
        } catch (Exception e) {
            String message = "Error parsing " + format + " record " + lastRowNumber + ": " + e.getMessage();
            BatchRowTracker.onReadFailed(lastRowNumber, message);
            throw new FlatFileParseException(message, e, "", lastRowNumber);
        }
    }

    private static boolean isEmpty(RecordSource.Record record) {
        return record.values().values().stream().allMatch(v -> v == null || v.isBlank());
    }

    private T bind(Map<String, String> values) throws ReflectiveOperationException {
        List<String> unknown = new ArrayList<>();
        Map<Field, String> toSet = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Field field = fieldsByKey.get(key(entry.getKey()));
            if (field == null) {
                unknown.add(entry.getKey());
            } else if (entry.getValue() != null && !entry.getValue().isBlank()) {
                toSet.put(field, entry.getValue().trim());
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown field(s) " + unknown + "; expected " + labels.values());
        }

        T instance = type.getDeclaredConstructor().newInstance();
        for (Map.Entry<Field, String> entry : toSet.entrySet()) {
            Field field = entry.getKey();
            field.setAccessible(true);
            field.set(instance, FieldValueParser.parse(entry.getValue(), field, datePattern, labels.get(field)));
        }
        return instance;
    }

    private void closeQuietly() {
        if (source != null) {
            try {
                source.close();
            } catch (IOException e) {
                log.warn("Failed to close {} reader: {}", format, e.getMessage());
            } finally {
                source = null;
            }
        }
    }

    // ─── used by the upload guard ────────────────────────────────────

    static RecordSource openSource(Path file, FileType format) throws IOException {
        if (format == FileType.XML) {
            return new XmlRecordSource(Files.newInputStream(file));
        }
        return new JsonRecordSource(new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)));
    }

    /** Number of records in a JSON/XML file, or -1 when it cannot be read. */
    public static long countRecords(Path file, FileType format) {
        try (RecordSource records = openSource(file, format)) {
            long count = 0;
            while (records.next() != null) count++;
            return count;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
