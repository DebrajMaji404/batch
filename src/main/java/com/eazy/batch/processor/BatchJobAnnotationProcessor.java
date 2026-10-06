package com.eazy.batch.processor;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.enums.FileType;
import com.eazy.batch.enums.ReaderType;
import com.eazy.batch.enums.ReportType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Annotation processor that auto-generates Spring Batch configuration classes at compile time.
 *
 * When you annotate a class with @BatchJob, this processor automatically creates 5 classes:
 * 1. Configuration - Job and Step beans
 * 2. Reader - Excel/CSV file reader with header validation
 * 3. Processor - Item processor with validation hooks
 * 4. Writer - Item writer that delegates to your save method
 * 5. SkipListener - Error tracking and logging
 *
 * This eliminates 500+ lines of boilerplate Spring Batch code per job.
 *
 * @version 3.0
 * @author EazyBatch Framework
 */
@SupportedAnnotationTypes("com.eazy.batch.annotation.BatchJob")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(BatchJobAnnotationProcessor.SKIP_OPTION)
public class BatchJobAnnotationProcessor extends AbstractProcessor {

    // FIXED: this option was referenced from pom.xml's compiler args
    // (-Aprocessor.skip.batchjob=true) but never actually read here, so it
    // silently did nothing. It's now honored in process() below.
    static final String SKIP_OPTION = "processor.skip.batchjob";

    private static final String INDENT = "    ";
    private static final String DOUBLE_INDENT = INDENT + INDENT;
    private static final String TRIPLE_INDENT = INDENT + INDENT + INDENT;
    private static final String QUAD_INDENT = INDENT + INDENT + INDENT + INDENT;
    private static final String QUINT_INDENT = QUAD_INDENT + INDENT;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, @NotNull RoundEnvironment roundEnv) {
        if ("true".equalsIgnoreCase(processingEnv.getOptions().get(SKIP_OPTION))) {
            logInfo("⏭️ Skipping @BatchJob annotation processing (-A" + SKIP_OPTION + "=true)");
            return true;
        }
        logInfo("Processing started");
        for (Element element : roundEnv.getElementsAnnotatedWith(BatchJob.class)) {
            if (element instanceof TypeElement typeElement) {
                try {
                    generateBatchComponents(typeElement);
                    logInfo("✅ Successfully generated batch configuration for: " + typeElement.getSimpleName());
                } catch (IOException e) {
                    logError("❌ Failed to generate batch configuration: " + e.getMessage(), element);
                } catch (IllegalArgumentException e) {
                    // FIXED: validation failures previously escaped uncaught
                    // here, producing a raw "exception occurred" stack trace
                    // instead of a clean compiler error.
                    logError("❌ Invalid @BatchJob configuration: " + e.getMessage(), element);
                }
            }
        }
        return true;
    }

    private void generateBatchComponents(@NotNull TypeElement element) throws IOException {
        BatchJob annotation = element.getAnnotation(BatchJob.class);
        String packageName = processingEnv.getElementUtils().getPackageOf(element).toString();
        String className = element.getSimpleName().toString();

        // Extract annotation parameters
        String jobName = annotation.jobName();
        String stepName = annotation.stepName();
        int chunkSize = annotation.chunkSize();
        int skipLimit = annotation.skipLimit();
        FileType fileType = annotation.fileType();
        ReaderType readerType = annotation.readerType();
        String sheetName = annotation.sheetName();
        int sheetIndex = annotation.sheetIndex();
        boolean dryRun = annotation.dryRun();
        boolean enableRetry = annotation.enableRetry();
        int retryLimit = annotation.retryLimit();
        String[] retryableExceptions = annotation.retryableExceptions();
        boolean cacheValidation = annotation.cacheValidation();
        String[] requiredParameters = annotation.requiredParameters();
        String[] optionalParameters = annotation.optionalParameters();
        boolean parallelProcessing = annotation.parallelProcessing();
        int threadPoolSize = annotation.threadPoolSize();
        boolean partitioned = annotation.partitioned();
        boolean incremental = annotation.incremental();
        boolean notifyOnCompletion = annotation.notifyOnCompletion();
        boolean notifyOnFailure = annotation.notifyOnFailure();
        String[] recipients = annotation.recipients();
        boolean hasNotification = notifyOnCompletion || notifyOnFailure;
        ReportType reportType = annotation.reportType();
        continueOnSkipLimit = annotation.onSkipLimit() == com.eazy.batch.enums.SkipLimitMode.CONTINUE;
        rowIsolation = annotation.rowIsolation();
        uniqueKey = annotation.uniqueKey();
        skipOnFqns = typeNames(annotation, true);
        noSkipOnFqns = typeNames(annotation, false);

        // Extract class names
        String dtoClassFqn = getClassFqn(annotation, "dtoClass");
        String wrapperClassFqn = getClassFqn(annotation, "wrapperClass");
        String dtoClassName = getSimpleName(dtoClassFqn);
        String wrapperClassName = getSimpleName(wrapperClassFqn);

        // Validate and generate
        validateConfiguration(jobName, stepName, dtoClassName, wrapperClassName, chunkSize, skipLimit, fileType, readerType, partitioned, incremental);
        validateUniqueKey(dtoClassFqn, element);
        // FIXED: notifyOnCompletion/notifyOnFailure/recipients were declared
        // on @BatchJob since the beginning but never actually wired to
        // anything in this branch's processor - setting them had zero
        // effect. Now generates and attaches a real notification listener.
        if (hasNotification && (recipients == null || recipients.length == 0)) {
            throw new IllegalArgumentException(
                    "notifyOnCompletion/notifyOnFailure is true but recipients() is empty on @BatchJob for " + className);
        }
        generateJobConfiguration(packageName, className, jobName, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn, chunkSize, skipLimit, enableRetry, retryLimit, retryableExceptions, requiredParameters, optionalParameters, parallelProcessing, threadPoolSize, hasNotification, fileType, sheetName, sheetIndex, reportType);
        generateReader(packageName, className, stepName, dtoClassName, dtoClassFqn, fileType, sheetName, sheetIndex);
        generateProcessor(packageName, className, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn, dryRun, cacheValidation);
        generateWriter(packageName, className, stepName, wrapperClassName, wrapperClassFqn, dryRun);
        generateSkipListener(packageName, className, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn);
        if (hasNotification) {
            generateNotificationListener(packageName, className, jobName, notifyOnCompletion, notifyOnFailure, recipients);
        }
    }

    private void validateConfiguration(String jobName, String stepName, String dtoClassName, String wrapperClassName, int chunkSize, int skipLimit, FileType fileType, ReaderType readerType, boolean partitioned, boolean incremental) {
        if (jobName == null || jobName.trim().isEmpty()) throw new IllegalArgumentException("jobName cannot be empty");
        if (stepName == null || stepName.trim().isEmpty()) throw new IllegalArgumentException("stepName cannot be empty");
        if (chunkSize != -1 && chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive (or -1 to use eazy.batch.default-chunk-size)");
        // FIXED: Spring Batch 6's skip-limit enforcement rejects a skipLimit
        // of exactly 0 at context-startup time ("skipLimit must be greater
        // than zero"). The old check here only rejected negative values, so
        // skipLimit=0 passed compile-time validation and then crashed the
        // app at runtime.
        if (skipLimit != -1 && skipLimit <= 0) throw new IllegalArgumentException("skipLimit must be greater than zero (or -1 to use eazy.batch.default-skip-limit)");
        // FIXED: fail fast at compile time instead of silently generating the
        // wrong reader (or one that doesn't exist) at runtime.
        if (readerType != ReaderType.FILE) {
            throw new IllegalArgumentException(
                    "readerType=" + readerType + " is not implemented yet. Only ReaderType.FILE is currently supported.");
        }
        if (fileType != FileType.CSV && fileType != FileType.EXCEL) {
            throw new IllegalArgumentException(
                    "fileType=" + fileType + " is not implemented yet. Only FileType.CSV and FileType.EXCEL are currently supported.");
        }
        // FIXED: partitioned/incremental were declared but silently ignored -
        // set them to true and nothing happened, no partition handler or
        // checkpoint logic was ever generated. Fail fast instead, consistent
        // with the readerType/fileType checks above.
        if (partitioned) {
            throw new IllegalArgumentException(
                    "partitioned=true is not implemented yet. Remove it (or set it to false) - see README 'Known limitations'.");
        }
        if (incremental) {
            throw new IllegalArgumentException(
                    "incremental=true is not implemented yet. Remove it (or set it to false) - see README 'Known limitations'.");
        }
    }

    private void generateJobConfiguration(String packageName, String className, String jobName, String stepName, String dtoClassName, String wrapperClassName, String dtoClassFqn, String wrapperClassFqn, int chunkSize, int skipLimit, boolean enableRetry, int retryLimit, String[] retryableExceptions, String[] requiredParameters, String[] optionalParameters, boolean parallelProcessing, int threadPoolSize, boolean hasNotification, FileType fileType, String sheetName, int sheetIndex, ReportType reportType) throws IOException {
        String generatedClassName = className + "Configuration";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + dtoClassFqn + ";");
            out.println("import " + wrapperClassFqn + ";");
            out.println("import com.eazy.batch.autoconfigure.BatchProcessorProperties;");
            out.println("import com.eazy.batch.enums.FileType;");
            out.println("import com.eazy.batch.enums.ReportType;");
            out.println("import com.eazy.batch.report.ReportSpec;");
            out.println("import com.eazy.batch.report.ReportSpecRegistry;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.job.Job;");
            out.println("import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;");
            out.println("import org.springframework.batch.core.listener.SkipListener;");
            out.println("import org.springframework.batch.core.step.Step;");
            out.println("import org.springframework.batch.core.job.builder.JobBuilder;");
            out.println("import com.eazy.batch.listener.JobCompletionListener;");
            out.println("import com.eazy.batch.listener.BatchProgressChunkListener;");
            out.println("import org.springframework.batch.core.repository.JobRepository;");
            out.println("import org.springframework.batch.core.step.builder.StepBuilder;");
            out.println("import org.springframework.batch.infrastructure.item.ItemProcessor;");
            out.println("import org.springframework.batch.infrastructure.item.ItemStreamReader;");
            out.println("import org.springframework.batch.infrastructure.item.ItemWriter;");
            if (parallelProcessing) {
                out.println("import org.springframework.batch.infrastructure.item.support.builder.SynchronizedItemStreamReaderBuilder;");
                out.println("import org.springframework.beans.factory.annotation.Qualifier;");
                out.println("import org.springframework.core.task.TaskExecutor;");
            }
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import org.springframework.transaction.PlatformTransactionManager;");
            out.println();
            out.println("/**");
            out.println(" * Auto-generated Spring Batch configuration for " + className);
            out.println(" * DO NOT MODIFY - Changes will be overwritten on recompilation");
            out.println(" * @generated by " + getClass().getName());
            out.println(" */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " {");
            out.println();
            out.println(INDENT + "private final JobRepository jobRepository;");
            out.println(INDENT + "private final PlatformTransactionManager transactionManager;");
            out.println(INDENT + "private final JobCompletionListener jobCompletionListener;");
            out.println(INDENT + "private final BatchProcessorProperties batchProcessorProperties;");
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public Job " + jobName + "(Step " + stepName + (hasNotification ? ", " + className + "NotificationListener notificationListener" : "") + ") {");
            out.println(DOUBLE_INDENT + "log.info(\"Initializing batch job: {}\", \"" + jobName + "\");");
            // Tells the completion listener how to re-read this job's input file
            // so it can build the ERRORS / ALL report with every row accounted for.
            out.println(DOUBLE_INDENT + "ReportSpecRegistry.register(new ReportSpec(\"" + jobName + "\", " + dtoClassName + ".class, FileType." + fileType.name() + ", "
                    + sheetIndex + ", " + (sheetName.isEmpty() ? "null" : "\"" + sheetName.replace("\"", "\\\"") + "\"") + ", ReportType." + reportType.name() + "));");
            out.println(DOUBLE_INDENT + "var jobBuilder = new JobBuilder(\"" + jobName + "\", jobRepository)");
            out.println(QUAD_INDENT + ".listener(jobCompletionListener)");
            if (hasNotification) {
                out.println(QUAD_INDENT + ".listener(notificationListener)");
            }
            out.println(QUAD_INDENT + ".start(" + stepName + ");");
            if (requiredParameters.length > 0 || optionalParameters.length > 0) {
                // NEW: requiredParameters()/optionalParameters() were declared
                // on @BatchJob but never actually enforced anywhere - the job
                // would happily run with missing/misspelled job parameters.
                out.println(DOUBLE_INDENT + "jobBuilder = jobBuilder.validator(new DefaultJobParametersValidator(");
                out.println(TRIPLE_INDENT + "new String[]{" + joinQuoted(requiredParameters) + "},");
                // The library's own job parameters (set by the built-in upload endpoint and read by
                // BatchContext) must never be rejected as "unknown". DefaultJobParametersValidator
                // only rejects unknown keys when the optional set is non-empty, so extend it only then.
                String[] effectiveOptional = optionalParameters;
                if (optionalParameters.length > 0) {
                    java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(java.util.Arrays.asList(optionalParameters));
                    all.addAll(java.util.List.of("filePath", "originalFileName", "timestamp", "username", "dryRun", "deleteFileAfterJob"));
                    effectiveOptional = all.toArray(new String[0]);
                }
                out.println(TRIPLE_INDENT + "new String[]{" + joinQuoted(effectiveOptional) + "}));");
            }
            out.println(DOUBLE_INDENT + "return jobBuilder.build();");
            out.println(INDENT + "}");
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public Step " + stepName + "(");
            out.println(TRIPLE_INDENT + "ItemStreamReader<" + dtoClassName + "> reader,");
            out.println(TRIPLE_INDENT + "ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> processor,");
            out.println(TRIPLE_INDENT + "ItemWriter<" + wrapperClassName + "> writer,");
            out.println(TRIPLE_INDENT + "SkipListener<" + dtoClassName + ", " + wrapperClassName + "> skipListener,");
            if (parallelProcessing) {
                out.println(TRIPLE_INDENT + "@Qualifier(\"batchTaskExecutor\") TaskExecutor taskExecutor,");
            }
            out.println(TRIPLE_INDENT + "BatchProgressChunkListener progressChunkListener) {");
            out.println(DOUBLE_INDENT + "log.info(\"Initializing batch step: {}\", \"" + stepName + "\");");
            out.println(DOUBLE_INDENT + "int effectiveChunkSize = " + (chunkSize == -1 ? "batchProcessorProperties.getDefaultChunkSize();" : chunkSize + ";"));
            if (continueOnSkipLimit) {
                out.println(DOUBLE_INDENT + "int effectiveSkipLimit = Integer.MAX_VALUE; // onSkipLimit = CONTINUE");
            } else {
                out.println(DOUBLE_INDENT + "int effectiveSkipLimit = " + (skipLimit == -1 ? "batchProcessorProperties.getDefaultSkipLimit();" : skipLimit + ";"));
            }
            if (parallelProcessing) {
                // NEW: parallelProcessing()/threadPoolSize() were declared on
                // @BatchJob but never wired to anything - the step always ran
                // single-threaded regardless. A custom reader like this one
                // (CSVItemReader/ExcelItemReaderWithHeaderValidation) is
                // stateful and NOT thread-safe on its own, so it's wrapped in
                // SynchronizedItemStreamReader (Spring Batch's own recommended
                // pattern for this) before being handed to a multi-threaded
                // step. Concurrency is controlled by the injected
                // batchTaskExecutor's pool size, sized from
                // eazy.batch.thread-pool-size (threadPoolSize() on the
                // annotation is documentation-only for now, matching that
                // shared executor's own sizing).
                out.println(DOUBLE_INDENT + "ItemStreamReader<" + dtoClassName + "> synchronizedReader =");
                out.println(TRIPLE_INDENT + "new SynchronizedItemStreamReaderBuilder<" + dtoClassName + ">().delegate(reader).build();");
            }
            // FIXED: previously called new ChunkOrientedStepBuilder<>(name, jobRepository,
            // transactionManager, chunkSize) directly, guessing at a constructor
            // signature that doesn't exist ("no suitable constructor found").
            // Switched to the same StepBuilder(name, jobRepository) entry point +
            // deprecated-but-functional .chunk(chunkSize, transactionManager) overload
            // already used (and confirmed compiling) in the @BatchExportJob-generated
            // Step below, instead of guessing at ChunkOrientedStepBuilder's
            // constructor a third time. Same reasoning for .skipLimit(...).skip(...)
            // in place of manually constructing a LimitCheckingExceptionHierarchySkipPolicy.
            out.println(DOUBLE_INDENT + "return new StepBuilder(\"" + stepName + "\", jobRepository)");
            out.println(TRIPLE_INDENT + ".<" + dtoClassName + ", " + wrapperClassName + ">chunk(effectiveChunkSize, transactionManager)");
            out.println(TRIPLE_INDENT + ".reader(" + (parallelProcessing ? "synchronizedReader" : "reader") + ")");
            out.println(TRIPLE_INDENT + ".processor(processor)");
            out.println(TRIPLE_INDENT + ".writer(writer)");
            out.println(TRIPLE_INDENT + ".faultTolerant()");
            out.println(TRIPLE_INDENT + ".skipLimit(effectiveSkipLimit)");
            if (skipOnFqns.isEmpty()) {
                out.println(TRIPLE_INDENT + ".skip(Exception.class)");
            } else {
                for (String type : skipOnFqns) out.println(TRIPLE_INDENT + ".skip(" + type + ".class)");
            }
            for (String type : noSkipOnFqns) out.println(TRIPLE_INDENT + ".noSkip(" + type + ".class)");
            out.println(TRIPLE_INDENT + ".listener(skipListener)");
            // NEW: live progress push over WebSocket after every chunk.
            out.println(TRIPLE_INDENT + ".listener(progressChunkListener)");
            if (parallelProcessing) {
                out.println(TRIPLE_INDENT + ".taskExecutor(taskExecutor)");
            }
            out.println(TRIPLE_INDENT + ".build();");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateReader(String packageName, String className, String stepName, String dtoClassName, String dtoClassFqn, FileType fileType, String sheetName, int sheetIndex) throws IOException {
        String generatedClassName = className + "Reader";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            if (fileType == FileType.CSV) {
                out.println("import com.eazy.batch.reader.CSVItemReader;");
            } else {
                out.println("import com.eazy.batch.reader.ExcelItemReaderWithHeaderValidation;");
            }
            out.println("import " + dtoClassFqn + ";");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.configuration.annotation.StepScope;");
            out.println("import org.springframework.beans.factory.annotation.Value;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import org.springframework.core.io.FileSystemResource;");
            out.println();
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("public class " + generatedClassName + " {");
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "@StepScope");
            if (fileType == FileType.CSV) {
                out.println(INDENT + "public CSVItemReader<" + dtoClassName + "> " + stepName + "ItemReader(");
            } else {
                out.println(INDENT + "public ExcelItemReaderWithHeaderValidation<" + dtoClassName + "> " + stepName + "ItemReader(");
            }
            out.println(TRIPLE_INDENT + "@Value(\"#{jobParameters['filePath']}\") String filePath) {");
            out.println(DOUBLE_INDENT + "log.debug(\"Initializing " + fileType + " reader for file: {}\", filePath);");
            if (fileType == FileType.CSV) {
                out.println(DOUBLE_INDENT + "return new CSVItemReader<>(new FileSystemResource(filePath), " + dtoClassName + ".class);");
            } else {
                out.println(DOUBLE_INDENT + "return new ExcelItemReaderWithHeaderValidation<>(");
                out.println(QUAD_INDENT + "new FileSystemResource(filePath), " + dtoClassName + ".class, " + sheetIndex + ", " + (sheetName.isEmpty() ? "null" : "\"" + sheetName + "\"") + ");");
            }
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateProcessor(String packageName, String className, String stepName, String dtoClassName, String wrapperClassName, String dtoClassFqn, String wrapperClassFqn, boolean dryRun, boolean cacheValidation) throws IOException {
        String generatedClassName = className + "Processor";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + dtoClassFqn + ";");
            out.println("import " + wrapperClassFqn + ";");
            out.println("import com.eazy.batch.utility.BatchUtility;");
            out.println("import com.eazy.batch.report.BatchRowTracker;");
            out.println("import com.github.benmanes.caffeine.cache.Cache;");
            out.println("import com.github.benmanes.caffeine.cache.Caffeine;");
            out.println("import jakarta.validation.ConstraintViolation;");
            out.println("import jakarta.validation.Validator;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.infrastructure.item.ItemProcessor;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import java.util.List;");
            out.println("import java.util.Set;");
            out.println("import java.util.stream.Collectors;");
            out.println();
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " {");
            out.println();
            out.println(INDENT + "private final " + className + " delegate;");
            out.println(INDENT + "private final Validator validator;");
            if (cacheValidation) {
                // NEW: chunk/job-scoped validation result cache. Real-world
                // input files often contain repeated rows or repeated
                // lookup values - re-running Jakarta's reflection-based
                // validator.validate() on identical content is wasted work.
                // Keyed by dto.toString(), so this relies on a
                // content-reflecting toString() (e.g. Lombok's @Data) -
                // disable via @BatchJob(cacheValidation = false) if your
                // DTO's toString() doesn't reflect its full field content.
                out.println(INDENT + "private final Cache<String, Set<ConstraintViolation<" + dtoClassName + ">>> validationCache =");
                out.println(DOUBLE_INDENT + "Caffeine.newBuilder().maximumSize(10_000).build();");
            }
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> " + stepName + "ItemProcessor() {");
            out.println(DOUBLE_INDENT + "return dto -> {");
            // NEW: capture the ORIGINAL instance Spring Batch handed us, before
            // preProcess() possibly swaps it for a different object. Identity
            // de-duplication in addSkippedItemOnce only works against the
            // instance the framework reuses across its one-at-a-time chunk
            // rescan, which is this one.
            out.println(TRIPLE_INDENT + "final " + dtoClassName + " originalItem = dto;");
            out.println(TRIPLE_INDENT + "try {");
            out.println(QUAD_INDENT + "if (dto == null) { log.warn(\"Received null DTO\"); return null; }");
            out.println(QUAD_INDENT + "dto = delegate.preProcess(dto);");
            out.println(QUAD_INDENT + "if (!delegate.shouldProcess(dto)) { log.debug(\"Item filtered: {}\", delegate.getIdentifier(dto)); BatchRowTracker.onProcessed(originalItem); return null; }");
            if (cacheValidation) {
                out.println(QUAD_INDENT + "final " + dtoClassName + " dtoForValidation = dto;");
                out.println(QUAD_INDENT + "Set<ConstraintViolation<" + dtoClassName + ">> violations = validationCache.get(");
                out.println(QUINT_INDENT + "dtoForValidation.toString(), key -> validator.validate(dtoForValidation));");
            } else {
                out.println(QUAD_INDENT + "Set<ConstraintViolation<" + dtoClassName + ">> violations = validator.validate(dto);");
            }
            out.println(QUAD_INDENT + "if (!violations.isEmpty()) {");
            out.println(QUINT_INDENT + "String errors = violations.stream().map(v -> v.getPropertyPath() + \": \" + v.getMessage()).collect(Collectors.joining(\", \"));");
            out.println(QUINT_INDENT + "throw new RuntimeException(\"Validation failed: \" + errors);");
            out.println(QUAD_INDENT + "}");
            if (uniqueKey.length > 0) {
                out.println(QUAD_INDENT + "String duplicate = com.eazy.batch.utility.BatchUniqueKeys.check(originalItem, " + joinQuoted(uniqueKey) + ");");
                out.println(QUAD_INDENT + "if (duplicate != null) { throw new RuntimeException(duplicate); }");
            }
            out.println(QUAD_INDENT + "List<String> customErrors = delegate.customValidate(dto);");
            out.println(QUAD_INDENT + "if (customErrors != null && !customErrors.isEmpty()) {");
            out.println(QUINT_INDENT + "throw new RuntimeException(\"Custom validation failed: \" + String.join(\", \", customErrors));");
            out.println(QUAD_INDENT + "}");
            if (dryRun) {
                out.println(QUAD_INDENT + "log.debug(\"[DRY RUN] Would process: {}\", delegate.getIdentifier(dto)); BatchRowTracker.onProcessed(originalItem); return null;");
            } else {
                // Per-request dry run (upload with dryRun=true): validate only, process nothing.
                out.println(QUAD_INDENT + "if (com.eazy.batch.service.BatchContext.dryRunRequested()) { log.debug(\"[DRY RUN] Would process: {}\", delegate.getIdentifier(dto)); BatchRowTracker.onProcessed(originalItem); return null; }");
                out.println(QUAD_INDENT + "var result = delegate.process(dto);");
                // Tie the output to its upload row, and have the row counted as
                // imported only if this chunk's transaction really commits.
                out.println(QUAD_INDENT + "var processed = result != null ? delegate.postProcess(result) : null;");
                out.println(QUAD_INDENT + "BatchRowTracker.linkOutput(originalItem, processed);");
                out.println(QUAD_INDENT + "BatchRowTracker.onProcessed(originalItem);");
                out.println(QUAD_INDENT + "return processed;");
            }
            // NEW: record the skip HERE, at throw time, before rethrowing.
            // Spring Batch only fires SkipListener callbacks when a chunk
            // commits, so a step that dies mid-chunk (typically
            // SkipLimitExceededException) rolled back without ever recording
            // anything - leaving the final error report empty despite a
            // non-zero skipCount. Rethrowing unchanged preserves all of
            // Spring Batch's own skip/retry/fault-tolerance behavior; this
            // only adds bookkeeping alongside it.
            out.println(TRIPLE_INDENT + "} catch (Exception e) {");
            out.println(QUAD_INDENT + "String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();");
            out.println(QUAD_INDENT + "BatchUtility.addSkippedItemOnce(originalItem, \"PROCESS\", reason);");
            out.println(QUAD_INDENT + "throw e;");
            out.println(TRIPLE_INDENT + "}");
            out.println(DOUBLE_INDENT + "};");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateWriter(String packageName, String className, String stepName, String wrapperClassName, String wrapperClassFqn, boolean dryRun) throws IOException {
        String generatedClassName = className + "Writer";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + wrapperClassFqn + ";");
            out.println("import com.eazy.batch.report.BatchRowTracker;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.infrastructure.item.Chunk;");
            out.println("import org.springframework.batch.infrastructure.item.ItemWriter;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import java.util.List;");
            out.println("import java.util.Objects;");
            out.println("import java.util.stream.Collectors;");
            out.println();
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " {");
            out.println();
            out.println(INDENT + "private final " + className + " delegate;");
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public ItemWriter<" + wrapperClassName + "> " + stepName + "ItemWriter() {");
            out.println(DOUBLE_INDENT + "return chunk -> {");
            out.println(TRIPLE_INDENT + "List<" + wrapperClassName + "> validItems = chunk.getItems().stream().filter(Objects::nonNull).collect(Collectors.toList());");
            out.println(TRIPLE_INDENT + "if (validItems.isEmpty()) { log.warn(\"No valid items to write\"); return; }");
            if (dryRun) {
                out.println(TRIPLE_INDENT + "log.info(\"[DRY RUN] Would write {} items\", validItems.size());");
            } else {
                out.println(TRIPLE_INDENT + "log.debug(\"Writing {} items\", validItems.size()); " + (rowIsolation ? "com.eazy.batch.utility.BatchTransactions.run(true, () -> delegate.save(validItems));" : "com.eazy.batch.utility.BatchTransactions.run(false, () -> delegate.save(validItems));") + "");
                out.println(TRIPLE_INDENT + "BatchRowTracker.onWritten(validItems);");
            }
            out.println(DOUBLE_INDENT + "};");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateSkipListener(String packageName, String className, String stepName, String dtoClassName, String wrapperClassName, String dtoClassFqn, String wrapperClassFqn) throws IOException {
        String generatedClassName = className + "SkipListener";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + dtoClassFqn + ";");
            out.println("import " + wrapperClassFqn + ";");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.listener.SkipListener;");
            out.println("import org.springframework.lang.NonNull;");
            out.println("import org.springframework.stereotype.Component;");
            out.println("import static com.eazy.batch.utility.BatchUtility.addSkippedItem;");
            out.println("import static com.eazy.batch.utility.BatchUtility.addSkippedRead;");
            out.println("import static com.eazy.batch.utility.BatchUtility.addSkippedItemOnce;");
            out.println();
            out.println("@Slf4j");
            out.println("@Component");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " implements SkipListener<" + dtoClassName + ", " + wrapperClassName + "> {");
            out.println();
            out.println(INDENT + "private final " + className + " delegate;");
            out.println();
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void onSkipInRead(@NonNull Throwable throwable) {");
            out.println(DOUBLE_INDENT + "addSkippedRead(throwable);");
            out.println(DOUBLE_INDENT + "log.error(\"[SKIP-READ] {}\", throwable.getMessage(), throwable);");
            out.println(INDENT + "}");
            out.println();
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void onSkipInProcess(@NonNull " + dtoClassName + " dto, @NonNull Throwable throwable) {");
            // addSkippedItemOnce (not addSkippedItem): the generated
            // ItemProcessor already recorded this item at throw time so the
            // record survives an aborted chunk. When the chunk DOES commit,
            // this callback fires for the same item instance - identity
            // de-duplication collapses the two into one report row instead
            // of double-counting every skip.
            out.println(DOUBLE_INDENT + "addSkippedItemOnce(dto, \"PROCESS\", throwable.getMessage());");
            out.println(DOUBLE_INDENT + "log.error(\"[SKIP-PROCESS] {}: {}\", delegate.getIdentifier(dto), throwable.getMessage(), throwable);");
            out.println(INDENT + "}");
            out.println();
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void onSkipInWrite(@NonNull " + wrapperClassName + " wrapper, @NonNull Throwable throwable) {");
            out.println(DOUBLE_INDENT + "addSkippedItem(wrapper, \"WRITE\", throwable.getMessage());");
            out.println(DOUBLE_INDENT + "log.error(\"[SKIP-WRITE] {}: {}\", delegate.getIdentifier(wrapper), throwable.getMessage(), throwable);");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateNotificationListener(String packageName, String className, String jobName, boolean notifyOnCompletion, boolean notifyOnFailure, String[] recipients) throws IOException {
        String generatedClassName = className + "NotificationListener";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import com.eazy.batch.service.EmailNotificationService;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.BatchStatus;");
            out.println("import org.springframework.batch.core.job.JobExecution;");
            out.println("import org.springframework.batch.core.listener.JobExecutionListener;");
            out.println("import org.springframework.lang.NonNull;");
            out.println("import org.springframework.stereotype.Component;");
            out.println("import java.time.Duration;");
            out.println("import java.time.ZoneOffset;");
            out.println();
            out.println("/** Auto-generated notification listener for " + className + " — DO NOT MODIFY */");
            out.println("@Slf4j");
            out.println("@Component");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " implements JobExecutionListener {");
            out.println();
            out.println(INDENT + "private final EmailNotificationService emailNotificationService;");
            out.println(INDENT + "private static final String[] RECIPIENTS = " + arrayLiteral(recipients) + ";");
            out.println();
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void afterJob(@NonNull JobExecution jobExecution) {");
            out.println(DOUBLE_INDENT + "Duration duration = Duration.ZERO;");
            out.println(DOUBLE_INDENT + "if (jobExecution.getStartTime() != null && jobExecution.getEndTime() != null) {");
            out.println(TRIPLE_INDENT + "duration = Duration.between(jobExecution.getStartTime().toInstant(ZoneOffset.UTC), jobExecution.getEndTime().toInstant(ZoneOffset.UTC));");
            out.println(DOUBLE_INDENT + "}");
            out.println(DOUBLE_INDENT + "long processed = jobExecution.getStepExecutions().stream().mapToLong(se -> se.getWriteCount()).sum();");
            out.println(DOUBLE_INDENT + "long skipped = jobExecution.getStepExecutions().stream().mapToLong(se -> se.getSkipCount()).sum();");
            if (notifyOnCompletion) {
                out.println(DOUBLE_INDENT + "if (jobExecution.getStatus() == BatchStatus.COMPLETED) {");
                out.println(TRIPLE_INDENT + "emailNotificationService.sendJobCompletionEmail(\"" + jobName + "\", RECIPIENTS, processed, skipped, duration.toString());");
                out.println(DOUBLE_INDENT + "}");
            }
            if (notifyOnFailure) {
                out.println(DOUBLE_INDENT + "if (jobExecution.getStatus() == BatchStatus.FAILED) {");
                out.println(TRIPLE_INDENT + "String errorMessage = jobExecution.getAllFailureExceptions().isEmpty() ? \"Unknown error\" : jobExecution.getAllFailureExceptions().get(0).getMessage();");
                out.println(TRIPLE_INDENT + "emailNotificationService.sendJobFailureEmail(\"" + jobName + "\", RECIPIENTS, errorMessage);");
                out.println(DOUBLE_INDENT + "}");
            }
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    // Per-job options of the @BatchJob being generated (set in the method that reads the annotation).
    private boolean continueOnSkipLimit;
    private boolean rowIsolation;
    private String[] uniqueKey = new String[0];
    private java.util.List<String> skipOnFqns = java.util.List.of();
    private java.util.List<String> noSkipOnFqns = java.util.List.of();

    /** Reads skipOn()/noSkipOn(): class arrays can only be read through MirroredTypesException. */
    private java.util.List<String> typeNames(BatchJob annotation, boolean skipOn) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            if (skipOn) annotation.skipOn(); else annotation.noSkipOn();
        } catch (javax.lang.model.type.MirroredTypesException e) {
            for (javax.lang.model.type.TypeMirror mirror : e.getTypeMirrors()) {
                names.add(((TypeElement) ((DeclaredType) mirror).asElement()).getQualifiedName().toString());
            }
        }
        return names;
    }

    /** Fails the build when a uniqueKey() name is not a field of the DTO (or its superclasses). */
    private void validateUniqueKey(String dtoClassFqn, Element element) {
        if (uniqueKey.length == 0) return;
        TypeElement dto = processingEnv.getElementUtils().getTypeElement(dtoClassFqn);
        for (String name : uniqueKey) {
            boolean found = false;
            for (TypeElement t = dto; t != null && !found; ) {
                for (Element e : t.getEnclosedElements()) {
                    if (e.getKind() == javax.lang.model.element.ElementKind.FIELD && e.getSimpleName().contentEquals(name)) {
                        found = true;
                        break;
                    }
                }
                javax.lang.model.type.TypeMirror sup = t.getSuperclass();
                t = sup instanceof DeclaredType d ? (TypeElement) d.asElement() : null;
                if (t != null && t.getQualifiedName().contentEquals("java.lang.Object")) t = null;
            }
            if (!found) {
                throw new IllegalArgumentException("@BatchJob uniqueKey '" + name + "' is not a field of " + dtoClassFqn);
            }
        }
    }

    private @Nullable String getClassFqn(BatchJob annotation, String methodName) {
        try {
            if ("dtoClass".equals(methodName)) annotation.dtoClass(); else annotation.wrapperClass();
            return null;
        } catch (MirroredTypeException mte) {
            return ((TypeElement) ((DeclaredType) mte.getTypeMirror()).asElement()).getQualifiedName().toString();
        }
    }

    private @NotNull String getSimpleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }

    /**
     * Renders a String[] annotation attribute as a comma-separated, quoted
     * Java literal fragment, e.g. {@code ["a","b"]} -> {@code "a", "b"}.
     * Used to embed requiredParameters()/optionalParameters() into
     * generated source as a {@code new String[]{...}} literal.
     */
    private String joinQuoted(String[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(values[i].replace("\"", "\\\"")).append("\"");
        }
        return sb.toString();
    }

    /**
     * Renders a String[] annotation attribute as a full Java array literal,
     * e.g. {@code ["a","b"]} -> {@code new String[] {"a", "b"}}.
     */
    private String arrayLiteral(String[] values) {
        return "new String[] {" + joinQuoted(values) + "}";
    }

    private void logInfo(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message);
    }

    private void logError(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}