package com.eazy.batch.test;

import com.eazy.batch.dto.BatchProgressMessage;
import com.eazy.batch.service.BatchContext;
import com.eazy.batch.service.BatchRunRegistry;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test helper: runs a {@code @BatchJob} against rows you write in the test, waits for it to
 * finish and hands back the final message plus the parsed report.
 *
 * <pre>
 * var result = new BatchJobTester(jobLauncher, studentJob)
 *         .param("tenantId", "t1")
 *         .runExcel(List.of("name", "age"), List.of(List.of("Asha", 21), List.of("", 5)));
 *
 * assertThat(result.message().getType()).isEqualTo(BatchProgressMessage.Type.COMPLETED);
 * assertThat(result.failedRows()).hasSize(1);
 * </pre>
 *
 * The report is read from the final message's {@code errorFileBase64}, so use it without a
 * {@code BatchReportStorage} bean (the default in tests).
 */
public final class BatchJobTester {

    public record Result(BatchProgressMessage message, List<Map<String, String>> reportRows) {
        /** Report rows whose Status column equals {@code status}. */
        public List<Map<String, String>> rowsWithStatus(String status) {
            return reportRows.stream().filter(r -> status.equalsIgnoreCase(r.get("Status"))).toList();
        }

        public List<Map<String, String>> failedRows() {
            return rowsWithStatus("FAILED");
        }

        public List<Map<String, String>> successRows() {
            return rowsWithStatus("SUCCESS");
        }

        public List<Map<String, String>> notImportedRows() {
            return rowsWithStatus("NOT_IMPORTED");
        }
    }

    private final JobLauncher launcher;
    private final Job job;
    private final Map<String, String> params = new LinkedHashMap<>();
    private Duration timeout = Duration.ofSeconds(60);

    public BatchJobTester(JobLauncher launcher, Job job) {
        this.launcher = launcher;
        this.job = job;
    }

    /** Extra job parameter, as an upload request parameter would be. */
    public BatchJobTester param(String key, String value) {
        params.put(key, value);
        return this;
    }

    public BatchJobTester username(String username) {
        return param(BatchContext.P_USERNAME, username);
    }

    public BatchJobTester dryRun() {
        return param(BatchContext.P_DRY_RUN, "true");
    }

    public BatchJobTester timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public Result runExcel(List<String> headers, List<? extends List<?>> rows) {
        return run(excel(headers, rows), "test.xlsx");
    }

    public Result runCsv(List<String> headers, List<? extends List<?>> rows) {
        return run(csv(headers, rows), "test.csv");
    }

    public Result run(byte[] file, String fileName) {
        Path path = null;
        try {
            path = Files.createTempFile("eazy-batch-test-", "-" + fileName);
            Files.write(path, file);

            JobParametersBuilder builder = new JobParametersBuilder()
                    .addString(BatchContext.P_FILE_PATH, path.toString())
                    .addString(BatchContext.P_ORIGINAL_FILE_NAME, fileName)
                    .addLong(BatchContext.P_TIMESTAMP, System.nanoTime());
            params.forEach(builder::addString);

            JobExecution execution = launcher.run(job, builder.toJobParameters());
            BatchProgressMessage message = await(execution.getId());
            return new Result(message, parseReport(message));
        } catch (Exception e) {
            throw new IllegalStateException("Batch test run failed: " + e.getMessage(), e);
        } finally {
            try {
                if (path != null) Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // temp file
            }
        }
    }

    private BatchProgressMessage await(Long id) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            BatchProgressMessage last = BatchRunRegistry.last(id);
            if (last != null && last.getType() != BatchProgressMessage.Type.PROGRESS) return last;
            Thread.sleep(50);
        }
        throw new IllegalStateException("Job execution " + id + " did not finish within " + timeout);
    }

    private static List<Map<String, String>> parseReport(BatchProgressMessage message) throws IOException {
        List<Map<String, String>> out = new ArrayList<>();
        if (message.getErrorFileBase64() == null) return out;
        byte[] bytes = Base64.getDecoder().decode(message.getErrorFileBase64());
        DataFormatter fmt = new DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) return out;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                for (Cell h : header) {
                    values.put(fmt.formatCellValue(h), fmt.formatCellValue(row.getCell(h.getColumnIndex())));
                }
                out.add(values);
            }
        }
        return out;
    }

    public static byte[] excel(List<String> headers, List<? extends List<?>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Data");
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) head.createCell(i).setCellValue(headers.get(i));
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<?> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    Object v = values.get(c);
                    if (v == null) continue;
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else row.createCell(c).setCellValue(v.toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] csv(List<String> headers, List<? extends List<?>> rows) {
        StringBuilder sb = new StringBuilder(String.join(",", headers)).append("\n");
        for (List<?> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(row.get(i) == null ? "" : "\"" + row.get(i).toString().replace("\"", "\"\"") + "\"");
            }
            sb.append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
