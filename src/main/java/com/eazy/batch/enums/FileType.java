package com.eazy.batch.enums;

import lombok.Getter;

/**
 * Supported upload file types for {@code @BatchJob}. EXCEL and CSV have a header row; JSON and
 * XML are lists of flat records (see {@code StructuredFileItemReader}).
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
