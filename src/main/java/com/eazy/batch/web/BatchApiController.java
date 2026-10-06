package com.eazy.batch.web;

import com.eazy.batch.autoconfigure.BatchProcessorProperties;
import com.eazy.batch.dto.BatchProgressMessage;
import com.eazy.batch.report.ReportSpec;
import com.eazy.batch.report.ReportSpecRegistry;
import com.eazy.batch.service.BatchContext;
import com.eazy.batch.service.BatchRunRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Built-in endpoints, enabled with {@code eazy.batch.api.enabled=true}:
 * <pre>
 * POST {base}/{jobName}/upload     multipart "file" (+ any extra params, which become job parameters)
 * GET  {base}/{jobName}/template   the upload template for that job
 * GET  {base}/{jobExecutionId}/status
 * POST {base}/{jobExecutionId}/stop
 * </pre>
 * The user is taken from the request's {@link Principal}; secure these paths like the rest of your app.
 */
@Slf4j
@RestController
@RequestMapping("${eazy.batch.api.base-path:/batch}")
public class BatchApiController {

    private static final Set<String> RESERVED = Set.of(
            BatchContext.P_FILE_PATH, BatchContext.P_ORIGINAL_FILE_NAME, BatchContext.P_TIMESTAMP,
            BatchContext.P_USERNAME, BatchContext.P_DRY_RUN, BatchContext.P_DELETE_FILE, "file");

    private final ApplicationContext context;
    private final JobLauncher jobLauncher;
    private final BatchProcessorProperties properties;
    private final UploadGuard guard;

    public BatchApiController(ApplicationContext context, JobLauncher jobLauncher, BatchProcessorProperties properties) {
        this.context = context;
        this.jobLauncher = jobLauncher;
        this.properties = properties;
        this.guard = new UploadGuard(properties.getUpload().getMaxFileSizeMb(), properties.getUpload().getMaxRows());
    }

    @PostMapping("/{jobName}/upload")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String jobName,
                                                     @RequestParam("file") MultipartFile file,
                                                     @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
                                                     @RequestParam Map<String, String> params,
                                                     Principal principal) {
        ReportSpec spec = ReportSpecRegistry.find(jobName);
        if (spec == null || !context.containsBean(jobName)) {
            return error(404, "Unknown batch job '" + jobName + "'.");
        }

        Path stored = null;
        try {
            guard.checkName(file.getOriginalFilename(), spec.fileType());
            guard.checkSize(file.getSize());

            stored = store(file);
            guard.checkRows(stored, spec.fileType(), spec.sheetIndex(), spec.sheetName());

            JobParametersBuilder builder = new JobParametersBuilder()
                    .addString(BatchContext.P_FILE_PATH, stored.toString())
                    .addString(BatchContext.P_ORIGINAL_FILE_NAME, file.getOriginalFilename())
                    .addLong(BatchContext.P_TIMESTAMP, System.currentTimeMillis())
                    .addString(BatchContext.P_DRY_RUN, String.valueOf(dryRun))
                    .addString(BatchContext.P_DELETE_FILE, String.valueOf(properties.getUpload().isDeleteAfterJob()));
            if (principal != null) builder.addString(BatchContext.P_USERNAME, principal.getName());
            params.forEach((k, v) -> {
                if (!RESERVED.contains(k) && v != null) builder.addString(k, v);
            });
            JobParameters jobParameters = builder.toJobParameters();

            JobExecution execution = jobLauncher.run(context.getBean(jobName, Job.class), jobParameters);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jobExecutionId", execution.getId());
            body.put("jobName", jobName);
            body.put("dryRun", dryRun);
            body.put("statusUrl", properties.getApi().getBasePath() + "/" + execution.getId() + "/status");
            return ResponseEntity.accepted().body(body);
        } catch (UploadGuard.UploadRejectedException e) {
            deleteQuietly(stored);
            return error(400, e.getMessage());
        } catch (Exception e) {
            deleteQuietly(stored);
            log.error("Could not start job '{}'", jobName, e);
            return error(500, "Could not start the job: " + e.getMessage());
        }
    }

    @GetMapping("/{jobName}/template")
    public ResponseEntity<byte[]> template(@PathVariable String jobName) {
        ReportSpec spec = ReportSpecRegistry.find(jobName);
        if (spec == null) return ResponseEntity.notFound().build();
        byte[] bytes = TemplateGenerator.generate(spec.dtoClass(), spec.fileType(), spec.sheetName());
        String name = jobName + "_template." + TemplateGenerator.extension(spec.fileType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType(TemplateGenerator.contentType(spec.fileType())))
                .body(bytes);
    }

    @GetMapping("/{jobExecutionId:\\d+}/status")
    public ResponseEntity<?> status(@PathVariable Long jobExecutionId) {
        BatchProgressMessage last = BatchRunRegistry.last(jobExecutionId);
        if (last == null) return error(404, "No job execution " + jobExecutionId + " is known to this server.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", BatchRunRegistry.isRunning(jobExecutionId));
        body.put("latest", last);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{jobExecutionId:\\d+}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable Long jobExecutionId) {
        if (!BatchRunRegistry.stop(jobExecutionId)) {
            return error(404, "Job execution " + jobExecutionId + " is not running on this server.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobExecutionId", jobExecutionId);
        body.put("message", "Stop requested - the job ends after the current chunk.");
        return ResponseEntity.accepted().body(body);
    }

    private Path store(MultipartFile file) throws IOException {
        String dir = properties.getUpload().getDirectory();
        Path root = dir == null || dir.isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"), "eazy-batch-uploads") : Paths.get(dir);
        Files.createDirectories(root);
        String name = file.getOriginalFilename() == null ? "upload" : Paths.get(file.getOriginalFilename()).getFileName().toString();
        Path target = root.resolve(UUID.randomUUID() + "_" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
        file.transferTo(target);
        return target;
    }

    private static void deleteQuietly(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static <T> ResponseEntity<T> error(int status, String message) {
        @SuppressWarnings("unchecked")
        T body = (T) Map.of("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
