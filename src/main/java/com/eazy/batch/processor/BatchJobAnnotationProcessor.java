package com.eazy.batch.processor;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.enums.FileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
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
public class BatchJobAnnotationProcessor extends AbstractProcessor {

    private static final String INDENT = "    ";
    private static final String DOUBLE_INDENT = INDENT + INDENT;
    private static final String TRIPLE_INDENT = INDENT + INDENT + INDENT;
    private static final String QUAD_INDENT = INDENT + INDENT + INDENT + INDENT;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, @NotNull RoundEnvironment roundEnv) {
        logInfo("Processing started");
        for (Element element : roundEnv.getElementsAnnotatedWith(BatchJob.class)) {
            if (element instanceof TypeElement typeElement) {
                try {
                    generateBatchComponents(typeElement);
                    logInfo("✅ Successfully generated batch configuration for: " + typeElement.getSimpleName());
                } catch (IOException e) {
                    logError("❌ Failed to generate batch configuration: " + e.getMessage(), element);
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
        String sheetName = annotation.sheetName();
        int sheetIndex = annotation.sheetIndex();
        boolean dryRun = annotation.dryRun();
        boolean enableRetry = annotation.enableRetry();
        int retryLimit = annotation.retryLimit();
        String[] retryableExceptions = annotation.retryableExceptions();

        // Extract class names
        String dtoClassFqn = getClassFqn(annotation, "dtoClass");
        String wrapperClassFqn = getClassFqn(annotation, "wrapperClass");
        String dtoClassName = getSimpleName(dtoClassFqn);
        String wrapperClassName = getSimpleName(wrapperClassFqn);

        // Validate and generate
        validateConfiguration(jobName, stepName, dtoClassName, wrapperClassName, chunkSize, skipLimit);
        generateJobConfiguration(packageName, className, jobName, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn, chunkSize, skipLimit, enableRetry, retryLimit, retryableExceptions);
        generateReader(packageName, className, stepName, dtoClassName, dtoClassFqn, fileType, sheetName, sheetIndex);
        generateProcessor(packageName, className, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn, dryRun);
        generateWriter(packageName, className, stepName, wrapperClassName, wrapperClassFqn, dryRun);
        generateSkipListener(packageName, className, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn);
    }

    private void validateConfiguration(String jobName, String stepName, String dtoClassName, String wrapperClassName, int chunkSize, int skipLimit) {
        if (jobName == null || jobName.trim().isEmpty()) throw new IllegalArgumentException("jobName cannot be empty");
        if (stepName == null || stepName.trim().isEmpty()) throw new IllegalArgumentException("stepName cannot be empty");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        if (skipLimit < 0) throw new IllegalArgumentException("skipLimit cannot be negative");
    }

    private void generateJobConfiguration(String packageName, String className, String jobName, String stepName, String dtoClassName, String wrapperClassName, String dtoClassFqn, String wrapperClassFqn, int chunkSize, int skipLimit, boolean enableRetry, int retryLimit, String[] retryableExceptions) throws IOException {
        String generatedClassName = className + "Configuration";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + dtoClassFqn + ";");
            out.println("import " + wrapperClassFqn + ";");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.job.Job;");
            out.println("import org.springframework.batch.core.listener.SkipListener;");
            out.println("import org.springframework.batch.core.step.Step;");
            out.println("import org.springframework.batch.core.job.builder.JobBuilder;");
            out.println("import com.eazy.batch.listener.JobCompletionListener;");
            out.println("import org.springframework.batch.core.repository.JobRepository;");
            out.println("import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;");
            out.println("import org.springframework.batch.core.step.skip.LimitCheckingExceptionHierarchySkipPolicy;");
            out.println("import java.util.Set;");
            out.println("import org.springframework.batch.infrastructure.item.ItemProcessor;");
            out.println("import org.springframework.batch.infrastructure.item.ItemReader;");
            out.println("import org.springframework.batch.infrastructure.item.ItemWriter;");
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
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public Job " + jobName + "(Step " + stepName + ") {");
            out.println(DOUBLE_INDENT + "log.info(\"Initializing batch job: {}\", \"" + jobName + "\");");
            out.println(DOUBLE_INDENT + "return new JobBuilder(\"" + jobName + "\", jobRepository)");
            out.println(QUAD_INDENT + ".listener(jobCompletionListener)");
            out.println(QUAD_INDENT + ".start(" + stepName + ")");
            out.println(QUAD_INDENT + ".build();");
            out.println(INDENT + "}");
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public Step " + stepName + "(");
            out.println(TRIPLE_INDENT + "ItemReader<" + dtoClassName + "> reader,");
            out.println(TRIPLE_INDENT + "ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> processor,");
            out.println(TRIPLE_INDENT + "ItemWriter<" + wrapperClassName + "> writer,");
            out.println(TRIPLE_INDENT + "SkipListener<" + dtoClassName + ", " + wrapperClassName + "> skipListener) {");
            out.println(DOUBLE_INDENT + "log.info(\"Initializing batch step: {}\", \"" + stepName + "\");");
            out.println(DOUBLE_INDENT + "var skipPolicy = new LimitCheckingExceptionHierarchySkipPolicy(");
            out.println(TRIPLE_INDENT + "Set.of(Exception.class), " + skipLimit + ");");
            out.println(DOUBLE_INDENT + "return new ChunkOrientedStepBuilder<" + dtoClassName + ", " + wrapperClassName + ">(\"" + stepName + "\", jobRepository, " + chunkSize + ")");
            out.println(TRIPLE_INDENT + ".transactionManager(transactionManager)");
            out.println(TRIPLE_INDENT + ".reader(reader)");
            out.println(TRIPLE_INDENT + ".processor(processor)");
            out.println(TRIPLE_INDENT + ".writer(writer)");
            out.println(TRIPLE_INDENT + ".faultTolerant()");
            out.println(TRIPLE_INDENT + ".skipPolicy(skipPolicy)");
            out.println(TRIPLE_INDENT + ".listener(skipListener)");
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

    private void generateProcessor(String packageName, String className, String stepName, String dtoClassName, String wrapperClassName, String dtoClassFqn, String wrapperClassFqn, boolean dryRun) throws IOException {
        String generatedClassName = className + "Processor";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + dtoClassFqn + ";");
            out.println("import " + wrapperClassFqn + ";");
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
            out.println();
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> " + stepName + "ItemProcessor() {");
            out.println(DOUBLE_INDENT + "return dto -> {");
            out.println(TRIPLE_INDENT + "if (dto == null) { log.warn(\"Received null DTO\"); return null; }");
            out.println(TRIPLE_INDENT + "dto = delegate.preProcess(dto);");
            out.println(TRIPLE_INDENT + "if (!delegate.shouldProcess(dto)) { log.debug(\"Item filtered: {}\", delegate.getIdentifier(dto)); return null; }");
            out.println(TRIPLE_INDENT + "Set<ConstraintViolation<" + dtoClassName + ">> violations = validator.validate(dto);");
            out.println(TRIPLE_INDENT + "if (!violations.isEmpty()) {");
            out.println(QUAD_INDENT + "String errors = violations.stream().map(v -> v.getPropertyPath() + \": \" + v.getMessage()).collect(Collectors.joining(\", \"));");
            out.println(QUAD_INDENT + "throw new RuntimeException(\"Validation failed: \" + errors);");
            out.println(TRIPLE_INDENT + "}");
            out.println(TRIPLE_INDENT + "List<String> customErrors = delegate.customValidate(dto);");
            out.println(TRIPLE_INDENT + "if (customErrors != null && !customErrors.isEmpty()) {");
            out.println(QUAD_INDENT + "throw new RuntimeException(\"Custom validation failed: \" + String.join(\", \", customErrors));");
            out.println(TRIPLE_INDENT + "}");
            if (dryRun) {
                out.println(TRIPLE_INDENT + "log.debug(\"[DRY RUN] Would process: {}\", delegate.getIdentifier(dto)); return null;");
            } else {
                out.println(TRIPLE_INDENT + "var result = delegate.process(dto);");
                out.println(TRIPLE_INDENT + "return result != null ? delegate.postProcess(result) : null;");
            }
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
                out.println(TRIPLE_INDENT + "log.debug(\"Writing {} items\", validItems.size()); delegate.save(validItems);");
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
            out.println(DOUBLE_INDENT + "addSkippedItem(null, \"READ\", throwable.getMessage());");
            out.println(DOUBLE_INDENT + "log.error(\"[SKIP-READ] {}\", throwable.getMessage(), throwable);");
            out.println(INDENT + "}");
            out.println();
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void onSkipInProcess(@NonNull " + dtoClassName + " dto, @NonNull Throwable throwable) {");
            out.println(DOUBLE_INDENT + "addSkippedItem(dto, \"PROCESS\", throwable.getMessage());");
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

    private void logInfo(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message);
    }

    private void logError(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}