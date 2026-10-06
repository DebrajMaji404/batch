package com.eazy.batch.web;

import com.eazy.batch.autoconfigure.BatchProcessorProperties;
import com.eazy.batch.report.ReportSpec;
import com.eazy.batch.report.ReportSpecRegistry;
import com.eazy.batch.service.BatchRunRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /actuator/eazybatch} - the registered batch jobs, the effective configuration and the
 * most recent runs of this server. Expose it with
 * {@code management.endpoints.web.exposure.include=eazybatch}.
 */
@Endpoint(id = "eazybatch")
public class BatchActuatorEndpoint {

    private final BatchProcessorProperties properties;

    public BatchActuatorEndpoint(BatchProcessorProperties properties) {
        this.properties = properties;
    }

    @ReadOperation
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();

        List<Map<String, Object>> jobs = new ArrayList<>();
        for (ReportSpec spec : ReportSpecRegistry.all()) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("name", spec.jobName());
            job.put("fileType", spec.fileType());
            job.put("reportType", spec.reportType());
            job.put("dto", spec.dtoClass().getName());
            jobs.add(job);
        }
        out.put("jobs", jobs);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("persistJobMetadata", properties.isPersistJobMetadata());
        config.put("rowIsolation", properties.isRowIsolation());
        config.put("apiEnabled", properties.getApi().isEnabled());
        config.put("maxFileSizeMb", properties.getUpload().getMaxFileSizeMb());
        config.put("maxRows", properties.getUpload().getMaxRows());
        config.put("localReports", properties.getReport().isLocalEnabled());
        out.put("config", config);

        out.put("recentRuns", BatchRunRegistry.recent(20));
        return out;
    }
}
