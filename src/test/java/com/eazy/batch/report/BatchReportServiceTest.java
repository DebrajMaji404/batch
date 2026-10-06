package com.eazy.batch.report;

import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.enums.FileType;
import com.eazy.batch.enums.ReportType;
import com.poiji.annotation.ExcelCellName;
import lombok.Data;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.MetaDataInstanceFactory;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchReportServiceTest {

    /** Public with a public no-arg constructor: the reader instantiates it reflectively. */
    @Data
    public static class UploadRow {
        @ExcelCellName("name")
        private String name;

        @ExcelCellName("city")
        private String city;
    }

    private final BatchReportService service = new BatchReportService(null);

    /** Header + 5 data rows, which are rows 2..6 as the user sees them in Excel. */
    private String writeUpload(Path dir) throws Exception {
        Path file = dir.resolve("upload.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("city");
            String[][] data = {{"r2", "c2"}, {"r3", "c3"}, {"r4", "c4"}, {"r5", "c5"}, {"r6", "c6"}};
            for (int i = 0; i < data.length; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(data[i][0]);
                row.createCell(1).setCellValue(data[i][1]);
            }
            try (FileOutputStream out = new FileOutputStream(file.toFile())) {
                workbook.write(out);
            }
        }
        return file.toString();
    }

    private ReportSpec spec(ReportType type) {
        return new ReportSpec("reportJob", UploadRow.class, FileType.EXCEL, 0, null, type);
    }

    private BatchRowTracker.Snapshot committed(int maxRowRead, int... rows) {
        BitSet bits = new BitSet();
        for (int row : rows) {
            bits.set(row);
        }
        return new BatchRowTracker.Snapshot(bits, maxRowRead);
    }

    private List<String[]> read(byte[] bytes, String sheetName) throws Exception {
        List<String[]> rows = new java.util.ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet(sheetName);
            assertThat(sheet).isNotNull();
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                String[] cells = new String[row.getLastCellNum()];
                for (int c = 0; c < cells.length; c++) {
                    cells[c] = row.getCell(c).getStringCellValue();
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    /** Row 4 failed; rows 2-3 committed; 5 was read but lost; 6 never reached. */
    private List<BatchSkippedItem<?>> failureAtRow4() {
        return List.of(new BatchSkippedItem<>(null, "PROCESS", "Paper Allocation not found", 4));
    }

    @Test
    void errorsReport_listsEveryRowThatWasNotImported_afterAnAbortedJob(@TempDir Path dir) throws Exception {
        BatchReportService.RowReport report = service.buildRowReport(spec(ReportType.ERRORS), writeUpload(dir), BatchStatus.FAILED,
                "Skip limit of '5' exceeded", failureAtRow4(), committed(5, 2, 3));

        assertThat(report.successRows()).isZero();
        assertThat(report.failedRows()).isEqualTo(1);
        assertThat(report.notImportedRows()).isEqualTo(2);

        List<String[]> rows = read(report.bytes(), "Errors");
        assertThat(rows).hasSize(4); // header + rows 4, 5, 6
        assertThat(rows.get(0)).containsExactly("name", "city", "Status", "Reason");

        // Row 4: the failure itself, with the original values and its reason.
        assertThat(rows.get(1)).containsExactly("r4", "c4", "FAILED", "[PROCESS] Paper Allocation not found");
        // Row 5: read but its chunk was rolled back.
        assertThat(rows.get(2)[0]).isEqualTo("r5");
        assertThat(rows.get(2)[2]).isEqualTo("NOT_IMPORTED");
        assertThat(rows.get(2)[3]).contains("rolled back").contains("Skip limit of '5' exceeded");
        // Row 6: the job died before getting here.
        assertThat(rows.get(3)[0]).isEqualTo("r6");
        assertThat(rows.get(3)[2]).isEqualTo("NOT_IMPORTED");
        assertThat(rows.get(3)[3]).contains("stopped before reaching this row");
    }

    @Test
    void allReport_marksEveryRowSuccessFailedOrNotImported(@TempDir Path dir) throws Exception {
        BatchReportService.RowReport report = service.buildRowReport(spec(ReportType.ALL), writeUpload(dir), BatchStatus.FAILED,
                "boom", failureAtRow4(), committed(5, 2, 3));

        assertThat(report.successRows()).isEqualTo(2);
        assertThat(report.failedRows()).isEqualTo(1);
        assertThat(report.notImportedRows()).isEqualTo(2);

        List<String[]> rows = read(report.bytes(), "All rows");
        assertThat(rows).hasSize(6); // header + 5 rows
        assertThat(rows.get(1)[2]).isEqualTo("SUCCESS");
        assertThat(rows.get(2)[2]).isEqualTo("SUCCESS");
        assertThat(rows.get(3)[2]).isEqualTo("FAILED");
        assertThat(rows.get(4)[2]).isEqualTo("NOT_IMPORTED");
        assertThat(rows.get(5)[2]).isEqualTo("NOT_IMPORTED");
        assertThat(rows.get(1)[3]).isEmpty(); // no reason on a success
    }

    @Test
    void errorsReport_isNullWhenTheJobCompletedWithoutFailures(@TempDir Path dir) throws Exception {
        BatchReportService.RowReport report = service.buildRowReport(spec(ReportType.ERRORS), writeUpload(dir), BatchStatus.COMPLETED,
                null, List.of(), committed(6, 2, 3, 4, 5, 6));

        assertThat(report).isNull();
    }

    @Test
    void errorsReport_ofACompletedJob_listsOnlyTheFailedRows(@TempDir Path dir) throws Exception {
        List<BatchSkippedItem<?>> skipped = List.of(new BatchSkippedItem<>(null, "PROCESS", "duplicate", 3));

        BatchReportService.RowReport report = service.buildRowReport(spec(ReportType.ERRORS), writeUpload(dir), BatchStatus.COMPLETED,
                null, skipped, committed(6));

        List<String[]> rows = read(report.bytes(), "Errors");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("r3", "c3", "FAILED", "[PROCESS] duplicate");
    }

    @Test
    void allReport_ofACompletedJob_marksEverythingElseSuccess(@TempDir Path dir) throws Exception {
        List<BatchSkippedItem<?>> skipped = List.of(new BatchSkippedItem<>(null, "PROCESS", "duplicate", 3));

        BatchReportService.RowReport report = service.buildRowReport(spec(ReportType.ALL), writeUpload(dir), BatchStatus.COMPLETED,
                null, skipped, null);

        List<String[]> rows = read(report.bytes(), "All rows");
        assertThat(rows).hasSize(6);
        assertThat(rows.get(1)[2]).isEqualTo("SUCCESS");
        assertThat(rows.get(2)[2]).isEqualTo("FAILED");
        assertThat(rows.get(3)[2]).isEqualTo("SUCCESS");
    }

    @Test
    void severalFailuresOnOneRowAreJoinedIntoOneReason(@TempDir Path dir) throws Exception {
        List<BatchSkippedItem<?>> skipped = List.of(
                new BatchSkippedItem<>(null, "WRITE", "first problem", 2),
                new BatchSkippedItem<>(null, "WRITE", "second problem", 2));

        BatchReportService.RowReport report = service.buildRowReport(spec(ReportType.ERRORS), writeUpload(dir), BatchStatus.COMPLETED,
                null, skipped, null);

        List<String[]> rows = read(report.bytes(), "Errors");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)[3]).isEqualTo("[WRITE] first problem; [WRITE] second problem");
    }

    // ─────────────────────────────────────────────────────────────────
    // publish(): delivery through the user's BatchReportStorage bean
    // ─────────────────────────────────────────────────────────────────

    private JobExecution failedJob(String filePath) {
        JobParameters params = new JobParametersBuilder().addString("filePath", filePath).toJobParameters();
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution("reportJob", 1L, 9001L, params);
        jobExecution.setStatus(BatchStatus.FAILED);
        return jobExecution;
    }

    @Test
    void publish_uploadsThroughTheStorageBeanAndReturnsItsUrl(@TempDir Path dir) throws Exception {
        ReportSpecRegistry.register(spec(ReportType.ERRORS));
        List<BatchReportFile> uploaded = new java.util.ArrayList<>();
        BatchReportService withStorage = new BatchReportService(file -> {
            uploaded.add(file);
            return "https://files.example.com/reports/" + file.jobExecutionId() + "/" + file.fileName();
        });

        BatchReportService.PublishedReport report =
                withStorage.publish(failedJob(writeUpload(dir)), failureAtRow4(), "boom");

        assertThat(report).isNotNull();
        assertThat(report.url()).isEqualTo("https://files.example.com/reports/9001/reportJob_errors.xlsx");
        assertThat(report.base64()).isNull(); // the bytes are NOT embedded when stored
        assertThat(uploaded).hasSize(1);
        assertThat(uploaded.get(0).content()).isNotEmpty();
        assertThat(uploaded.get(0).contentType()).isEqualTo(BatchReportService.XLSX_CONTENT_TYPE);
        assertThat(uploaded.get(0).reportType()).isEqualTo(ReportType.ERRORS);
        assertThat(report.sizeBytes()).isEqualTo(uploaded.get(0).content().length);
        // The client is told what kind of file this is, and what is in it.
        assertThat(report.reportType()).isEqualTo(ReportType.ERRORS);
        assertThat(report.failedRows()).isEqualTo(1);
        assertThat(report.notImportedRows()).isEqualTo(4); // rows 2,3,5,6: no commits recorded in this test
    }

    @Test
    void publish_embedsBase64WhenThereIsNoStorageBean(@TempDir Path dir) throws Exception {
        ReportSpecRegistry.register(spec(ReportType.ALL));

        BatchReportService.PublishedReport report =
                new BatchReportService(null).publish(failedJob(writeUpload(dir)), failureAtRow4(), "boom");

        assertThat(report).isNotNull();
        assertThat(report.url()).isNull();
        assertThat(report.base64()).isNotBlank();
        assertThat(report.fileName()).isEqualTo("reportJob_report.xlsx"); // ALL -> _report
        assertThat(report.reportType()).isEqualTo(ReportType.ALL);
    }

    @Test
    void publish_fallsBackToBase64WhenTheStorageUploadFails(@TempDir Path dir) throws Exception {
        ReportSpecRegistry.register(spec(ReportType.ERRORS));
        BatchReportService failingStorage = new BatchReportService(file -> {
            throw new IllegalStateException("bucket unreachable");
        });

        BatchReportService.PublishedReport report =
                failingStorage.publish(failedJob(writeUpload(dir)), failureAtRow4(), "boom");

        assertThat(report).isNotNull();
        assertThat(report.url()).isNull();
        assertThat(report.base64()).isNotBlank(); // the user still gets the file
    }

    @Test
    void publish_returnsNullWhenThereIsNothingToReport(@TempDir Path dir) throws Exception {
        ReportSpecRegistry.register(spec(ReportType.ERRORS));
        JobParameters params = new JobParametersBuilder().addString("filePath", writeUpload(dir)).toJobParameters();
        JobExecution completed = MetaDataInstanceFactory.createJobExecution("reportJob", 1L, 9002L, params);
        completed.setStatus(BatchStatus.COMPLETED);

        assertThat(new BatchReportService(null).publish(completed, List.of(), null)).isNull();
    }
}
