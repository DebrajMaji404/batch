package com.eazy.batch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO to track skipped items during batch processing
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchSkippedItem<T> {
    private T item;
    private String phase; // READ, PROCESS, WRITE, JOB
    private String reason;
    /** Row number in the uploaded file (header = row 1), or null when unknown. */
    private Integer rowNumber;

    public BatchSkippedItem(T item, String phase, String reason) {
        this(item, phase, reason, null);
    }
}
