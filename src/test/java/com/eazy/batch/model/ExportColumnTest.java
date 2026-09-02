package com.eazy.batch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportColumnTest {

    @Data
    @AllArgsConstructor
    static class Manager {
        private String email;
    }

    @Data
    @AllArgsConstructor
    static class Employee {
        private String name;
        private Manager manager; // may be null
    }

    @Test
    void getValue_returnsExtractedValue() {
        ExportColumn<Employee> col = ExportColumn.col("Name", Employee::getName);

        assertThat(col.getValue(new Employee("Alice", null))).isEqualTo("Alice");
    }

    @Test
    void getValue_withNullEntity_returnsEmptyString() {
        ExportColumn<Employee> col = ExportColumn.col("Name", Employee::getName);

        assertThat(col.getValue(null)).isEqualTo("");
    }

    @Test
    void getValue_withNullExtractedValue_returnsEmptyString() {
        ExportColumn<Employee> col = ExportColumn.col("Name", Employee::getName);

        assertThat(col.getValue(new Employee(null, null))).isEqualTo("");
    }

    @Test
    void getValue_withBrokenNestedExtractor_returnsEmptyStringNotException() {
        // e.getManager().getEmail() throws NPE when manager is null - this is
        // exactly the "broken column extractor" scenario the fix targets:
        // it must not blow up the whole export, just render a blank cell.
        ExportColumn<Employee> col = ExportColumn.col("Manager Email", e -> e.getManager().getEmail());

        assertThat(col.getValue(new Employee("Bob", null))).isEqualTo("");
    }

    @Test
    void getValue_withWorkingNestedExtractor_returnsNestedValue() {
        ExportColumn<Employee> col = ExportColumn.col("Manager Email", e -> e.getManager().getEmail());

        assertThat(col.getValue(new Employee("Bob", new Manager("boss@example.com"))))
                .isEqualTo("boss@example.com");
    }

    @Test
    void getHeader_returnsConfiguredHeader() {
        ExportColumn<Employee> col = ExportColumn.col("Name", Employee::getName);

        assertThat(col.getHeader()).isEqualTo("Name");
    }
}
