package com.eazy.batch.enums;

import lombok.Getter;

/**
 * Supported file types for batch processing
 */
@Getter
public enum FileType {
    EXCEL("Excel files (.xlsx, .xls)"),
    CSV("CSV files (.csv)"),
    JSON("JSON files (.json)"),
    XML("XML files (.xml)");

    private final String description;

    FileType(String description) {
        this.description = description;
    }

}
