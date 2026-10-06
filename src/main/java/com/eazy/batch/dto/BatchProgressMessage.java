package com.eazy.batch.dto;

import com.eazy.batch.enums.ReportType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message broadcast over WebSocket (STOMP) to
 * {@code {websocketTopicPrefix}/{jobExecutionId}}, e.g. {@code /topic/batch-progress/42}.
 *
 * <p>{@code type = PROGRESS} messages are sent after every chunk while the job runs.
 * Exactly one {@code type = COMPLETED} or {@code type = FAILED} message is sent at the
 * end carries the job's Excel report, if there is one: as a URL in {@code errorFileUrl}
 * when a {@code BatchReportStorage} bean is registered, otherwise embedded as base64 in
 * {@code errorFileBase64}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchProgressMessage {

    public enum Type { PROGRESS, COMPLETED, FAILED }

    private Type type;
    private Long jobExecutionId;
    private String jobName;

    /** Rows read so far (this step). */
    private long readCount;
    /** Rows written so far (this step). */
    private long writeCount;
    /** Rows skipped so far (this step). */
    private long skipCount;

    /** Only set on COMPLETED/FAILED. */
    private Long durationMs;

    /**
     * Only set on FAILED: the root-cause message of whatever killed the job.
     *
     * <p>This exists because not every failure produces skipped rows. A
     * job-level failure - a template/header mismatch, a missing file, a bad
     * JPQL query - happens before or outside item processing, so skipCount
     * stays 0 and no error report is generated. Without this field those
     * failures reached the client as a bare {@code type = FAILED} with every
     * other field zero/null, giving the user nothing to act on.</p>
     */
    private String failureMessage;

    /**
     * Only set on COMPLETED/FAILED, and only when there is a report: the file
     * name of the Excel report ({@code ..._errors.xlsx} for
     * {@code reportType = ERRORS}, {@code ..._report.xlsx} for {@code ALL}).
     */
    private String errorFileName;
    /**
     * Full URL of the report, as returned by your {@code BatchReportStorage}
     * bean. Set instead of {@code errorFileBase64} whenever such a bean exists.
     */
    private String errorFileUrl;
    /** Base64-encoded .xlsx bytes. Only set when no {@code BatchReportStorage} bean (or it failed). */
    private String errorFileBase64;
    private Integer errorFileSizeBytes;

    /**
     * What kind of file the report is, so the client can present it correctly:
     * <ul>
     *   <li>{@code ERRORS} - only the rows that were NOT imported (Status is FAILED or NOT_IMPORTED);</li>
     *   <li>{@code ALL} - every row of the upload (Status is SUCCESS, FAILED or NOT_IMPORTED).</li>
     * </ul>
     * Set whenever {@code errorFileName} is. A job that died before any row could be
     * reported on always yields an {@code ERRORS} file, even for {@code @BatchJob(reportType = ALL)}.
     */
    private ReportType reportType;
    /** Rows in the report with Status SUCCESS (only ever &gt; 0 for {@code ALL}). */
    private Integer reportSuccessRows;
    /** Rows in the report with Status FAILED - rejected themselves; see their Reason. */
    private Integer reportFailedRows;
    /** Rows in the report with Status NOT_IMPORTED - lost to a rolled-back chunk or an early stop. */
    private Integer reportNotImportedRows;
}
