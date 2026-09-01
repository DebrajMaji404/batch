package com.eazy.batch.model;

import java.util.function.Function;

/**
 * Defines a single column in an export file.
 *
 * <p>Use the static factory {@link #col} to create columns with method references
 * so you never write raw SQL mappers again:</p>
 *
 * <pre>{@code
 * List.of(
 *     ExportColumn.col("Name",          Employee::getName),
 *     ExportColumn.col("Email",         Employee::getEmail),
 *     ExportColumn.col("Manager Email", e -> e.getManager().getEmail()),   // nested
 *     ExportColumn.col("Department",    e -> e.getDept().getName())        // nested
 * )
 * }</pre>
 *
 * @param <T> Entity type being exported
 */
public class ExportColumn<T> {

    private final String header;
    private final Function<T, Object> valueExtractor;

    private ExportColumn(String header, Function<T, Object> valueExtractor) {
        this.header = header;
        this.valueExtractor = valueExtractor;
    }

    /**
     * Factory method to create a column definition.
     *
     * @param header         Column header shown in the file
     * @param valueExtractor Function to extract value from entity (use method references or lambdas)
     * @param <T>            Entity type
     * @return ExportColumn instance
     */
    public static <T> ExportColumn<T> col(String header, Function<T, Object> valueExtractor) {
        return new ExportColumn<>(header, valueExtractor);
    }

    public String getHeader() {
        return header;
    }

    public Object getValue(T entity) {
        if (entity == null) return "";
        try {
            Object value = valueExtractor.apply(entity);
            return value != null ? value : "";
        } catch (Exception e) {
            return "";  // safe fallback if nested value is null (e.g. e.getManager() is null)
        }
    }
}