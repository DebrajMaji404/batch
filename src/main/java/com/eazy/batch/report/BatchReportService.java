package com.eazy.batch.report;

import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.enums.ReportType;
import com.eazy.batch.utility.ErrorReportExcelGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the end-of-job report and hands it to the user's
 * {@link BatchReportStorage} (or embeds it as base64 when there is none).
 *
 * <p>For jobs generated from {@code @BatchJob} the report is built by walking
 * the uploaded file again and deciding every row's {@link RowStatus} from what
 * {@link BatchRowTracker} and the skip tracking recorded - so rows that were
 * lost to a rolled-back chunk, or never reached because the job died, show up
 * as {@code NOT_IMPORTED} instead of silently missing.</p>
 */
@Slf4j
public class BatchReportService {

    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * A finished, delivered report. Exactly one of {@code url} / {@code base64} is set.
     *
     * @param reportType       {@link ReportType#ERRORS} (only rows not imported) or
     *                         {@link ReportType#ALL} (every row, with a Status) - tells the
     *                         client how to present the file
     * @param successRows      rows imported (only counted for {@code ALL}; 0 for {@code ERRORS})
     * @param failedRows       rows rejected
     * @param notImportedRows  rows lost to a rolled-back chunk or an early stop
     */
    public record PublishedReport(String fileName, int sizeBytes, String url, String base64,
                                  ReportType reportType, int successRows, int failedRows, int notImportedRows) {
    }

    /** The finished workbook plus how many rows of each status it holds. */
    record RowReport(byte[] bytes, int successRows, int failedRows, int notImportedRows) {
    }

    private final BatchReportStorage storage;

    /** @param storage where to upload reports; {@code null} embeds them as base64 instead */
    public BatchReportService(BatchReportStorage storage) {
        this.storage = storage;
    }

    /**
     * Builds and delivers the report for a finished job.
     *
     * @param failureCause why the job stopped (root-cause message); used in the
     *                     Reason of rows that were lost with it. Ignored when COMPLETED.
     * @return the delivered report, or {@code null} if there is nothing to report
     */
    public PublishedReport publish(JobExecution jobExecution, List<BatchSkippedItem<?>> skipped, String failureCause) {
        try {
            String jobName = jobExecution.getJobInstance().getJobName();
            BatchStatus status = jobExecution.getStatus();
            ReportSpec spec = ReportSpecRegistry.find(jobName);
            String filePath = jobExecution.getJobParameters().getString("filePath");

            RowReport report = null;
            ReportType type = spec != null ? spec.reportType() : ReportType.ERRORS;

            if (spec != null && filePath != null) {
                try {
                    report = buildRowReport(spec, filePath, status, failureCause, skipped,
                            BatchRowTracker.snapshot(jobExecution.getId()));
                } catch (Exception e) {
                    // e.g. header mismatch (the file can't be opened) or the
                    // file is already gone - fall back to what we recorded.
                    log.warn("Could not build the row-level report for job {} ({}); falling back to recorded skips only",
                            jobName, e.getMessage());
                }
            }
            if (report == null && spec != null && filePath != null && type == ReportType.ERRORS
                    && status == BatchStatus.COMPLETED && skipped.isEmpty()) {
                return null; // row-level report found nothing wrong
            }
            if (report == null) {
                // The fallback only ever lists failures, so it is an ERRORS-style file
                // whatever the job asked for.
                type = ReportType.ERRORS;
                report = buildFallbackReport(skipped, status, failureCause);
            }
            if (report == null) {
                return null;
            }

            String suffix = type == ReportType.ALL ? "_report.xlsx" : "_errors.xlsx";
            return deliver(new BatchReportFile(report.bytes(), jobName + suffix, XLSX_CONTENT_TYPE,
                    jobName, jobExecution.getId(), type), report);
        } catch (Exception e) {
            // Report problems must never take down the final status message.
            log.error("Could not build/deliver the report for job execution {}: {}",
                    jobExecution.getId(), e.getMessage(), e);
            return null;
        }
    }

    private PublishedReport deliver(BatchReportFile file, RowReport counts) {
        if (storage != null) {
            try {
                String url = storage.store(file);
                if (url != null && !url.isBlank()) {
                    return published(file, counts, url, null);
                }
                log.warn("BatchReportStorage returned no URL for {}; embedding the report as base64", file.fileName());
            } catch (Exception e) {
                log.error("BatchReportStorage failed for {} ({}); embedding the report as base64",
                        file.fileName(), e.getMessage(), e);
            }
        }
        return published(file, counts, null, Base64.getEncoder().encodeToString(file.content()));
    }

    private static PublishedReport published(BatchReportFile file, RowReport counts, String url, String base64) {
        return new PublishedReport(file.fileName(), file.content().length, url, base64, file.reportType(),
                counts.successRows(), counts.failedRows(), counts.notImportedRows());
    }

    /** Only what was recorded as skipped; or, for a failed job with none, one row saying why. */
    private RowReport buildFallbackReport(List<BatchSkippedItem<?>> skipped, BatchStatus status, String failureCause) {
        List<BatchSkippedItem<?>> items = skipped;
        if (items.isEmpty() && status != BatchStatus.COMPLETED) {
            items = List.of(new BatchSkippedItem<>(null, "JOB", failureCause));
        }
        byte[] bytes = ErrorReportExcelGenerator.generate(items);
        return bytes == null ? null : new RowReport(bytes, 0, items.size(), 0);
    }

    /**
     * Decides every row's status and writes the report.
     *
     * <p>Package-visible so it can be tested without a {@code JobExecution}.</p>
     *
     * @return the report, or {@code null} when an ERRORS report has nothing to list
     */
    RowReport buildRowReport(ReportSpec spec, String filePath, BatchStatus status, String failureCause,
                          List<BatchSkippedItem<?>> skipped, BatchRowTracker.Snapshot snapshot) throws IOException {

        // Failures by row (a row can fail more than once, e.g. two entities in the same row).
        Map<Integer, Set<String>> failuresByRow = new LinkedHashMap<>();
        List<BatchSkippedItem<?>> rowless = new ArrayList<>();
        for (BatchSkippedItem<?> item : skipped) {
            if (item.getRowNumber() == null) {
                rowless.add(item);
            } else {
                failuresByRow.computeIfAbsent(item.getRowNumber(), k -> new LinkedHashSet<>())
                        .add(failureText(item));
            }
        }

        boolean jobCompleted = status == BatchStatus.COMPLETED;
        if (spec.reportType() == ReportType.ERRORS && jobCompleted && failuresByRow.isEmpty() && rowless.isEmpty()) {
            return null;
        }

        String sheet = spec.reportType() == ReportType.ALL
                ? ErrorReportExcelGenerator.ALL_ROWS_SHEET : ErrorReportExcelGenerator.ERRORS_SHEET;
        String cause = failureCause != null && !failureCause.isBlank() ? failureCause : "job status " + status;

        try (ErrorReportExcelGenerator.Builder builder = new ErrorReportExcelGenerator.Builder(spec.dtoClass(), sheet);
             SourceRows rows = SourceRows.open(spec, filePath)) {

            int successRows = 0;
            int failedRows = 0;
            int notImportedRows = 0;

            for (SourceRows.SourceRow row = rows.next(); row != null; row = rows.next()) {
                Set<String> failures = failuresByRow.get(row.number());
                RowStatus rowStatus;
                String reason;

                if (failures != null) {
                    rowStatus = RowStatus.FAILED;
                    reason = String.join("; ", failures);
                } else if (jobCompleted) {
                    rowStatus = RowStatus.SUCCESS;
                    reason = "";
                } else if (snapshot != null && snapshot.isCommitted(row.number())) {
                    rowStatus = RowStatus.SUCCESS;
                    reason = "";
                } else {
                    rowStatus = RowStatus.NOT_IMPORTED;
                    reason = notImportedReason(row.number(), snapshot, cause);
                    if (row.parseError() != null) {
                        reason += " This row also cannot be parsed: " + row.parseError();
                    }
                }

                if (spec.reportType() == ReportType.ALL || rowStatus != RowStatus.SUCCESS) {
                    builder.addRow(row.dto(), rowStatus, reason);
                    switch (rowStatus) {
                        case SUCCESS -> successRows++;
                        case FAILED -> failedRows++;
                        case NOT_IMPORTED -> notImportedRows++;
                    }
                }
            }

            // Failures we could not tie to a row (custom reader, etc.).
            for (BatchSkippedItem<?> item : rowless) {
                builder.addRow(item.getItem(), RowStatus.FAILED, failureText(item));
                failedRows++;
            }

            if (builder.rowCount() == 0) {
                if (jobCompleted) {
                    return null;
                }
                // The job died but no row can be blamed (e.g. it stopped after the last chunk).
                builder.addRow(null, RowStatus.NOT_IMPORTED, "Job did not finish: " + cause);
                notImportedRows++;
            }
            return new RowReport(builder.build(), successRows, failedRows, notImportedRows);
        }
    }

    private static String failureText(BatchSkippedItem<?> item) {
        String phase = item.getPhase() != null ? item.getPhase() : "";
        String reason = item.getReason() != null ? item.getReason() : "";
        return phase.isEmpty() ? reason : "[" + phase + "] " + reason;
    }

    private static String notImportedReason(int row, BatchRowTracker.Snapshot snapshot, String cause) {
        boolean wasRead = snapshot != null && row <= snapshot.maxRowRead();
        return wasRead
                ? "Not imported: the chunk containing this row was rolled back (" + cause
                        + "). The row itself may be fine - upload it again."
                : "Not imported: the job stopped before reaching this row (" + cause + ").";
    }
}
