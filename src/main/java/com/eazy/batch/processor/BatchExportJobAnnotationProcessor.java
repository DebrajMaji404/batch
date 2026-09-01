package com.eazy.batch.processor;

import com.eazy.batch.annotation.BatchExportJob;
import com.eazy.batch.enums.ExportFileType;
import com.eazy.batch.enums.StorageType;
import org.jetbrains.annotations.NotNull;

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
 * Annotation processor that auto-generates Spring Batch export configuration at compile time.
 *
 * <p>For each class annotated with {@code @BatchExportJob}, generates 4 classes:</p>
 * <ol>
 *   <li><b>ExportConfiguration</b> — Job + Step beans</li>
 *   <li><b>ExportReader</b>        — JpaCursorItemReader using your JPQL query</li>
 *   <li><b>ExportWriter</b>        — Excel/CSV writer with column definitions</li>
 *   <li><b>ExportStepListener</b>  — finalizes file + fires onSaveComplete(url)</li>
 * </ol>
 *
 * <p>Storage is injected by qualifier:</p>
 * <ul>
 *   <li>{@code LOCAL}  → {@code @Qualifier("localExportStorage")}  (built-in)</li>
 *   <li>{@code CUSTOM} → {@code @Qualifier("customExportStorage")} (you provide)</li>
 * </ul>
 */
@SupportedAnnotationTypes("com.eazy.batch.annotation.BatchExportJob")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class BatchExportJobAnnotationProcessor extends AbstractProcessor {

    private static final String I   = "    ";
    private static final String II  = "        ";
    private static final String III = "            ";
    private static final String IV  = "                ";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, @NotNull RoundEnvironment roundEnv) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "[BatchExportJob] Processor started");
        for (Element element : roundEnv.getElementsAnnotatedWith(BatchExportJob.class)) {
            if (element instanceof TypeElement typeElement) {
                try {
                    generateExportComponents(typeElement);
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "✅ Generated export configuration for: " + typeElement.getSimpleName());
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "❌ Failed to generate export configuration: " + e.getMessage(), element);
                } catch (IllegalArgumentException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "❌ Invalid @BatchExportJob configuration: " + e.getMessage(), element);
                }
            }
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────
    // Main entry
    // ─────────────────────────────────────────────────────────────────

    private void generateExportComponents(TypeElement element) throws IOException {
        BatchExportJob ann      = element.getAnnotation(BatchExportJob.class);
        String pkg              = processingEnv.getElementUtils().getPackageOf(element).toString();
        String className        = element.getSimpleName().toString();

        String entityFqn        = getClassFqn(ann);
        String entityClass      = getSimpleName(entityFqn);
        String sheetName        = ann.sheetName().isEmpty() ? ann.fileName() : ann.sheetName();

        validateExportConfiguration(ann.jobName(), ann.stepName(), ann.chunkSize(), ann.skipLimit());

        generateJobConfig(pkg, className, ann.jobName(), ann.stepName(),
                ann.chunkSize(), ann.skipLimit(), entityClass, entityFqn);

        generateReader(pkg, className, ann.stepName(), entityClass, entityFqn);

        generateWriter(pkg, className, ann.stepName(), entityClass, entityFqn,
                ann.storageType(), ann.fileType(), ann.fileName(), sheetName, ann.localDirectory(), ann.dryRun());

        generateStepListener(pkg, className, ann.stepName(), entityClass, entityFqn, ann.fileType(), ann.dryRun());

        // FIXED: export jobs previously had .skip(Exception.class) +
        // .skipLimit(...) with no SkipListener at all - skipped entities
        // were counted internally by Spring Batch but never surfaced via
        // BatchUtility.getSkippedItems() the way @BatchJob skips are.
        generateExportSkipListener(pkg, className, ann.jobName(), entityClass, entityFqn);
    }

    private void validateExportConfiguration(String jobName, String stepName, int chunkSize, int skipLimit) {
        if (jobName == null || jobName.trim().isEmpty()) throw new IllegalArgumentException("jobName cannot be empty");
        if (stepName == null || stepName.trim().isEmpty()) throw new IllegalArgumentException("stepName cannot be empty");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        // FIXED: like the @BatchJob skip policy, a skipLimit of 0 is rejected
        // at runtime by Spring Batch's skip machinery ("skipLimit must be
        // greater than zero") - catch it here instead at compile time.
        if (skipLimit <= 0) throw new IllegalArgumentException("skipLimit must be greater than zero");
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. Job + Step Configuration
    // ─────────────────────────────────────────────────────────────────

    private void generateJobConfig(String pkg, String className, String jobName, String stepName,
                                   int chunkSize, int skipLimit,
                                   String entityClass, String entityFqn) throws IOException {
        String gen = className + "ExportConfiguration";
        try (PrintWriter out = new PrintWriter(
                processingEnv.getFiler().createSourceFile(pkg + "." + gen).openWriter())) {

            out.println("package " + pkg + ";");
            out.println();
            out.println("import " + entityFqn + ";");
            out.println("import com.eazy.batch.listener.JobCompletionListener;");
            out.println("import com.eazy.batch.listener.BatchProgressChunkListener;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.job.Job;");
            out.println("import org.springframework.batch.core.listener.SkipListener;");
            out.println("import org.springframework.batch.core.step.Step;");
            out.println("import org.springframework.batch.core.job.builder.JobBuilder;");
            out.println("import org.springframework.batch.core.step.builder.StepBuilder;");
            out.println("import org.springframework.batch.core.repository.JobRepository;");
            out.println("import org.springframework.batch.infrastructure.item.ItemReader;");
            out.println("import org.springframework.batch.infrastructure.item.ItemWriter;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import org.springframework.transaction.PlatformTransactionManager;");
            out.println();
            out.println("/** Auto-generated export job configuration for " + className + " — DO NOT MODIFY */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + gen + " {");
            out.println();
            out.println(I + "private final JobRepository jobRepository;");
            out.println(I + "private final PlatformTransactionManager transactionManager;");
            out.println(I + "private final JobCompletionListener jobCompletionListener;");
            out.println();
            out.println(I + "@Bean");
            out.println(I + "public Job " + jobName + "(Step " + stepName + ") {");
            out.println(II + "log.info(\"Initializing export job: {}\", \"" + jobName + "\");");
            out.println(II + "return new JobBuilder(\"" + jobName + "\", jobRepository)");
            out.println(IV + ".listener(jobCompletionListener)");
            out.println(IV + ".start(" + stepName + ")");
            out.println(IV + ".build();");
            out.println(I + "}");
            out.println();
            out.println(I + "@Bean");
            out.println(I + "public Step " + stepName + "(");
            out.println(III + "ItemReader<" + entityClass + "> reader,");
            out.println(III + "ItemWriter<" + entityClass + "> writer,");
            out.println(III + "SkipListener<" + entityClass + ", " + entityClass + "> skipListener,");
            out.println(III + "BatchProgressChunkListener progressChunkListener,");
            out.println(III + className + "ExportStepListener stepListener) {");
            out.println(II + "return new StepBuilder(\"" + stepName + "\", jobRepository)");
            out.println(IV + ".<" + entityClass + ", " + entityClass + ">chunk(" + chunkSize + ", transactionManager)");
            out.println(IV + ".reader(reader)");
            out.println(IV + ".writer(writer)");
            out.println(IV + ".faultTolerant()");
            out.println(IV + ".skipLimit(" + skipLimit + ")");
            out.println(IV + ".skip(Exception.class)");
            out.println(IV + ".listener(skipListener)");
            // NEW: live progress push over WebSocket after every chunk.
            out.println(IV + ".listener(progressChunkListener)");
            out.println(IV + ".listener(stepListener)");
            out.println(IV + ".build();");
            out.println(I + "}");
            out.println("}");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. JPA Reader
    // ─────────────────────────────────────────────────────────────────

    private void generateReader(String pkg, String className, String stepName,
                                String entityClass, String entityFqn) throws IOException {
        String gen = className + "ExportReader";
        try (PrintWriter out = new PrintWriter(
                processingEnv.getFiler().createSourceFile(pkg + "." + gen).openWriter())) {

            out.println("package " + pkg + ";");
            out.println();
            out.println("import " + entityFqn + ";");
            out.println("import jakarta.persistence.EntityManagerFactory;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.infrastructure.item.database.JpaCursorItemReader;");
            out.println("import org.springframework.batch.infrastructure.item.database.builder.JpaCursorItemReaderBuilder;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println();
            out.println("/** Auto-generated JPA reader for " + className + " — DO NOT MODIFY */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + gen + " {");
            out.println();
            out.println(I + "private final EntityManagerFactory entityManagerFactory;");
            out.println(I + "private final " + className + " delegate;");
            out.println();
            out.println(I + "@Bean");
            out.println(I + "public JpaCursorItemReader<" + entityClass + "> " + stepName + "ExportItemReader() {");
            out.println(II + "String jpql = delegate.getJpqlQuery();");
            out.println(II + "log.info(\"[" + className + "] Export JPQL: {}\", jpql);");
            out.println(II + "return new JpaCursorItemReaderBuilder<" + entityClass + ">()");
            out.println(IV + ".name(\"" + stepName + "ExportReader\")");
            out.println(IV + ".entityManagerFactory(entityManagerFactory)");
            out.println(IV + ".queryString(jpql)");
            out.println(IV + ".build();");
            out.println(I + "}");
            out.println("}");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. Writer — injects the correct storage bean by qualifier
    //    LOCAL  → "localExportStorage"  (built-in LocalExportStorageService)
    //    CUSTOM → "customExportStorage" (user-provided bean)
    // ─────────────────────────────────────────────────────────────────

    private void generateWriter(String pkg, String className, String stepName,
                                String entityClass, String entityFqn,
                                StorageType storage, ExportFileType format,
                                String fileName, String sheetName, String localDirectory,
                                boolean dryRun) throws IOException {
        String gen         = className + "ExportWriter";
        String writerClass = format == ExportFileType.CSV ? "CsvExportItemWriter" : "ExcelExportItemWriter";
        String writerType  = writerClass + "<" + entityClass + ">";
        String qualifier   = storage == StorageType.LOCAL ? "localExportStorage" : "customExportStorage";
        String ext         = format == ExportFileType.CSV ? ".csv" : ".xlsx";
        boolean hasLocalDirOverride = storage == StorageType.LOCAL && localDirectory != null && !localDirectory.isBlank();

        try (PrintWriter out = new PrintWriter(
                processingEnv.getFiler().createSourceFile(pkg + "." + gen).openWriter())) {

            out.println("package " + pkg + ";");
            out.println();
            if (dryRun) {
                // NEW: dry-run mode - no file is ever built or uploaded.
                // Useful for verifying a JPQL query and column mappings
                // against real data without producing output.
                out.println("import org.springframework.batch.infrastructure.item.ItemWriter;");
                out.println("import lombok.extern.slf4j.Slf4j;");
                out.println("import org.springframework.context.annotation.Bean;");
                out.println("import org.springframework.context.annotation.Configuration;");
                out.println();
                out.println("/** Auto-generated DRY-RUN export writer for " + className + " — DO NOT MODIFY */");
                out.println("@Slf4j");
                out.println("@Configuration");
                out.println("public class " + gen + " {");
                out.println();
                out.println(I + "@Bean");
                out.println(I + "public ItemWriter<" + entityClass + "> " + stepName + "ExportItemWriter() {");
                out.println(II + "return chunk -> log.info(\"[DRY RUN][" + className + "] Would write {} rows (no file will be produced)\", chunk.size());");
                out.println(I + "}");
                out.println("}");
                return;
            }
            out.println("import " + entityFqn + ";");
            out.println("import com.eazy.batch.writer." + writerClass + ";");
            out.println("import com.eazy.batch.service.ExportStorageService;");
            if (hasLocalDirOverride) {
                out.println("import com.eazy.batch.service.LocalExportStorageService;");
            }
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.configuration.annotation.StepScope;");
            out.println("import org.springframework.beans.factory.annotation.Qualifier;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import java.time.LocalDateTime;");
            out.println("import java.time.format.DateTimeFormatter;");
            out.println();
            out.println("/**");
            out.println(" * Auto-generated export writer for " + className + " — DO NOT MODIFY");
            out.println(" * Storage: " + storage + " → bean qualifier: \"" + qualifier + "\"");
            out.println(" */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + gen + " {");
            out.println();
            out.println(I + "private final " + className + " delegate;");
            out.println();
            out.println(I + "@Bean");
            // FIXED: this bean was previously a plain singleton, constructed
            // once at application startup. Both CsvExportItemWriter and
            // ExcelExportItemWriter buffer the whole file in an instance
            // field set in their constructor, so as a singleton: (1) the
            // filename timestamp was frozen at startup forever, and (2)
            // every job run after the first re-used the same never-cleared
            // buffer, silently duplicating/corrupting the output on every
            // subsequent execution. @StepScope gives each StepExecution a
            // fresh instance.
            out.println(I + "@StepScope");
            if (hasLocalDirOverride) {
                // Per-job localDirectory() override: bypass the shared
                // "localExportStorage" bean and build a dedicated instance,
                // since localDirectory() was previously declared but never
                // actually read anywhere.
                out.println(I + "public " + writerType + " " + stepName + "ExportItemWriter() {");
                out.println(II + "ExportStorageService storageService = new LocalExportStorageService(\"" + localDirectory.replace("\"", "\\\"") + "\");");
            } else {
                out.println(I + "public " + writerType + " " + stepName + "ExportItemWriter(");
                out.println(III + "@Qualifier(\"" + qualifier + "\") ExportStorageService storageService) {");
            }
            out.println();
            out.println(II + "String timestamp = LocalDateTime.now()");
            out.println(III + ".format(DateTimeFormatter.ofPattern(\"yyyyMMdd_HHmmss\"));");
            out.println(II + "String fullFileName = \"" + fileName + "_\" + timestamp + \"" + ext + "\";");
            out.println(II + "log.info(\"[" + className + "] Export file: {}\", fullFileName);");
            out.println();
            if (format == ExportFileType.CSV) {
                out.println(II + "return new CsvExportItemWriter<>(");
                out.println(IV + "delegate.getColumns(),");
                out.println(IV + "fullFileName,");
                out.println(IV + "storageService,");
                out.println(IV + "delegate::onSaveComplete,");
                out.println(IV + "delegate::onSaveFailure);");
            } else {
                out.println(II + "return new ExcelExportItemWriter<>(");
                out.println(IV + "delegate.getColumns(),");
                out.println(IV + "fullFileName,");
                out.println(IV + "\"" + sheetName + "\",");
                out.println(IV + "storageService,");
                out.println(IV + "delegate::onSaveComplete,");
                out.println(IV + "delegate::onSaveFailure);");
            }
            out.println(I + "}");
            out.println("}");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. StepListener — calls finalizeAndSave() after all chunks
    // ─────────────────────────────────────────────────────────────────

    private void generateStepListener(String pkg, String className, String stepName,
                                      String entityClass, String entityFqn,
                                      ExportFileType format, boolean dryRun) throws IOException {
        String gen         = className + "ExportStepListener";
        String writerClass = format == ExportFileType.CSV ? "CsvExportItemWriter" : "ExcelExportItemWriter";
        String writerType  = writerClass + "<" + entityClass + ">";

        try (PrintWriter out = new PrintWriter(
                processingEnv.getFiler().createSourceFile(pkg + "." + gen).openWriter())) {

            out.println("package " + pkg + ";");
            out.println();
            out.println("import " + entityFqn + ";");
            if (!dryRun) {
                out.println("import com.eazy.batch.writer." + writerClass + ";");
            }
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.ExitStatus;");
            out.println("import org.springframework.batch.core.step.StepExecution;");
            out.println("import org.springframework.batch.core.listener.StepExecutionListener;");
            out.println("import org.springframework.lang.NonNull;");
            out.println("import org.springframework.stereotype.Component;");
            out.println();
            out.println("/** Auto-generated step listener for " + className + " — DO NOT MODIFY */");
            out.println("@Slf4j");
            out.println("@Component");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + gen + " implements StepExecutionListener {");
            out.println();
            if (!dryRun) {
                out.println(I + "private final " + writerType + " writer;");
            }
            out.println(I + "private final " + className + " delegate;");
            out.println();
            out.println(I + "@Override");
            out.println(I + "public void beforeStep(@NonNull StepExecution stepExecution) {");
            out.println(II + "delegate.onExportStart();");
            out.println(II + "log.info(\"[" + className + "] Export step started\");");
            out.println(I + "}");
            out.println();
            out.println(I + "@Override");
            out.println(I + "public ExitStatus afterStep(@NonNull StepExecution stepExecution) {");
            out.println(II + "log.info(\"[" + className + "] Step done — Read: {}, Written: {}\",");
            out.println(III + "stepExecution.getReadCount(), stepExecution.getWriteCount());");
            out.println(II + "if (stepExecution.getStatus().isUnsuccessful()) {");
            out.println(III + "log.error(\"[" + className + "] Step failed — skipping file save\");");
            out.println(III + "return stepExecution.getExitStatus();");
            out.println(II + "}");
            if (dryRun) {
                out.println(II + "log.info(\"[DRY RUN][" + className + "] No file produced.\");");
            } else {
                out.println(II + "// Serialize file → upload to storage → fire onSaveComplete(url)");
                out.println(II + "writer.finalizeAndSave();");
            }
            out.println(II + "return stepExecution.getExitStatus();");
            out.println(I + "}");
            out.println("}");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private void generateExportSkipListener(String pkg, String className, String jobName,
                                             String entityClass, String entityFqn) throws IOException {
        String gen = className + "ExportSkipListener";
        try (PrintWriter out = new PrintWriter(
                processingEnv.getFiler().createSourceFile(pkg + "." + gen).openWriter())) {

            out.println("package " + pkg + ";");
            out.println();
            out.println("import " + entityFqn + ";");
            out.println("import com.eazy.batch.service.MetricsService;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.listener.SkipListener;");
            out.println("import org.springframework.lang.NonNull;");
            out.println("import org.springframework.stereotype.Component;");
            out.println("import static com.eazy.batch.utility.BatchUtility.addSkippedItem;");
            out.println();
            out.println("/** Auto-generated skip listener for " + className + " — DO NOT MODIFY */");
            out.println("@Slf4j");
            out.println("@Component");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + gen + " implements SkipListener<" + entityClass + ", " + entityClass + "> {");
            out.println();
            out.println(I + "private final MetricsService metricsService;");
            out.println();
            out.println(I + "@Override");
            out.println(I + "public void onSkipInRead(@NonNull Throwable throwable) {");
            out.println(II + "addSkippedItem(null, \"READ\", throwable.getMessage());");
            out.println(II + "metricsService.recordItemSkipped(\"" + jobName + "\", \"READ\");");
            out.println(II + "log.error(\"[" + className + "][SKIP-READ] {}\", throwable.getMessage(), throwable);");
            out.println(I + "}");
            out.println();
            out.println(I + "@Override");
            out.println(I + "public void onSkipInProcess(@NonNull " + entityClass + " item, @NonNull Throwable throwable) {");
            out.println(II + "addSkippedItem(item, \"PROCESS\", throwable.getMessage());");
            out.println(II + "metricsService.recordItemSkipped(\"" + jobName + "\", \"PROCESS\");");
            out.println(II + "log.error(\"[" + className + "][SKIP-PROCESS] {}\", throwable.getMessage(), throwable);");
            out.println(I + "}");
            out.println();
            out.println(I + "@Override");
            out.println(I + "public void onSkipInWrite(@NonNull " + entityClass + " item, @NonNull Throwable throwable) {");
            out.println(II + "addSkippedItem(item, \"WRITE\", throwable.getMessage());");
            out.println(II + "metricsService.recordItemSkipped(\"" + jobName + "\", \"WRITE\");");
            out.println(II + "log.error(\"[" + className + "][SKIP-WRITE] {}\", throwable.getMessage(), throwable);");
            out.println(I + "}");
            out.println("}");
        }
    }

    private String getClassFqn(BatchExportJob annotation) {
        try {
            annotation.entityClass();
            return null;
        } catch (MirroredTypeException mte) {
            return ((TypeElement) ((DeclaredType) mte.getTypeMirror()).asElement())
                    .getQualifiedName().toString();
        }
    }

    private String getSimpleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}