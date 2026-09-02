# Eazy Batch Processor

Auto-generate Spring Batch (6.x) configuration using annotations. Point it at a DTO and a
save method for imports, or an entity and a JPQL query for exports — the annotation
processor generates the Job, Step, reader, writer, skip listener and (for exports) storage
wiring for you.

Built on **Spring Boot 4.0.2 / Spring Batch 6.x** and **Java 21**.

Two annotations, two workflows:

| Annotation | Direction | Use it for |
|---|---|---|
| `@BatchJob` | File → Database | Importing an Excel/CSV upload into your database |
| `@BatchExportJob` | Database → File | Exporting a JPA query's results to an Excel/CSV file |

---

## Installation

### Step 1: Build the library

```bash
mvn clean install
```

JAR is installed to: `~/.m2/repository/com/eazy/eazy-batch-processor/1.0.1/`

### Step 2: Add the dependency to your application POM

```xml
<dependency>
    <groupId>com.eazy</groupId>
    <artifactId>eazy-batch-processor</artifactId>
    <version>1.0.1</version>
</dependency>
```

### Step 3: Register the annotation processor

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <source>21</source>
                <target>21</target>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.eazy</groupId>
                        <artifactId>eazy-batch-processor</artifactId>
                        <version>1.0.1</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

> If you ever need to build **this library itself** without running its own annotation
> processor over its own (nonexistent) `@BatchJob` usages, pass
> `-Aprocessor.skip.batchjob=true` as a compiler arg — this is already set in this
> repo's own `pom.xml` for that reason.

### Step 4: Compile

```bash
mvn clean compile
```

Generated sources land in `target/generated-sources/annotations/`. In IntelliJ, right-click
that folder → **Mark Directory as → Generated Sources Root** (Eclipse: Project Properties →
Java Build Path → Source → Add Folder).

---

## Part 1 — Importing files with `@BatchJob`

### Step 1: Define your DTO (one row of input)

```java
import lombok.Data;

@Data
public class MyDTO {
    private String name;
    private Integer age;
}
```

Column headers in your Excel/CSV file must match the DTO's field names (case-insensitive).
The reader validates headers up front and fails fast with a clear error if they don't match.

### Step 2: Define your wrapper (what one row turns into)

```java
import lombok.Data;
import java.util.List;

@Data
public class MyWrapper {
    private List<MyEntity> entities;

    public MyWrapper(List<MyEntity> entities) {
        this.entities = entities;
    }
}
```

### Step 3: Write the job config

```java
import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.config.SimpleBatchProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
@BatchJob(
    jobName = "myJob",
    stepName = "myStep",
    dtoClass = MyDTO.class,
    wrapperClass = MyWrapper.class
)
public class MyJobConfig implements SimpleBatchProcessor<MyDTO, MyWrapper> {

    private final MyService service;
    private final MyRepository repository;

    @Override
    public MyWrapper process(MyDTO dto) throws Exception {
        List<MyEntity> entities = service.process(dto);
        return new MyWrapper(entities);
    }

    @Override
    public void save(List<MyWrapper> wrappers) {
        extractAndSaveFlat(wrappers, MyWrapper::getEntities, repository);
    }
}
```

### Step 4: Launch it from a controller

```java
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchController {

    private final TaskExecutorJobLauncher jobLauncher;
    private final FileService fileService;

    @Qualifier("myJob")
    private final Job myJob;

    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file)
            throws Exception {

        String filePath = fileService.saveFile(file, "/uploads/batch");

        JobParameters params = new JobParametersBuilder()
                .addString("filePath", filePath)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        jobLauncher.run(myJob, params);

        return ResponseEntity.ok("Batch processing started");
    }
}
```

### What gets generated for `@BatchJob`

| Generated class | What it is |
|---|---|
| `MyJobConfigConfiguration` | The `Job` and `Step` beans |
| `MyJobConfigReader` | Row-by-row Excel/CSV reader with header validation |
| `MyJobConfigProcessor` | Validates each DTO (Jakarta Bean Validation + your `customValidate()`), then calls your `process()` |
| `MyJobConfigWriter` | Calls your `save()` with the chunk |
| `MyJobConfigSkipListener` | Records skip details (phase, reason) via `BatchUtility`, and reports to Micrometer if metrics are enabled |
| `MyJobConfigNotificationListener` | *(only generated if `notifyOnCompletion`/`notifyOnFailure` is set)* — sends completion/failure emails |

All beans are registered automatically — nothing to wire up by hand.

### `@BatchJob` annotation reference

```java
@BatchJob(
    jobName = "myJob",                 // Required
    stepName = "myStep",               // Required
    batchName = "MyBatch",             // Optional: display name for logging
    dtoClass = MyDTO.class,            // Required
    wrapperClass = MyWrapper.class,    // Required

    chunkSize = -1,                    // -1 (default) = use eazy.batch.default-chunk-size.
                                        // Set a positive number to override per-job.
    skipLimit = -1,                    // -1 (default) = use eazy.batch.default-skip-limit.
                                        // Must be > 0 if set explicitly - Spring Batch 6
                                        // rejects skipLimit=0 at startup.

    fileType = FileType.EXCEL,         // EXCEL or CSV only - JSON/XML are declared on the
                                        // FileType enum but not implemented yet and will
                                        // fail compilation with a clear error if used.
    readerType = ReaderType.FILE,      // FILE only - DATABASE/API/KAFKA are declared on the
                                        // ReaderType enum but not implemented yet.
    sheetName = "",                    // Excel: sheet name (defaults to first sheet)
    sheetIndex = 0,                    // Excel: sheet index, used only if sheetName is empty

    cacheValidation = true,            // NEW: caches Jakarta validation results within the
                                        // job run, keyed by dto.toString(). Skips
                                        // re-validating rows with identical content (common
                                        // in messy real-world files). Disable if your DTO's
                                        // toString() doesn't reflect its full field content.

    requiredParameters = {"filePath"}, // Job launch fails (JobParametersInvalidException) if
                                        // any of these keys are missing, enforced via a
                                        // generated DefaultJobParametersValidator.
    optionalParameters = {},           // Combined with requiredParameters() to build the full
                                        // accepted set - unlisted parameter keys also fail launch.

    parallelProcessing = false,        // Multi-threaded step execution. The reader is
                                        // automatically wrapped in Spring Batch's
                                        // SynchronizedItemStreamReader, since the built-in
                                        // CSV/Excel readers aren't thread-safe on their own.
    threadPoolSize = 4,                // Documentation-only - concurrency is actually
                                        // controlled by the shared batchTaskExecutor bean's
                                        // pool size (eazy.batch.thread-pool-size).

    enableRetry = false,               // Retry failed items before they count as a skip
    retryLimit = 3,
    retryableExceptions = {},          // Fully-qualified exception class names

    notifyOnCompletion = false,        // Email on job completion (needs recipients + SMTP)
    notifyOnFailure = false,           // Email on job failure
    recipients = {},                   // Required if either notify* flag is true

    dryRun = false                     // Validate + run process() logic but skip persisting
)
```

Attributes declared but **not yet implemented** — using them currently has no effect for
`checkpointColumn`, or fails compilation immediately with a clear error for `partitioned`/
`incremental` (setting either to `true`): `partitioned`, `partitions`, `incremental`,
`checkpointColumn`. See [Known limitations](#known-limitations--roadmap) below.

### `SimpleBatchProcessor<DTO, WRAPPER>` reference

**Required:**
```java
MyWrapper process(MyDTO dto) throws Exception;   // your business logic
void save(List<MyWrapper> wrappers);              // persist the chunk
```

**Optional lifecycle hooks** (all have safe no-op defaults):
```java
DTO preProcess(DTO dto)                                    // normalize/clean before validation
boolean shouldProcess(DTO dto)                              // return false to silently filter
List<String> customValidate(DTO dto)                        // extra validation beyond Jakarta
WRAPPER postProcess(WRAPPER wrapper)                         // enrich after process()
void onJobStart() / onJobComplete(processed, skipped) / onJobFailure(error)
String getIdentifier(Object item)                            // used in skip/error log lines
```

**Save helpers** (call from `save()`):
```java
extractAndSave(wrappers, MyWrapper::getEntity, repository);          // wrapper holds 1 entity
extractAndSaveFlat(wrappers, MyWrapper::getEntities, repository);    // wrapper holds a List<E>
extractAndSaveIf(wrappers, MyWrapper::getEntity, e -> e.isValid(), repository);
```

These fall back to per-entity saves if the bulk `saveAll()` fails, so one bad row doesn't
block the rest of the chunk. Any entity that fails even the individual save is recorded via
`BatchUtility.addSkippedItem(entity, "WRITE", reason)`, so it's visible in
`BatchUtility.getSkippedItems()` even though it can't trigger Spring Batch's own
`SkipListener.onSkipInWrite` (that only fires for exceptions thrown out of the `ItemWriter`
itself — a caught-and-logged failure inside your own `save()` logic never reaches it).

---

## Part 2 — Exporting to files with `@BatchExportJob`

### Step 1: Define your columns and JPQL query

```java
import com.eazy.batch.annotation.BatchExportJob;
import com.eazy.batch.config.SimpleExportProcessor;
import com.eazy.batch.enums.StorageType;
import com.eazy.batch.model.ExportColumn;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
@BatchExportJob(
    jobName        = "exportEmployeeJob",
    stepName       = "exportEmployeeStep",
    entityClass    = Employee.class,
    storageType    = StorageType.LOCAL,
    localDirectory = "/var/exports",     // optional - falls back to
                                          // eazy.batch.export.local-directory, then temp dir
    fileName       = "employees"
)
public class EmployeeExportConfig implements SimpleExportProcessor<Employee> {

    private final NotificationService notificationService;

    // Plain JPQL - no rowMapper, no raw SQL
    @Override
    public String getJpqlQuery() {
        return "SELECT e FROM Employee e WHERE e.active = true ORDER BY e.name";
    }

    // Method references map entity fields to columns; supports nested access
    @Override
    public List<ExportColumn<Employee>> getColumns() {
        return List.of(
            col("ID",            Employee::getId),
            col("Name",          Employee::getName),
            col("Email",         Employee::getEmail),
            col("Manager Email", e -> e.getManager().getEmail()),  // nested, null-safe
            col("Department",    e -> e.getDept().getName())
        );
    }

    @Override
    public void onSaveComplete(String fileUrl) {
        notificationService.notifyAdmins("Export ready: " + fileUrl);
    }

    @Override
    public void onSaveFailure(Throwable error) {
        notificationService.notifyAdmins("Export failed: " + error.getMessage());
    }
}
```

### Step 2: Launch it

```java
@RestController
@RequiredArgsConstructor
public class ExportController {

    private final TaskExecutorJobLauncher jobLauncher;

    @Qualifier("exportEmployeeJob")
    private final Job exportEmployeeJob;

    @PostMapping("/api/export/employees")
    public ResponseEntity<String> exportEmployees() throws Exception {
        jobLauncher.run(exportEmployeeJob, new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters());
        return ResponseEntity.ok("Export started - check onSaveComplete for the file URL");
    }
}
```

That's it — no reader, no writer, no storage plumbing to hand-write. The generated code
streams the JPA query results in, writes them to a streaming Excel/CSV file (memory-safe for
large exports), uploads to storage, and calls `onSaveComplete(url)`.

### Using your own storage (S3, Firebase, GCS, ...)

Only local-disk storage ships built in. For anything else, implement `ExportStorageService`
yourself and register it under the qualifier `"customExportStorage"`:

```java
@Bean("customExportStorage")
public ExportStorageService myStorage() {
    return (inputStream, fileName, contentType) -> {
        s3Client.putObject(bucket, fileName, inputStream);
        return "https://s3.amazonaws.com/my-bucket/" + fileName;
    };
}
```

```java
@BatchExportJob(
    jobName     = "exportEmployeeJob",
    stepName    = "exportEmployeeStep",
    entityClass = Employee.class,
    storageType = StorageType.CUSTOM,   // routes to "customExportStorage" instead
    fileName    = "employees"
)
```

There is **no built-in S3/Firebase/GCS implementation** despite `ExportStorageService`'s
javadoc listing them as examples — those are illustrations of what *you* can plug in, not
shipped code.

### What gets generated for `@BatchExportJob`

| Generated class | What it is |
|---|---|
| `EmployeeExportConfigExportConfiguration` | `Job` + `Step` beans, with `JobCompletionListener` and the skip listener attached |
| `EmployeeExportConfigExportReader` | `JpaCursorItemReader` built from your `getJpqlQuery()` |
| `EmployeeExportConfigExportWriter` | `@StepScope` Csv/Excel writer — a **fresh instance per job run**, so repeated executions never share state or a stale filename timestamp |
| `EmployeeExportConfigExportStepListener` | Calls `onExportStart()`, then `finalizeAndSave()` after the last chunk |
| `EmployeeExportConfigExportSkipListener` | Same skip-tracking as `@BatchJob`'s skip listener |

### `@BatchExportJob` annotation reference

```java
@BatchExportJob(
    jobName        = "exportEmployeeJob",     // Required
    stepName       = "exportEmployeeStep",    // Required
    exportName     = "",                      // Optional: display name for logging
    entityClass    = Employee.class,          // Required: JPA entity being exported

    storageType    = StorageType.LOCAL,       // LOCAL (built-in) or CUSTOM (you provide)
    fileName       = "export",                // Base name; a timestamp is appended per run
    fileType       = ExportFileType.EXCEL,    // EXCEL or CSV
    sheetName      = "",                      // Excel only; defaults to fileName
    localDirectory = "",                      // LOCAL only; falls back to
                                               // eazy.batch.export.local-directory, then temp dir

    chunkSize = 500,                          // Rows per chunk
    skipLimit = 10,                           // Must be > 0 - Spring Batch 6 rejects 0

    dryRun = false,                           // NEW: read + validate but produce no file -
                                               // useful for testing a JPQL query and column
                                               // mappings against real data

    notifyOnCompletion = false,               // NEW: email on export completion (needs
                                               // recipients + SMTP config) - same mechanism
                                               // as @BatchJob's notifyOnCompletion
    notifyOnFailure    = false,               // NEW: email if the export fails
    recipients         = {},                  // Required if either notify* flag is true

    async = true                              // Documentation-only today - see note below
)
```

> **Note on `async`:** launching is entirely up to your own controller code (you call
> `jobLauncher.run(...)`), so this flag doesn't currently switch any framework behavior. It's
> there as a reminder to actually launch asynchronously (e.g. via `TaskExecutorJobLauncher`,
> which this library configures for you) rather than to imply the framework enforces it.

---

## Configuration Properties

Add to `application.properties`:

```properties
# Core
eazy.batch.enabled=true
eazy.batch.thread-pool-size=5
eazy.batch.queue-capacity=100

# Defaults used when a job's chunkSize/skipLimit is left at -1 (@BatchJob only -
# @BatchExportJob's chunkSize/skipLimit always use its own literal defaults, 500/10)
eazy.batch.default-chunk-size=100
eazy.batch.default-skip-limit=10

# How long skipped-item details are retained in memory (BatchUtility.getSkippedItems())
eazy.batch.cleanup-after-hours=24

# Micrometer metrics: batch.items.processed/skipped, batch.job.success/failure/duration
eazy.batch.metrics-enabled=false

# Progress logging during long-running steps
eazy.batch.progress-tracking-enabled=true
eazy.batch.progress-update-interval=100

# Email notifications (@BatchJob notifyOnCompletion/notifyOnFailure)
eazy.batch.email-notifications-enabled=false
eazy.batch.smtp-host=
eazy.batch.smtp-port=587
eazy.batch.smtp-username=
eazy.batch.smtp-password=
eazy.batch.from-email=noreply@batch.com

# Default directory for @BatchExportJob(storageType = LOCAL) when the job itself
# doesn't set localDirectory(). Falls back to the system temp directory if blank.
eazy.batch.export.local-directory=

# Live progress + error report over WebSocket - see dedicated section below
eazy.batch.websocket-enabled=true
eazy.batch.websocket-endpoint=/ws-batch
eazy.batch.websocket-topic-prefix=/topic/batch-progress

# Spring Batch settings
spring.batch.job.enabled=false
# Leave as 'never' - this library's own smart CommandLineRunner creates the batch
# tables idempotently. Setting this to 'always' makes Spring Boot ALSO re-run its
# bundled (non-idempotent) schema scripts on every startup, which fails with
# "relation already exists" on the second run.
spring.batch.jdbc.initialize-schema=never
```

---

## Skip tracking

Every skip (read, process, or write failure) across both `@BatchJob` and `@BatchExportJob`
is recorded via `BatchUtility`, independent of Spring Batch's own `stepExecution.getSkipCount()`:

```java
List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems();      // current job
List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems(jobExecutionId);
int count = BatchUtility.getSkippedItemCount();
```

Each `BatchSkippedItem` has the offending item (may be `null` for read failures, since there's
no parsed item yet), the phase (`READ`/`PROCESS`/`WRITE`), and the failure reason. Data is
kept for `eazy.batch.cleanup-after-hours` (default 24h) and cleared automatically at the
start/end of each job.

---

## Metrics

Set `eazy.batch.metrics-enabled=true` with a `MeterRegistry` on the classpath (e.g.
`micrometer-registry-prometheus`) to get:

- `batch.items.processed{job=...}`
- `batch.items.skipped{job=...,phase=...}`
- `batch.job.success{job=...}` / `batch.job.failure{job=...}`
- `batch.job.duration{job=...}`

These are recorded automatically for both `@BatchJob` and `@BatchExportJob` — no extra code
needed.

---

## Live progress + error report over WebSocket (no Kafka needed)

Every job — `@BatchJob` or `@BatchExportJob` — automatically broadcasts progress over a
built-in STOMP/WebSocket endpoint. No external broker: Spring's in-memory simple broker
handles delivery to whichever browser tabs are subscribed. This is on by default
(`eazy.batch.websocket-enabled=true`).

**What gets sent, to where:**

- After every chunk, a `PROGRESS` message is broadcast to
  `{websocketTopicPrefix}/{jobExecutionId}` (default topic prefix `/topic/batch-progress`,
  so e.g. `/topic/batch-progress/42`).
- Exactly one `COMPLETED` or `FAILED` message is sent at the end. If any rows were skipped,
  that message carries a base64-encoded `.xlsx` error report (columns: Item / Phase / Reason)
  in `errorFileBase64` — decode it client-side and either save it or turn it into a download
  link. No error report is attached if nothing was skipped.

**Message shape** (`BatchProgressMessage`, serialized as JSON):

```json
{
  "type": "PROGRESS",
  "jobExecutionId": 42,
  "jobName": "myJob",
  "readCount": 500,
  "writeCount": 480,
  "skipCount": 20,
  "durationMs": null,
  "errorFileName": null,
  "errorFileBase64": null,
  "errorFileSizeBytes": null
}
```

On completion, `type` becomes `"COMPLETED"`/`"FAILED"`, `durationMs` is set, and — only if
`skipCount > 0` — `errorFileName`/`errorFileBase64`/`errorFileSizeBytes` are populated.

### Server side — nothing to configure

The endpoint, broker, and every job's broadcast are wired up automatically. Your controller
just needs to give the client the `jobExecutionId` to subscribe to:

```java
@RestController
@RequiredArgsConstructor
public class BatchController {

    private final TaskExecutorJobLauncher jobLauncher;

    @Qualifier("myJob")
    private final Job myJob;

    @PostMapping("/api/batch/upload")
    public ResponseEntity<Long> upload(@RequestParam("file") MultipartFile file) throws Exception {
        String filePath = fileService.saveFile(file);
        JobParameters params = new JobParametersBuilder()
                .addString("filePath", filePath)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(myJob, params);
        // JobExecution (and its id) is available immediately - the job itself
        // runs in the background via TaskExecutorJobLauncher.
        return ResponseEntity.ok(execution.getId());
    }
}
```

### Client side (JavaScript, using `@stomp/stompjs` + `sockjs-client`)

```javascript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const jobExecutionId = await startUpload(file); // POST above, returns the id

const client = new Client({
  webSocketFactory: () => new SockJS('/ws-batch'),
  onConnect: () => {
    client.subscribe(`/topic/batch-progress/${jobExecutionId}`, (frame) => {
      const msg = JSON.parse(frame.body);

      if (msg.type === 'PROGRESS') {
        updateProgressBar(msg.writeCount, msg.readCount);
      } else {
        // COMPLETED or FAILED
        if (msg.errorFileBase64) {
          downloadBase64File(msg.errorFileBase64, msg.errorFileName);
        }
        showFinalStatus(msg.type, msg);
        client.deactivate();
      }
    });
  },
});
client.activate();

function downloadBase64File(base64, fileName) {
  const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
  const blob = new Blob([bytes], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}
```

### Configuration

```properties
eazy.batch.websocket-enabled=true                     # default true
eazy.batch.websocket-endpoint=/ws-batch                # STOMP endpoint (SockJS fallback included)
eazy.batch.websocket-topic-prefix=/topic/batch-progress
```

Set `eazy.batch.websocket-enabled=false` to disable entirely (no endpoint registered, no
messages sent — every job runs exactly as before, just without the broadcast).

---

## Overriding the default `JobCompletionListener`

```java
import com.eazy.batch.listener.JobCompletionListener;
import com.eazy.batch.service.BatchWebSocketNotifier;
import com.eazy.batch.service.MetricsService;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BatchConfig {

    @Bean
    public JobCompletionListener jobCompletionListener(MetricsService metricsService,
                                                         BatchWebSocketNotifier webSocketNotifier) {
        return new JobCompletionListener(metricsService, webSocketNotifier) {
            @Override
            public void afterJob(JobExecution jobExecution) {
                super.afterJob(jobExecution);
                // Add your custom logic here
            }
        };
    }
}
```

Your bean takes priority automatically (`@ConditionalOnMissingBean`).

---

## Restart support and parallel processing

**Restart:** both file readers (`CSVItemReader`, `ExcelItemReaderWithHeaderValidation`)
implement `ItemStream` and checkpoint their row position into the step's `ExecutionContext`
after every chunk. If a step fails partway through, restarting that job execution resumes
from the last committed chunk instead of re-reading the whole file from row 0/1.

**Parallel processing:** set `@BatchJob(parallelProcessing = true)` to run a step's
read-process-write cycle across multiple threads from the shared `batchTaskExecutor` pool
(sized by `eazy.batch.thread-pool-size`). The reader is automatically wrapped in Spring
Batch's own `SynchronizedItemStreamReader`, since the built-in readers hold internal state
and aren't thread-safe on their own — this trades some throughput for correctness, which is
the right default. As with any multi-threaded step, item order isn't guaranteed and a chunk
may contain non-consecutive items.

---

## Excel/CSV file format for `@BatchJob`

Headers must match DTO field names (case-insensitive):

| name | age |
|------|-----|
| John | 25  |
| Jane | 30  |

The reader validates headers up front (fails fast on template mismatches), then per row:
reads it, maps columns to DTO fields, converts types (including `LocalDate`/`LocalDateTime`
via `@ExcelDateFormat`, and enums via `fromDisplayName()`/`valueOf()`/case-insensitive
match), and validates it (Jakarta Bean Validation + your `customValidate()`).

---

## Known limitations / roadmap

Being upfront about what this library **doesn't** do yet, so you don't discover it the hard
way:

- **No partitioning.** `@BatchJob(partitioned = true, partitions = N)` fails compilation with
  a clear error rather than silently doing nothing — no partition handler is generated yet.
- **No incremental/checkpointed extraction across separate runs.** `@BatchJob(incremental =
  true, checkpointColumn = "...")` fails compilation for the same reason. (Note: this is
  different from — and unrelated to — the restart-within-a-failed-run support described
  above, which IS implemented.)
- **`FileType.JSON`/`FileType.XML`** and **`ReaderType.DATABASE`/`API`/`KAFKA`** are declared
  on their enums but not implemented. Using them fails the build with a clear compiler error
  rather than a confusing runtime one.
- **No built-in S3/Firebase/GCS export storage** — only `LOCAL`. Implement
  `ExportStorageService` yourself for anything else (see above).
- **No retry for `@BatchExportJob`** — `enableRetry`/`retryLimit`/`retryableExceptions` exist
  on `@BatchJob` with no equivalent on `@BatchExportJob` yet. (`notifyOnCompletion`/
  `notifyOnFailure`/`recipients` ARE supported on both annotations.)
- **`CustomJobCompletionListener` is not auto-wired.** Implementing it and registering it as
  a bean does nothing on its own — every generated `Job` wires in exactly one concrete
  `JobCompletionListener`, not a list of listeners. To customize completion behavior, override
  `JobCompletionListener` itself (see "Overriding the default `JobCompletionListener`" above).

If you need any of these, open an issue (or a PR) — the annotation processor architecture
makes most of them additive rather than invasive to add.

---

## Running the test suite

```bash
mvn test
```

The test suite includes `AnnotationProcessorCodegenTest`, which compiles a handful of real
`@BatchJob`/`@BatchExportJob` sample classes (covering plain, `parallelProcessing = true`,
`notifyOnCompletion`/`notifyOnFailure`, and `dryRun = true` configurations) as part of the
build. If either annotation processor emits invalid generated source — a bad import, a call
to a method or constructor that doesn't exist, a type mismatch — `mvn test` fails right here
with a normal `javac` compiler error, rather than only surfacing in a downstream consumer's
project.

---

## Troubleshooting

### Generated files not created

```bash
mvn clean compile -X
```

Look for `✅ Successfully generated batch configuration for: MyJobConfig` (or the
`[BatchExportJob]` equivalent). A `❌ Invalid @BatchJob configuration` / `❌ Invalid
@BatchExportJob configuration` message means a validation check failed (e.g. `skipLimit` was
0, or an unsupported `fileType`/`readerType` was used) — read the message, it names the
specific problem.

### Bean not found at runtime

1. Confirm the annotation processor path is configured in your POM (Step 3 above).
2. Mark `target/generated-sources/annotations` as a source root in your IDE.
3. Verify `@Qualifier("...")` matches `jobName` exactly.

### IDE doesn't recognize generated code

**IntelliJ:** right-click `target/generated-sources/annotations` → Mark Directory as →
Generated Sources Root.
**Eclipse:** Project Properties → Java Build Path → Source → Add Folder → select
`target/generated-sources/annotations`.

---

## Deployment

### Local development
```bash
mvn clean install
# JAR in: ~/.m2/repository/com/eazy/eazy-batch-processor/1.0.1/
```

### Production (Nexus/Artifactory)
1. Deploy the library JAR to your Maven repository.
2. Your application pulls it as a normal dependency.
3. Ship your application as usual.

---

## License

MIT License — see `LICENSE`.
