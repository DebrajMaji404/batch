package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import com.poiji.annotation.ExcelCellName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorReportExcelGeneratorTest {

    /** Stands in for a real upload DTO - two template columns. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class SampleUploadDto {
        @ExcelCellName("name")
        private String name;

        @ExcelCellName("description")
        private String description;
    }

    /** Stands in for the mapped entity handed to WRITE-phase skips - no @ExcelCellName. */
    @Data
    @AllArgsConstructor
    static class SampleEntity {
        private String name;
    }

    @Test
    void generate_withEmptyList_returnsNull() {
        assertThat(ErrorReportExcelGenerator.generate(List.of())).isNull();
    }

    @Test
    void generate_withNullList_returnsNull() {
        assertThat(ErrorReportExcelGenerator.generate(null)).isNull();
    }

    @Test
    void generate_usesOriginalTemplateColumnsFollowedByPhaseAndReason() throws Exception {
        List<BatchSkippedItem<?>> items = List.of(
                new BatchSkippedItem<>(new SampleUploadDto("xx", "desc-xx"), "PROCESS", "already exists: xx"),
                new BatchSkippedItem<>(new SampleUploadDto("aa", "desc-aa"), "PROCESS", "already exists: aa")
        );

        byte[] bytes = ErrorReportExcelGenerator.generate(items);
        assertThat(bytes).isNotNull().isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Errors");
            assertThat(sheet).isNotNull();

            // Header mirrors the upload template's own columns (declared-field
            // order, same as the readers derive), then the two diagnostics.
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("name");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("description");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Phase");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Reason");
            // No leftover generic "Item" column.
            assertThat(header.getCell(4)).isNull();

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(0).getStringCellValue()).isEqualTo("xx");
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("desc-xx");
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("PROCESS");
            assertThat(row1.getCell(3).getStringCellValue()).isEqualTo("already exists: xx");

            Row row2 = sheet.getRow(2);
            assertThat(row2.getCell(0).getStringCellValue()).isEqualTo("aa");
            assertThat(row2.getCell(1).getStringCellValue()).isEqualTo("desc-aa");

            assertThat(sheet.getLastRowNum()).isEqualTo(2); // header + 2 data rows
        }
    }

    @Test
    void generate_leavesTemplateColumnsBlankForReadAndWritePhaseSkips() throws Exception {
        // Mixed job: a PROCESS skip carries the DTO (so it defines the columns),
        // while a READ skip has no item at all and a WRITE skip carries the
        // mapped entity - neither can populate the template columns.
        List<BatchSkippedItem<?>> items = List.of(
                new BatchSkippedItem<>(new SampleUploadDto("xx", "desc-xx"), "PROCESS", "already exists"),
                new BatchSkippedItem<>(null, "READ", "malformed row"),
                new BatchSkippedItem<>(new SampleEntity("zz"), "WRITE", "constraint violation")
        );

        byte[] bytes = ErrorReportExcelGenerator.generate(items);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Errors");

            Row readRow = sheet.getRow(2);
            assertThat(readRow.getCell(0).getStringCellValue()).isEmpty();
            assertThat(readRow.getCell(1).getStringCellValue()).isEmpty();
            assertThat(readRow.getCell(2).getStringCellValue()).isEqualTo("READ");
            assertThat(readRow.getCell(3).getStringCellValue()).isEqualTo("malformed row");

            Row writeRow = sheet.getRow(3);
            assertThat(writeRow.getCell(0).getStringCellValue()).isEmpty();
            assertThat(writeRow.getCell(2).getStringCellValue()).isEqualTo("WRITE");
            assertThat(writeRow.getCell(3).getStringCellValue()).isEqualTo("constraint violation");
        }
    }

    @Test
    void generate_fallsBackToGenericItemColumnWhenNoDtoAvailable() throws Exception {
        // A job whose only failures were READ-phase parse errors has no DTO on
        // any item, so there are no template columns to mirror.
        List<BatchSkippedItem<?>> items = List.of(
                new BatchSkippedItem<>(null, "READ", "malformed row"),
                new BatchSkippedItem<>("raw-line-3", "READ", "unparseable")
        );

        byte[] bytes = ErrorReportExcelGenerator.generate(items);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Errors");

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Item");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Phase");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Reason");

            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("(none - read failure)");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("raw-line-3");
        }
    }
}
