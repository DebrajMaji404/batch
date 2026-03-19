package com.eazy.batch.processor;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.enums.FileType;
import lombok.extern.slf4j.Slf4j;
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
 * Annotation processor that auto-generates Spring Batch 6 configuration classes at compile time.
 *
 * Generated imports target:
 *   Spring Batch 6   (spring-boot-starter-batch 4.x)
 *   Spring Framework 7
 *   Jakarta EE
 */
@Slf4j
@SupportedAnnotationTypes("com.eazy.batch.annotation.BatchJob")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions("processor.skip.batchjob")
public class BatchJobAnnotationProcessor extends AbstractProcessor {

    private static final String I1 = "    ";
    private static final String I2 = "        ";
    private static final String I3 = "            ";
    private static final String I4 = "                ";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, @NotNull RoundEnvironment roundEnv) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "PROCESSOR RUNNING");
        String skip = processingEnv.getOptions().get("processor.skip.batchjob");
        if ("true".equalsIgnoreCase(skip)) {
            logInfo("Skipping BatchJob annotation");
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Skipping BatchJob annotation");
            return true;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(BatchJob.class)) {
            if (element instanceof TypeElement typeElement) {
                try {
                    generateBatchComponents(typeElement);
                    logInfo("Successfully generated batch configuration for: " + typeElement.getSimpleName());
                } catch (IOException e) {
                    logError("Failed to generate batch configuration: " + e.getMessage(), element);
                }
            }
        }
        return true;
    }

    private void generateBatchComponents(@NotNull TypeElement element) throws IOException {
        BatchJob annotation    = element.getAnnotation(BatchJob.class);
        String packageName     = processingEnv.getElementUtils().getPackageOf(element).toString();
        String className       = element.getSimpleName().toString();
        String jobName         = annotation.jobName();
        String stepName        = annotation.stepName();
        int    chunkSize       = annotation.chunkSize();
        int    skipLimit       = annotation.skipLimit();
        FileType fileType      = annotation.fileType();
        String sheetName       = annotation.sheetName();
        int    sheetIndex      = annotation.sheetIndex();
        boolean dryRun         = annotation.dryRun();
        boolean enableRetry    = annotation.enableRetry();
        int     retryLimit     = annotation.retryLimit();

        String dtoClassFqn      = getClassFqn(annotation, "dtoClass");
        String wrapperClassFqn  = getClassFqn(annotation, "wrapperClass");
        String dtoClassName     = getSimpleName(dtoClassFqn);
        String wrapperClassName = getSimpleName(wrapperClassFqn);

        validateConfiguration(jobName, stepName, chunkSize, skipLimit);
        generateJobConfiguration(packageName, className, jobName, stepName,
                dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn,
                chunkSize, skipLimit, enableRetry, retryLimit);
        generateReader(packageName, className, stepName, dtoClassName, dtoClassFqn,
                fileType, sheetName, sheetIndex);
        generateProcessor(packageName, className, stepName, dtoClassName, wrapperClassName,
                dtoClassFqn, wrapperClassFqn, dryRun);
        generateWriter(packageName, className, stepName, wrapperClassName, wrapperClassFqn, dryRun);
        generateSkipListener(packageName, className, stepName, dtoClassName, wrapperClassName,
                dtoClassFqn, wrapperClassFqn);
    }

    private void validateConfiguration(String jobName, String stepName, int chunkSize, int skipLimit) {
        if (jobName  == null || jobName.trim().isEmpty())  throw new IllegalArgumentException("jobName cannot be empty");
        if (stepName == null || stepName.trim().isEmpty()) throw new IllegalArgumentException("stepName cannot be empty");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        if (skipLimit  < 0) throw new IllegalArgumentException("skipLimit cannot be negative");
    }

    // =========================================================================
    // 1. Configuration  (Job + Step)
    // =========================================================================
    private void generateJobConfiguration(
            String packageName, String className,
            String jobName,     String stepName,
            String dtoClassName, String wrapperClassName,
            String dtoClassFqn,  String wrapperClassFqn,
            int chunkSize,       int skipLimit,
            boolean enableRetry, int retryLimit) throws IOException {

        String gcn = className + "Configuration";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + gcn);
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            p(w, "package " + packageName + ";");
            p(w, "");
            p(w, "import " + dtoClassFqn + ";");
            p(w, "import " + wrapperClassFqn + ";");
            p(w, "import lombok.RequiredArgsConstructor;");
            p(w, "import lombok.extern.slf4j.Slf4j;");
            // --- Spring Batch 6 package changes ---
            p(w, "import org.springframework.batch.core.job.Job;");
            p(w, "import org.springframework.batch.core.step.Step;");
            p(w, "import org.springframework.batch.core.listener.SkipListener;");
            p(w, "import org.springframework.batch.core.job.builder.JobBuilder;");
            p(w, "import org.springframework.batch.core.repository.JobRepository;");
            // ChunkOrientedStepBuilder replaces StepBuilder.chunk() in Spring Batch 6
            p(w, "import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;");
            // SkipPolicy-based skip (replaces fluent .skip()/.skipLimit())
            p(w, "import org.springframework.batch.core.step.skip.LimitCheckingItemSkipPolicy;");
            // ItemReader/ItemProcessor/ItemWriter package unchanged
            p(w, "import org.springframework.batch.infrastructure.item.ItemProcessor;");
            p(w, "import org.springframework.batch.infrastructure.item.ItemReader;");
            p(w, "import org.springframework.batch.infrastructure.item.ItemWriter;");
            p(w, "import org.springframework.context.annotation.Bean;");
            p(w, "import org.springframework.context.annotation.Configuration;");
            p(w, "import org.springframework.transaction.PlatformTransactionManager;");
            p(w, "import java.util.Map;");
            p(w, "");
            p(w, "/** Auto-generated Spring Batch 6 config for " + className + " - DO NOT MODIFY */");
            p(w, "@Slf4j");
            p(w, "@Configuration");
            p(w, "@RequiredArgsConstructor");
            p(w, "public class " + gcn + " {");
            p(w, "");
            p(w, I1 + "private final JobRepository jobRepository;");
            p(w, I1 + "private final PlatformTransactionManager transactionManager;");
            p(w, "");

            // Job bean
            p(w, I1 + "@Bean");
            p(w, I1 + "public Job " + jobName + "(Step " + stepName + ") {");
            p(w, I2 +     "log.info(\"Initializing job: {}\", \"" + jobName + "\");");
            p(w, I2 +     "return new JobBuilder(\"" + jobName + "\", jobRepository)");
            p(w, I4 +         ".start(" + stepName + ")");
            p(w, I4 +         ".build();");
            p(w, I1 + "}");
            p(w, "");

            // Step bean — Spring Batch 6: ChunkOrientedStepBuilder + SkipPolicy
            p(w, I1 + "@Bean");
            p(w, I1 + "public Step " + stepName + "(");
            p(w, I3 +     "ItemReader<" + dtoClassName + "> reader,");
            p(w, I3 +     "ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> processor,");
            p(w, I3 +     "ItemWriter<" + wrapperClassName + "> writer,");
            p(w, I3 +     "SkipListener<" + dtoClassName + ", " + wrapperClassName + "> skipListener) {");
            p(w, I2 +     "log.info(\"Initializing step: {}\", \"" + stepName + "\");");
            p(w, I2 +     "var skipPolicy = new LimitCheckingItemSkipPolicy(" + skipLimit + ", Map.of(Exception.class, Boolean.TRUE));");
            p(w, I2 +     "return new ChunkOrientedStepBuilder<" + dtoClassName + ", " + wrapperClassName + ">(");
            p(w, I4 +             "\"" + stepName + "\", jobRepository, transactionManager, " + chunkSize + ")");
            p(w, I4 +         ".reader(reader)");
            p(w, I4 +         ".processor(processor)");
            p(w, I4 +         ".writer(writer)");
            p(w, I4 +         ".faultTolerant()");
            p(w, I4 +         ".skipPolicy(skipPolicy)");
            p(w, I4 +         ".listener(skipListener)");
            if (enableRetry) {
                p(w, I4 +     ".retryPolicy(");
                p(w, I4 +     "    org.springframework.batch.core.step.skip.AlwaysSkipItemSkipPolicy.class.isInstance(skipPolicy)");
                p(w, I4 +     "        ? null");
                p(w, I4 +     "        : new org.springframework.retry.policy.SimpleRetryPolicy(" + retryLimit + "))");
            }
            p(w, I4 +         ".build();");
            p(w, I1 + "}");
            p(w, "}");
        }
    }

    // =========================================================================
    // 2. Reader
    // =========================================================================
    private void generateReader(
            String packageName, String className,
            String stepName,    String dtoClassName, String dtoClassFqn,
            FileType fileType,  String sheetName,    int sheetIndex) throws IOException {

        String gcn = className + "Reader";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + gcn);
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            p(w, "package " + packageName + ";");
            p(w, "");
            if (fileType == FileType.CSV) {
                p(w, "import com.eazy.batch.reader.CSVItemReader;");
            } else {
                p(w, "import com.eazy.batch.reader.ExcelItemReaderWithHeaderValidation;");
            }
            p(w, "import " + dtoClassFqn + ";");
            p(w, "import lombok.extern.slf4j.Slf4j;");
            p(w, "import org.springframework.batch.core.configuration.annotation.StepScope;");
            // ItemReader package unchanged in Spring Batch 6
            p(w, "import org.springframework.batch.infrastructure.item.ItemReader;");
            p(w, "import org.springframework.beans.factory.annotation.Value;");
            p(w, "import org.springframework.context.annotation.Bean;");
            p(w, "import org.springframework.context.annotation.Configuration;");
            p(w, "import org.springframework.core.io.FileSystemResource;");
            p(w, "");
            p(w, "@Slf4j");
            p(w, "@Configuration");
            p(w, "public class " + gcn + " {");
            p(w, "");
            p(w, I1 + "@Bean");
            p(w, I1 + "@StepScope");
            p(w, I1 + "public ItemReader<" + dtoClassName + "> " + stepName + "ItemReader(");
            p(w, I3 +     "@Value(\"#{jobParameters['filePath']}\") String filePath) {");
            p(w, I2 +     "log.debug(\"Initializing " + fileType + " reader for: {}\", filePath);");
            if (fileType == FileType.CSV) {
                p(w, I2 + "return new CSVItemReader<>(new FileSystemResource(filePath), " + dtoClassName + ".class);");
            } else {
                String sheet = sheetName.isEmpty() ? "null" : "\"" + sheetName + "\"";
                p(w, I2 + "return new ExcelItemReaderWithHeaderValidation<>(");
                p(w, I4 +     "new FileSystemResource(filePath), " + dtoClassName + ".class, " + sheetIndex + ", " + sheet + ");");
            }
            p(w, I1 + "}");
            p(w, "}");
        }
    }

    // =========================================================================
    // 3. Processor
    // =========================================================================
    private void generateProcessor(
            String packageName, String className,
            String stepName,    String dtoClassName, String wrapperClassName,
            String dtoClassFqn, String wrapperClassFqn,
            boolean dryRun) throws IOException {

        String gcn = className + "Processor";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + gcn);
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            p(w, "package " + packageName + ";");
            p(w, "");
            p(w, "import " + dtoClassFqn + ";");
            p(w, "import " + wrapperClassFqn + ";");
            p(w, "import jakarta.validation.ConstraintViolation;");
            p(w, "import jakarta.validation.Validator;");
            p(w, "import lombok.RequiredArgsConstructor;");
            p(w, "import lombok.extern.slf4j.Slf4j;");
            // ItemProcessor package unchanged in Spring Batch 6
            p(w, "import org.springframework.batch.infrastructure.item.ItemProcessor;");
            p(w, "import org.springframework.context.annotation.Bean;");
            p(w, "import org.springframework.context.annotation.Configuration;");
            p(w, "import java.util.List;");
            p(w, "import java.util.Set;");
            p(w, "import java.util.stream.Collectors;");
            p(w, "");
            p(w, "@Slf4j");
            p(w, "@Configuration");
            p(w, "@RequiredArgsConstructor");
            p(w, "public class " + gcn + " {");
            p(w, "");
            p(w, I1 + "private final " + className + " delegate;");
            p(w, I1 + "private final Validator validator;");
            p(w, "");
            p(w, I1 + "@Bean");
            p(w, I1 + "public ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> " + stepName + "ItemProcessor() {");
            p(w, I2 +     "return dto -> {");
            p(w, I3 +         "if (dto == null) { log.warn(\"Received null DTO\"); return null; }");
            p(w, I3 +         "dto = delegate.preProcess(dto);");
            p(w, I3 +         "if (!delegate.shouldProcess(dto)) { log.debug(\"Item filtered: {}\", delegate.getIdentifier(dto)); return null; }");
            p(w, I3 +         "Set<ConstraintViolation<" + dtoClassName + ">> violations = validator.validate(dto);");
            p(w, I3 +         "if (!violations.isEmpty()) {");
            p(w, I4 +             "String errors = violations.stream().map(v -> v.getPropertyPath() + \": \" + v.getMessage()).collect(Collectors.joining(\", \"));");
            p(w, I4 +             "throw new RuntimeException(\"Validation failed: \" + errors);");
            p(w, I3 +         "}");
            p(w, I3 +         "List<String> customErrors = delegate.customValidate(dto);");
            p(w, I3 +         "if (customErrors != null && !customErrors.isEmpty()) {");
            p(w, I4 +             "throw new RuntimeException(\"Custom validation failed: \" + String.join(\", \", customErrors));");
            p(w, I3 +         "}");
            if (dryRun) {
                p(w, I3 + "log.debug(\"[DRY RUN] Would process: {}\", delegate.getIdentifier(dto)); return null;");
            } else {
                p(w, I3 +     "var result = delegate.process(dto);");
                p(w, I3 +     "return result != null ? delegate.postProcess(result) : null;");
            }
            p(w, I2 +     "};");
            p(w, I1 + "}");
            p(w, "}");
        }
    }

    // =========================================================================
    // 4. Writer
    // =========================================================================
    private void generateWriter(
            String packageName, String className,
            String stepName,    String wrapperClassName, String wrapperClassFqn,
            boolean dryRun) throws IOException {

        String gcn = className + "Writer";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + gcn);
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            p(w, "package " + packageName + ";");
            p(w, "");
            p(w, "import " + wrapperClassFqn + ";");
            p(w, "import lombok.RequiredArgsConstructor;");
            p(w, "import lombok.extern.slf4j.Slf4j;");
            // Chunk + ItemWriter package unchanged in Spring Batch 6
            p(w, "import org.springframework.batch.infrastructure.item.Chunk;");
            p(w, "import org.springframework.batch.infrastructure.item.ItemWriter;");
            p(w, "import org.springframework.context.annotation.Bean;");
            p(w, "import org.springframework.context.annotation.Configuration;");
            p(w, "import java.util.List;");
            p(w, "import java.util.Objects;");
            p(w, "import java.util.stream.Collectors;");
            p(w, "");
            p(w, "@Slf4j");
            p(w, "@Configuration");
            p(w, "@RequiredArgsConstructor");
            p(w, "public class " + gcn + " {");
            p(w, "");
            p(w, I1 + "private final " + className + " delegate;");
            p(w, "");
            p(w, I1 + "@Bean");
            p(w, I1 + "public ItemWriter<" + wrapperClassName + "> " + stepName + "ItemWriter() {");
            p(w, I2 +     "return chunk -> {");
            p(w, I3 +         "List<" + wrapperClassName + "> validItems = chunk.getItems().stream().filter(Objects::nonNull).collect(Collectors.toList());");
            p(w, I3 +         "if (validItems.isEmpty()) { log.warn(\"No valid items to write\"); return; }");
            if (dryRun) {
                p(w, I3 + "log.info(\"[DRY RUN] Would write {} items\", validItems.size());");
            } else {
                p(w, I3 +     "log.debug(\"Writing {} items\", validItems.size()); delegate.save(validItems);");
            }
            p(w, I2 +     "};");
            p(w, I1 + "}");
            p(w, "}");
        }
    }

    // =========================================================================
    // 5. SkipListener
    // =========================================================================
    private void generateSkipListener(
            String packageName, String className,
            String stepName,    String dtoClassName, String wrapperClassName,
            String dtoClassFqn, String wrapperClassFqn) throws IOException {

        String gcn = className + "SkipListener";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + gcn);
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            p(w, "package " + packageName + ";");
            p(w, "");
            p(w, "import " + dtoClassFqn + ";");
            p(w, "import " + wrapperClassFqn + ";");
            p(w, "import lombok.RequiredArgsConstructor;");
            p(w, "import lombok.extern.slf4j.Slf4j;");
            // Spring Batch 6: SkipListener moved to org.springframework.batch.core.listener
            p(w, "import org.springframework.batch.core.listener.SkipListener;");
            // Spring Framework 7: use jakarta.annotation.Nonnull
            p(w, "import jakarta.annotation.Nonnull;");
            p(w, "import org.springframework.stereotype.Component;");
            p(w, "import static com.eazy.batch.utility.BatchUtility.addSkippedItem;");
            p(w, "");
            p(w, "@Slf4j");
            p(w, "@Component");
            p(w, "@RequiredArgsConstructor");
            p(w, "public class " + gcn + " implements SkipListener<" + dtoClassName + ", " + wrapperClassName + "> {");
            p(w, "");
            p(w, I1 + "private final " + className + " delegate;");
            p(w, "");
            p(w, I1 + "@Override");
            p(w, I1 + "public void onSkipInRead(@Nonnull Throwable throwable) {");
            p(w, I2 +     "addSkippedItem(null, \"READ\", throwable.getMessage());");
            p(w, I2 +     "log.error(\"[SKIP-READ] {}\", throwable.getMessage(), throwable);");
            p(w, I1 + "}");
            p(w, "");
            p(w, I1 + "@Override");
            p(w, I1 + "public void onSkipInProcess(@Nonnull " + dtoClassName + " dto, @Nonnull Throwable throwable) {");
            p(w, I2 +     "addSkippedItem(dto, \"PROCESS\", throwable.getMessage());");
            p(w, I2 +     "log.error(\"[SKIP-PROCESS] {}: {}\", delegate.getIdentifier(dto), throwable.getMessage(), throwable);");
            p(w, I1 + "}");
            p(w, "");
            p(w, I1 + "@Override");
            p(w, I1 + "public void onSkipInWrite(@Nonnull " + wrapperClassName + " wrapper, @Nonnull Throwable throwable) {");
            p(w, I2 +     "addSkippedItem(wrapper, \"WRITE\", throwable.getMessage());");
            p(w, I2 +     "log.error(\"[SKIP-WRITE] {}: {}\", delegate.getIdentifier(wrapper), throwable.getMessage(), throwable);");
            p(w, I1 + "}");
            p(w, "}");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private void p(PrintWriter w, String line) {
        w.println(line);
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
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private void logInfo(String msg)                    { processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,  msg); }
    private void logError(String msg, Element element)  { processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, element); }
}