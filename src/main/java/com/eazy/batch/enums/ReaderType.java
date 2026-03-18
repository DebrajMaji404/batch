package com.eazy.batch.enums;

import lombok.Getter;

/**
 * Supported reader types for batch processing
 */
@Getter
public enum ReaderType {
    FILE("File-based reader"),
    DATABASE("Database reader"),
    API("REST API reader"),
    KAFKA("Kafka stream reader");

    private final String description;

    ReaderType(String description) {
        this.description = description;
    }

}