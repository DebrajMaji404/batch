package com.eazy.batch.testfixtures;

import lombok.Data;

import java.util.List;

/**
 * Minimal wrapper used only to exercise {@code @BatchJob} code generation in
 * tests - not part of the library's public API.
 */
@Data
public class SampleWrapper {
    private List<SamplePerson> people;

    public SampleWrapper(List<SamplePerson> people) {
        this.people = people;
    }
}
