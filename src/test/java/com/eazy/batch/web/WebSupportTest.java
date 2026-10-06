package com.eazy.batch.web;

import com.eazy.batch.annotation.ExcelSampleData;
import com.eazy.batch.dto.BatchProgressMessage;
import com.eazy.batch.enums.FileType;
import com.eazy.batch.enums.ReportType;
import com.eazy.batch.report.BatchReportFile;
import com.eazy.batch.report.LocalReportStorage;
import com.eazy.batch.service.BatchRunRegistry;
import com.poiji.annotation.ExcelCellName;
import lombok.Data;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSupportTest {

    enum Level { LOW, HIGH }

    @Data
    public static class Dto {
        @ExcelCellName("Name")
        @ExcelSampleData("Asha")
        @jakarta.validation.constraints.NotBlank
        private String name;
        @ExcelCellName("Level")
        private Level level;
        @ExcelCellName("Age")
        private Integer age;
    }

    @Test
    void templateHasHeadersSampleAndInstructions() throws Exception {
        byte[] bytes = TemplateGenerator.generate(Dto.class, FileType.EXCEL, "Data");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet data = wb.getSheet("Data");
            assertThat(data.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Name");
            assertThat(data.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Level");
            assertThat(data.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Asha");
            assertThat(data.getRow(1).getCell(1).getStringCellValue()).isEqualTo("LOW");
            Sheet help = wb.getSheet("Instructions");
            assertThat(help.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Yes");
            assertThat(help.getRow(2).getCell(4).getStringCellValue()).isEqualTo("LOW, HIGH");
        }
    }

    @Test
    void csvTemplate() {
        String csv = new String(TemplateGenerator.generate(Dto.class, FileType.CSV, null), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\"Name\",\"Level\",\"Age\"");
    }

    @Test
    void jsonTemplateHasTypedSamples() {
        String json = new String(TemplateGenerator.generate(Dto.class, FileType.JSON, null), StandardCharsets.UTF_8);
        assertThat(json).contains("\"Name\": \"Asha\"").contains("\"Level\": \"LOW\"").contains("\"Age\": 1");
        assertThat(TemplateGenerator.contentType(FileType.JSON)).isEqualTo("application/json");
        assertThat(TemplateGenerator.extension(FileType.JSON)).isEqualTo("json");
    }

    @Test
    void xmlTemplateUsesValidElementNames() {
        String xml = new String(TemplateGenerator.generate(Dto.class, FileType.XML, null), StandardCharsets.UTF_8);
        assertThat(xml).contains("<rows>").contains("<Name>Asha</Name>").contains("<Age>1</Age>");
        assertThat(TemplateGenerator.xmlName("Student Name")).isEqualTo("Student_Name");
        assertThat(TemplateGenerator.xmlName("1st")).isEqualTo("_1st");
    }

    @Test
    void guardCountsJsonAndXmlRecords(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("a.json");
        Files.writeString(json, "[{\"a\":1},{\"a\":2},{\"a\":3}]");
        new UploadGuard(1, 3).checkRows(json, FileType.JSON, 0, null);
        assertThatThrownBy(() -> new UploadGuard(1, 2).checkRows(json, FileType.JSON, 0, null))
                .hasMessageContaining("3 rows");
    }

    @Test
    void guardRejectsWrongTypeEmptyAndOversize() {
        UploadGuard guard = new UploadGuard(1, 0);
        assertThatThrownBy(() -> guard.checkName("a.txt", FileType.EXCEL)).isInstanceOf(UploadGuard.UploadRejectedException.class);
        guard.checkName("a.XLSX", FileType.EXCEL);
        assertThatThrownBy(() -> guard.checkSize(0)).isInstanceOf(UploadGuard.UploadRejectedException.class);
        assertThatThrownBy(() -> guard.checkSize(2L * 1024 * 1024)).hasMessageContaining("1 MB");
    }

    @Test
    void guardCountsCsvRows(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("a.csv");
        Files.writeString(csv, "h\n1\n2\n3\n");
        new UploadGuard(1, 3).checkRows(csv, FileType.CSV, 0, null);
        assertThatThrownBy(() -> new UploadGuard(1, 2).checkRows(csv, FileType.CSV, 0, null))
                .hasMessageContaining("3 rows");
    }

    @Test
    void localStorageStoresAndRefusesTraversal(@TempDir Path dir) {
        LocalReportStorage storage = new LocalReportStorage(dir.toString(), "http://h/batch/reports");
        String url = storage.store(new BatchReportFile(new byte[]{1, 2}, "r.xlsx", "x", "job", 7L, ReportType.ALL));
        assertThat(url).isEqualTo("http://h/batch/reports/7/r.xlsx");
        assertThat(storage.resolve("7", "r.xlsx")).isNotNull();
        assertThat(storage.resolve("7", "../7/r.xlsx")).isNull();
        assertThat(storage.resolve("..", "x")).isNull();
    }

    @Test
    void registryKeepsLastMessage() {
        BatchRunRegistry.record(BatchProgressMessage.builder().jobExecutionId(990001L)
                .type(BatchProgressMessage.Type.COMPLETED).build());
        assertThat(BatchRunRegistry.last(990001L).getType()).isEqualTo(BatchProgressMessage.Type.COMPLETED);
        assertThat(BatchRunRegistry.stop(990001L)).isFalse();
        List<BatchProgressMessage> recent = BatchRunRegistry.recent(5);
        assertThat(recent).isNotEmpty();
    }
}
