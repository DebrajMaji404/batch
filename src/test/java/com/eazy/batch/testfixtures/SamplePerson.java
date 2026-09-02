package com.eazy.batch.testfixtures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal plain entity used only to exercise {@code @BatchJob} code
 * generation in tests - not part of the library's public API. Deliberately
 * NOT a JPA @Entity, since {@code SimpleBatchProcessor.save()} in this
 * fixture just no-ops rather than actually persisting anything.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SamplePerson {
    private String name;
    private Integer age;
}
