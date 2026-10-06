package com.eazy.batch.report;

import com.eazy.batch.enums.FileType;
import com.eazy.batch.enums.ReportType;

/**
 * Everything the report builder needs to know about a job in order to re-read
 * its input file after the run: registered by the generated configuration of
 * every {@code @BatchJob}.
 */
public record ReportSpec(
        String jobName,
        Class<?> dtoClass,
        FileType fileType,
        int sheetIndex,
        String sheetName,
        ReportType reportType) {
}
