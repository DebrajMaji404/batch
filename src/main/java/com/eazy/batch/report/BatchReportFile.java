package com.eazy.batch.report;

import com.eazy.batch.enums.ReportType;

/**
 * A finished report handed to {@link BatchReportStorage}.
 *
 * @param content        the .xlsx bytes
 * @param fileName       suggested file name, e.g. {@code studentCourseBatchJob_errors.xlsx}
 * @param contentType    MIME type of {@code content}
 * @param jobName        name of the job that produced it
 * @param jobExecutionId id of the job execution that produced it (unique per run - handy for object keys)
 * @param reportType     {@link ReportType#ERRORS} or {@link ReportType#ALL}
 */
public record BatchReportFile(
        byte[] content,
        String fileName,
        String contentType,
        String jobName,
        Long jobExecutionId,
        ReportType reportType) {
}
