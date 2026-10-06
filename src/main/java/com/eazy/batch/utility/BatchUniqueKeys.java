package com.eazy.batch.utility;

import com.eazy.batch.report.BatchRowTracker;
import com.eazy.batch.service.BatchContext;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects rows that repeat an earlier row's key inside one uploaded file
 * ({@code @BatchJob(uniqueKey = {...})}). Used by generated processors.
 */
public final class BatchUniqueKeys {

    private static final Map<Long, Map<String, Integer>> SEEN = new ConcurrentHashMap<>();

    private BatchUniqueKeys() {
    }

    /** @return an error message when {@code item} repeats an earlier row's key, otherwise null */
    public static String check(Object item, String... fields) {
        Long jobId = BatchContext.current().jobExecutionId();
        Integer row = BatchRowTracker.rowOf(item);
        if (jobId == null || row == null) return null;

        String key = keyOf(item, fields);
        if (key == null) return null; // incomplete key: leave to normal validation

        Integer first = SEEN.computeIfAbsent(jobId, id -> new ConcurrentHashMap<>()).putIfAbsent(key, row);
        // the same row can be seen again when Spring Batch rescans a chunk - that is not a duplicate
        if (first == null || first.equals(row)) return null;
        return "Duplicate of row " + first + " (" + String.join(", ", fields) + " = " + key + ")";
    }

    static String keyOf(Object item, String[] fields) {
        StringJoiner joiner = new StringJoiner(" | ");
        for (String name : fields) {
            Object value = read(item, name);
            if (value == null || value.toString().isBlank()) return null;
            joiner.add(value.toString().trim().toLowerCase());
        }
        return joiner.toString();
    }

    private static Object read(Object item, String name) {
        for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(item);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    public static void clear(Long jobExecutionId) {
        if (jobExecutionId != null) SEEN.remove(jobExecutionId);
    }
}
