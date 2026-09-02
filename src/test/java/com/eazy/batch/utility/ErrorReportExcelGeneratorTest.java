package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorReportExcelGeneratorTest {

    @Test
    void generate_withEmptyList_returnsNull() {
        assertThat(ErrorReportExcelGenerator.generate(List.of())).isNull();
    }

    @Test
    void generate_withNullList_returnsNull() {
        assertThat(ErrorReportExcelGenerator.generate(null)).isNull();
    }

    @Test
    void generate_producesReadableXlsxWithHeaderAndRows() throws Exception {
        List<BatchSkippedItem<?>> items = List.of(
                new BatchSkippedItem<>("row-1", "READ", "malformed CSV"),
                new BatchSkippedItem<>(null, "READ", "unreadable line"),
                new BatchSkippedItem<>("row-3", "WRITE", "constraint violation")
        );

        byte[] bytes = ErrorReportExcelGenerator.generate(items);

        assertThat(bytes).isNotNull().isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Errors");
            assertThat(sheet).isNotNull();

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Item");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Phase");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Reason");

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(0).getStringCellValue()).isEqualTo("row-1");
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("READ");
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("malformed CSV");

            // null item (a READ-phase failure with no parsed item yet) should
            // render as a placeholder, not throw or leave a blank/null cell.
            Row row2 = sheet.getRow(2);
            assertThat(row2.getCell(0).getStringCellValue()).isEqualTo("(none - read failure)");

            assertThat(sheet.getLastRowNum()).isEqualTo(3); // header + 3 data rows = indices 0..3
        }
    }
}
