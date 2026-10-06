package com.eazy.batch.web;

import com.eazy.batch.report.LocalReportStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/** Serves reports kept by {@link LocalReportStorage}: {@code GET {base}/reports/{jobExecutionId}/{fileName}}. */
@RestController
@RequestMapping("${eazy.batch.api.base-path:/batch}/reports")
public class ReportDownloadController {

    private final LocalReportStorage storage;

    public ReportDownloadController(LocalReportStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/{jobExecutionId:\\d+}/{fileName:.+}")
    public ResponseEntity<Resource> download(@PathVariable String jobExecutionId, @PathVariable String fileName) {
        Path file = storage.resolve(jobExecutionId, fileName);
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getFileName().toString()).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new FileSystemResource(file));
    }
}
