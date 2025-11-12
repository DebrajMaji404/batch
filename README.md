# Spring Boot Batch Processor Starter

Auto-generate Spring Batch configuration using annotations. Like MapStruct but for batch jobs.

## Installation

### Step 1: Build the Starter

```bash
cd spring-boot-starter-batch-processor
mvn clean install
```

JAR is installed to: `~/.m2/repository/com/eazy/spring-boot-starter-batch-processor/1.0.0/`

### Step 2: Add Dependency to Your Application POM

Add this to your main application's `pom.xml` in the `<dependencies>` section:

```xml
<dependency>
    <groupId>com.eazy</groupId>
    <artifactId>spring-boot-starter-batch-processor</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Step 3: Configure Maven Compiler Plugin

Add this to your main application's `pom.xml` in the `<build>` section:

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
                        <artifactId>spring-boot-starter-batch-processor</artifactId>
                        <version>1.0.0</version>
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

### Step 4: Compile Your Application

```bash
mvn clean compile
```

Done! Generated files will be in `target/generated-sources/annotations/`

---

## How to Use

### Step 1: Create Your DTO Class

```java
import lombok.Data;

@Data
public class MyDTO {
    private String name;
    private Integer age;
}
```

### Step 2: Create Your Wrapper Class

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

### Step 3: Create Your Batch Job Config

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

### Step 4: Use in Your Controller

```java
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
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

---

## What Gets Generated

The annotation processor automatically generates a complete Spring Batch configuration with these beans:

- **myJob** - Spring Batch Job
- **myStep** - Step configuration
- **myStepReader** - Excel file reader
- **myStepProcessor** - Item processor with validation
- **myStepWriter** - Batch writer to database
- **myStepSkipListener** - Error handling listener

All beans are automatically registered in Spring context.

---

## @BatchJob Annotation Reference

```java
@BatchJob(
    jobName = "myJob",              // Required: Name of Job bean
    stepName = "myStep",            // Required: Name of Step bean
    batchName = "MyBatch",          // Optional: Display name for logging
    dtoClass = MyDTO.class,         // Required: Input DTO class
    wrapperClass = MyWrapper.class, // Required: Output wrapper class
    chunkSize = 100,                // Optional: Items per chunk (default: 100)
    skipLimit = 10                  // Optional: Failed items to skip (default: 10)
)
```

---

## SimpleBatchProcessor Methods

### Required Methods

```java
// Process one item
@Override
public MyWrapper process(MyDTO dto) throws Exception {
    // Your business logic
    return wrapper;
}

// Save batch of items
@Override
public void save(List<MyWrapper> wrappers) {
    // Save to database
}
```

### Helper Methods for Saving

**Save single entity from wrapper:**
```java
extractAndSave(wrappers, MyWrapper::getEntity, repository);
```

**Save list of entities from wrapper:**
```java
extractAndSaveFlat(wrappers, MyWrapper::getEntities, repository);
```

**Custom identifier for logging:**
```java
@Override
public String getIdentifier(Object item) {
    return "Item-" + item.getId();
}
```

---

## Configuration Properties

Add to `application.properties`:

```properties
# Batch processor settings
eazy.batch.thread-pool-size=5
eazy.batch.queue-capacity=100
eazy.batch.default-chunk-size=100
eazy.batch.default-skip-limit=10

# Spring Batch settings
spring.batch.job.enabled=false
spring.batch.jdbc.initialize-schema=always
```

---

## Override Job Completion Listener

Create a `@Configuration` class to override the default listener:

```java
import com.eazy.batch.listener.JobCompletionListener;
import org.springframework.batch.core.JobExecution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BatchConfig {
    
    @Bean
    public JobCompletionListener jobCompletionListener() {
        return new JobCompletionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                super.beforeJob(jobExecution);
                System.out.println("Job started: " + jobExecution.getJobInstance().getJobName());
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                super.afterJob(jobExecution);
                System.out.println("Job completed: " + jobExecution.getStatus());
                // Add your custom logic here
            }
        };
    }
}
```

---

## Excel File Format

Create Excel files with headers matching DTO field names (case-insensitive):

| name | age |
|------|-----|
| John | 25  |
| Jane | 30  |

The reader automatically:
- Reads row by row
- Maps columns to DTO fields
- Converts data types
- Validates data

---

## Troubleshooting

### Generated files not created

Run with debug output:
```bash
mvn clean compile -X
```

Look for: `Generated batch configuration for: MyJobConfig`

### Bean not found at runtime

1. Ensure annotation processor path is configured in POM
2. Mark `target/generated-sources/annotations` as source root in IDE
3. Verify `@Qualifier` name matches `jobName` in `@BatchJob`

### IDE doesn't recognize generated code

**IntelliJ:**
- Right-click `target/generated-sources/annotations`
- Mark Directory as → Generated Sources Root

**Eclipse:**
- Project Properties → Java Build Path → Source
- Add Folder → Select `target/generated-sources/annotations`

---

## Example: Complete Working Code

**DTO:**
```java
@Data
public class DisciplineOfferFeesTypeDTO {
    private String disciplineCode;
    private String offerType;
    private Double feeAmount;
}
```

**Wrapper:**
```java
@Data
public class DisciplineOfferFeesTypeWrapper {
    private List<DisciplineOfferFee> disciplineOfferFees;
}
```

**Job Config:**
```java
@Component
@RequiredArgsConstructor
@BatchJob(
    jobName = "testDisciplineFeeJob",
    stepName = "testDisciplineFeeStep",
    dtoClass = DisciplineOfferFeesTypeDTO.class,
    wrapperClass = DisciplineOfferFeesTypeWrapper.class
)
public class TestDisciplineOfferFeesTypeJobConfig 
        implements SimpleBatchProcessor<DisciplineOfferFeesTypeDTO, DisciplineOfferFeesTypeWrapper> {
    
    private final DisciplineOfferFeeService disciplineOfferFeeService;
    private final DisciplineOfferFeeRepository disciplineOfferFeeRepository;

    @Override
    public DisciplineOfferFeesTypeWrapper process(DisciplineOfferFeesTypeDTO dto) throws Exception {
        return new DisciplineOfferFeesTypeWrapper(
            disciplineOfferFeeService.getForBatch(dto)
        );
    }

    @Override
    public void save(List<DisciplineOfferFeesTypeWrapper> wrappers) {
        extractAndSaveFlat(
            wrappers,
            DisciplineOfferFeesTypeWrapper::getDisciplineOfferFees,
            disciplineOfferFeeRepository
        );
    }
}
```

**Usage:**
```java
@RestController
@RequiredArgsConstructor
public class BatchController {
    
    private final TaskExecutorJobLauncher jobLauncher;
    
    @Qualifier("testDisciplineFeeJob")
    private final Job testDisciplineFeeJob;
    
    @PostMapping("/api/batch/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) throws Exception {
        String filePath = fileService.saveFile(file);
        JobParameters params = new JobParametersBuilder()
                .addString("filePath", filePath)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
        jobLauncher.run(testDisciplineFeeJob, params);
        return ResponseEntity.ok("Batch started");
    }
}
```

---

## Deployment

### Local Development
```bash
mvn clean install
# JAR in: ~/.m2/repository/com/eazy/spring-boot-starter-batch-processor/1.0.0/
```

### Production (Using Nexus/Artifactory)
1. Deploy starter JAR to your Maven repository
2. Main app pulls from repository automatically
3. Push main app to server

---

## License

MIT License - See LICENSE file