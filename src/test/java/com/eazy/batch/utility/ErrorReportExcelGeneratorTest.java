package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.report.RowStatus;
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
    void generate_usesOriginalTemplateColumnsFollowedByStatusAndReason() throws Exception {
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
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Status");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Reason");
            // No leftover generic "Item" column.
            assertThat(header.getCell(4)).isNull();

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(0).getStringCellValue()).isEqualTo("xx");
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("desc-xx");
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("FAILED");
            assertThat(row1.getCell(3).getStringCellValue()).isEqualTo("[PROCESS] already exists: xx");

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
            assertThat(readRow.getCell(2).getStringCellValue()).isEqualTo("FAILED");
            assertThat(readRow.getCell(3).getStringCellValue()).isEqualTo("[READ] malformed row");

            Row writeRow = sheet.getRow(3);
            assertThat(writeRow.getCell(0).getStringCellValue()).isEmpty();
            assertThat(writeRow.getCell(3).getStringCellValue()).isEqualTo("[WRITE] constraint violation");
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
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Status");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Reason");

            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("(none - read failure)");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("raw-line-3");
        }
    }

    @Test
    void generate_jobLevelFailureProducesSingleExplanatoryRow() throws Exception {
        List<BatchSkippedItem<?>> items = List.of(
                new BatchSkippedItem<>(null, "JOB", "Missing headers: [Description]"));

        byte[] bytes = ErrorReportExcelGenerator.generate(items);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Errors");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("(not row-specific)");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("FAILED");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("[JOB] Missing headers: [Description]");
        }
    }

    @Test
    void builder_writesEveryStatusIntoTheStatusColumn() throws Exception {
        try (ErrorReportExcelGenerator.Builder builder =
                     new ErrorReportExcelGenerator.Builder(SampleUploadDto.class, "All rows")) {
            builder.addRow(new SampleUploadDto("a", "1"), RowStatus.SUCCESS, "");
            builder.addRow(new SampleUploadDto("b", "2"), RowStatus.FAILED, "bad");
            builder.addRow(new SampleUploadDto("c", "3"), RowStatus.NOT_IMPORTED, "rolled back");
            assertThat(builder.rowCount()).isEqualTo(3);

            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(builder.build()))) {
                Sheet sheet = workbook.getSheet("All rows");
                assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("SUCCESS");
                assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("FAILED");
                assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("NOT_IMPORTED");
                assertThat(sheet.getRow(3).getCell(3).getStringCellValue()).isEqualTo("rolled back");
            }
        }
    }
}
