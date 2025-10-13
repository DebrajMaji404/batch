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
    private String phase; // READ, PROCESS, WRITE
    private String reason;
}