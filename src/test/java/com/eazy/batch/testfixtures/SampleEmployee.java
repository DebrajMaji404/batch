package com.eazy.batch.testfixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

/**
 * Minimal JPA entity used only to exercise {@code @BatchExportJob} code
 * generation in tests - not part of the library's public API.
 */
@Data
@Entity
public class SampleEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
}
