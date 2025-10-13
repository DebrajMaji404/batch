package com.eazy.batch.processor;

import com.eazy.batch.annotation.BatchJob;

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
 * Enterprise-grade annotation processor that generates Spring Batch configuration classes
 * at compile time to eliminate boilerplate and ensure consistency across batch jobs.
 *
 * <p>This processor generates the following components for each {@link BatchJob} annotated class:
 * <ul>
 *   <li>Job Configuration with proper dependency injection</li>
 *   <li>ItemReader with step scope and header validation</li>
 *   <li>ItemProcessor with Jakarta validation</li>
 *   <li>ItemWriter with null-safe chunk processing</li>
 *   <li>SkipListener with comprehensive error tracking</li>
 * </ul>
 *
 * @author Generated Code
 * @version 2.1
 * @since 1.0
 */
@SupportedAnnotationTypes("com.eazy.batch.annotation.BatchJob")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class BatchJobAnnotationProcessor extends AbstractProcessor {

    private static final String INDENT = "    ";
    private static final String BEAN_SUFFIX_READER = "ItemReader";
    private static final String BEAN_SUFFIX_PROCESSOR = "ItemProcessor";
    private static final String BEAN_SUFFIX_WRITER = "ItemWriter";
    private static final String BEAN_SUFFIX_SKIP_LISTENER = "SkipListener";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
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

    private void generateBatchComponents(TypeElement element) throws IOException {
        BatchJob annotation = element.getAnnotation(BatchJob.class);
        String packageName = processingEnv.getElementUtils().getPackageOf(element).toString();
        String className = element.getSimpleName().toString();

        String jobName = annotation.jobName();
        String stepName = annotation.stepName();

        // Extract class information
        String dtoClassFqn = getClassFqn(annotation, "dtoClass");
        String wrapperClassFqn = getClassFqn(annotation, "wrapperClass");
        String dtoClassName = getSimpleName(dtoClassFqn);
        String wrapperClassName = getSimpleName(wrapperClassFqn);

        // Generate all components with consistent bean naming
        generateJobConfiguration(packageName, className, jobName, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn);
        generateReader(packageName, className, stepName, dtoClassName, dtoClassFqn);
        generateProcessor(packageName, className, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn);
        generateWriter(packageName, className, stepName, wrapperClassName, wrapperClassFqn);
        generateSkipListener(packageName, className, stepName, dtoClassName, wrapperClassName, dtoClassFqn, wrapperClassFqn);
    }

    private void generateJobConfiguration(String packageName, String className, String jobName, String stepName,
                                          String dtoClassName, String wrapperClassName,
                                          String dtoClassFqn, String wrapperClassFqn) throws IOException {
        String generatedClassName = className + "Configuration";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            // Package and imports
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + dtoClassFqn + ";");
            out.println("import " + wrapperClassFqn + ";");
            out.println("import com.eazy.batch.listener.JobCompletionListener;");
            out.println("import com.eazy.batch.constant.AppConstant;");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.Job;");
            out.println("import org.springframework.batch.core.SkipListener;");
            out.println("import org.springframework.batch.core.Step;");
            out.println("import org.springframework.batch.core.job.builder.JobBuilder;");
            out.println("import org.springframework.batch.core.launch.support.RunIdIncrementer;");
            out.println("import org.springframework.batch.core.repository.JobRepository;");
            out.println("import org.springframework.batch.core.step.builder.StepBuilder;");
            out.println("import org.springframework.batch.item.ItemProcessor;");
            out.println("import org.springframework.batch.item.ItemReader;");
            out.println("import org.springframework.batch.item.ItemWriter;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import org.springframework.transaction.PlatformTransactionManager;");
            out.println();

            // JavaDoc
            out.println("/**");
            out.println(" * Spring Batch Job Configuration for " + className);
            out.println(" * <p>This class is auto-generated by {@link BatchJobAnnotationProcessor}.</p>");
            out.println(" * <p><b>DO NOT MODIFY</b> - Any changes will be overwritten on next compilation.</p>");
            out.println(" * ");
            out.println(" * <p>Configuration includes:");
            out.println(" * <ul>");
            out.println(" *   <li>Job: " + jobName + "</li>");
            out.println(" *   <li>Step: " + stepName + "</li>");
            out.println(" *   <li>Chunk-based processing with fault tolerance</li>");
            out.println(" *   <li>Skip handling with comprehensive logging</li>");
            out.println(" * </ul>");
            out.println(" * ");
            out.println(" * @see " + className);
            out.println(" * @generated " + getClass().getName());
            out.println(" */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " {");
            out.println();

            // Fields
            out.println(INDENT + "private final JobRepository jobRepository;");
            out.println(INDENT + "private final PlatformTransactionManager transactionManager;");
            out.println(INDENT + "private final JobCompletionListener jobCompletionListener;");
            out.println();

            // Job Bean
            out.println(INDENT + "/**");
            out.println(INDENT + " * Defines the batch job with automatic run ID incrementer and completion listener.");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @param " + stepName + " the step to execute");
            out.println(INDENT + " * @return configured Job instance");
            out.println(INDENT + " */");
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public Job " + jobName + "(Step " + stepName + ") {");
            out.println(INDENT + INDENT + "log.info(\"Initializing batch job: {}\", \"" + jobName + "\");");
            out.println(INDENT + INDENT + "return new JobBuilder(\"" + jobName + "\", jobRepository)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".incrementer(new RunIdIncrementer())");
            out.println(INDENT + INDENT + INDENT + INDENT + ".start(" + stepName + ")");
            out.println(INDENT + INDENT + INDENT + INDENT + ".listener(jobCompletionListener)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".build();");
            out.println(INDENT + "}");
            out.println();

            // Step Bean - FIXED: Removed @Qualifier annotations
            out.println(INDENT + "/**");
            out.println(INDENT + " * Defines the batch step with chunk-oriented processing and fault tolerance.");
            out.println(INDENT + " * <p>Uses type-based dependency injection for proper skip listener registration.</p>");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @param reader the item reader");
            out.println(INDENT + " * @param processor the item processor");
            out.println(INDENT + " * @param writer the item writer");
            out.println(INDENT + " * @param skipListener the skip listener for error handling");
            out.println(INDENT + " * @return configured Step instance");
            out.println(INDENT + " */");
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public Step " + stepName + "(");
            out.println(INDENT + INDENT + INDENT + "ItemReader<" + dtoClassName + "> reader,");
            out.println(INDENT + INDENT + INDENT + "ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> processor,");
            out.println(INDENT + INDENT + INDENT + "ItemWriter<" + wrapperClassName + "> writer,");
            out.println(INDENT + INDENT + INDENT + "SkipListener<" + dtoClassName + ", " + wrapperClassName + "> skipListener");
            out.println(INDENT + ") {");
            out.println(INDENT + INDENT + "log.info(\"Initializing batch step: {}\", \"" + stepName + "\");");
            out.println(INDENT + INDENT + "return new StepBuilder(\"" + stepName + "\", jobRepository)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".<" + dtoClassName + ", " + wrapperClassName + ">chunk(AppConstant.BatchJob.CHUNK_SIZE, transactionManager)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".reader(reader)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".processor(processor)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".writer(writer)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".faultTolerant()");
            out.println(INDENT + INDENT + INDENT + INDENT + ".skipLimit(AppConstant.BatchJob.SKIP_LIMIT)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".skip(Exception.class)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".noRollback(Exception.class)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".listener(skipListener)");
            out.println(INDENT + INDENT + INDENT + INDENT + ".build();");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateReader(String packageName, String className, String stepName,
                                String dtoClassName, String dtoClassFqn) throws IOException {
        String generatedClassName = className + "Reader";
        String beanName = stepName + BEAN_SUFFIX_READER;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import com.eazy.batch.reader.ExcelItemReaderWithHeaderValidation;");
            out.println("import " + dtoClassFqn + ";");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.configuration.annotation.StepScope;");
            out.println("import org.springframework.batch.item.ItemReader;");
            out.println("import org.springframework.beans.factory.annotation.Value;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println("import org.springframework.core.io.FileSystemResource;");
            out.println();

            out.println("/**");
            out.println(" * Excel Item Reader for " + className);
            out.println(" * <p>Reads and validates Excel file headers before processing.</p>");
            out.println(" * <p><b>DO NOT MODIFY</b> - Auto-generated by {@link BatchJobAnnotationProcessor}.</p>");
            out.println(" * ");
            out.println(" * @see " + className);
            out.println(" * @generated " + getClass().getName());
            out.println(" */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("public class " + generatedClassName + " {");
            out.println();

            out.println(INDENT + "/**");
            out.println(INDENT + " * Creates a step-scoped Excel item reader with header validation.");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @param filePath the path to the Excel file (from job parameters)");
            out.println(INDENT + " * @return configured ItemReader instance");
            out.println(INDENT + " */");
            out.println(INDENT + "@Bean");
            out.println(INDENT + "@StepScope");
            out.println(INDENT + "public ItemReader<" + dtoClassName + "> " + beanName + "(");
            out.println(INDENT + INDENT + INDENT + "@Value(\"#{jobParameters['filePath']}\") String filePath) {");
            out.println(INDENT + INDENT + "log.debug(\"Initializing Excel reader for file: {}\", filePath);");
            out.println(INDENT + INDENT + "return new ExcelItemReaderWithHeaderValidation<>(");
            out.println(INDENT + INDENT + INDENT + INDENT + "new FileSystemResource(filePath),");
            out.println(INDENT + INDENT + INDENT + INDENT + dtoClassName + ".class");
            out.println(INDENT + INDENT + ");");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateProcessor(String packageName, String className, String stepName,
                                   String dtoClassName, String wrapperClassName,
                                   String dtoClassFqn, String wrapperClassFqn) throws IOException {
        String generatedClassName = className + "Processor";
        String beanName = stepName + BEAN_SUFFIX_PROCESSOR;
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
            out.println("import org.springframework.batch.item.ItemProcessor;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println();
            out.println("import java.util.Set;");
            out.println("import java.util.stream.Collectors;");
            out.println();

            out.println("/**");
            out.println(" * Item Processor for " + className);
            out.println(" * <p>Validates DTOs using Jakarta Bean Validation before delegating to business logic.</p>");
            out.println(" * <p><b>DO NOT MODIFY</b> - Auto-generated by {@link BatchJobAnnotationProcessor}.</p>");
            out.println(" * ");
            out.println(" * @see " + className);
            out.println(" * @generated " + getClass().getName());
            out.println(" */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " {");
            out.println();

            out.println(INDENT + "private final " + className + " delegate;");
            out.println(INDENT + "private final Validator validator;");
            out.println();

            out.println(INDENT + "/**");
            out.println(INDENT + " * Creates an item processor with Jakarta Bean Validation.");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @return configured ItemProcessor instance");
            out.println(INDENT + " */");
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> " + beanName + "() {");
            out.println(INDENT + INDENT + "return dto -> {");
            out.println(INDENT + INDENT + INDENT + "if (dto == null) {");
            out.println(INDENT + INDENT + INDENT + INDENT + "log.warn(\"Received null DTO in processor\");");
            out.println(INDENT + INDENT + INDENT + INDENT + "return null;");
            out.println(INDENT + INDENT + INDENT + "}");
            out.println();
            out.println(INDENT + INDENT + INDENT + "// Validate DTO");
            out.println(INDENT + INDENT + INDENT + "Set<ConstraintViolation<" + dtoClassName + ">> violations = validator.validate(dto);");
            out.println(INDENT + INDENT + INDENT + "if (!violations.isEmpty()) {");
            out.println(INDENT + INDENT + INDENT + INDENT + "String errors = violations.stream()");
            out.println(INDENT + INDENT + INDENT + INDENT + INDENT + INDENT + ".map(v -> v.getPropertyPath() + \": \" + v.getMessage())");
            out.println(INDENT + INDENT + INDENT + INDENT + INDENT + INDENT + ".collect(Collectors.joining(\", \"));");
            out.println(INDENT + INDENT + INDENT + INDENT + "log.error(\"Validation failed for DTO: {}\", errors);");
            out.println(INDENT + INDENT + INDENT + INDENT + "throw new RuntimeException(\"Validation failed: \" + errors);");
            out.println(INDENT + INDENT + INDENT + "}");
            out.println();
            out.println(INDENT + INDENT + INDENT + "// Delegate to business logic");
            out.println(INDENT + INDENT + INDENT + "return delegate.process(dto);");
            out.println(INDENT + INDENT + "};");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateWriter(String packageName, String className, String stepName,
                                String wrapperClassName, String wrapperClassFqn) throws IOException {
        String generatedClassName = className + "Writer";
        String beanName = stepName + BEAN_SUFFIX_WRITER;
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + wrapperClassFqn + ";");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.item.Chunk;");
            out.println("import org.springframework.batch.item.ItemWriter;");
            out.println("import org.springframework.context.annotation.Bean;");
            out.println("import org.springframework.context.annotation.Configuration;");
            out.println();
            out.println("import java.util.List;");
            out.println("import java.util.Objects;");
            out.println("import java.util.stream.Collectors;");
            out.println();

            out.println("/**");
            out.println(" * Item Writer for " + className);
            out.println(" * <p>Filters null items and delegates to persistence logic.</p>");
            out.println(" * <p><b>DO NOT MODIFY</b> - Auto-generated by {@link BatchJobAnnotationProcessor}.</p>");
            out.println(" * ");
            out.println(" * @see " + className);
            out.println(" * @generated " + getClass().getName());
            out.println(" */");
            out.println("@Slf4j");
            out.println("@Configuration");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " {");
            out.println();

            out.println(INDENT + "private final " + className + " delegate;");
            out.println();

            out.println(INDENT + "/**");
            out.println(INDENT + " * Creates an item writer that filters null items before persistence.");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @return configured ItemWriter instance");
            out.println(INDENT + " */");
            out.println(INDENT + "@Bean");
            out.println(INDENT + "public ItemWriter<" + wrapperClassName + "> " + beanName + "() {");
            out.println(INDENT + INDENT + "return chunk -> {");
            out.println(INDENT + INDENT + INDENT + "List<" + wrapperClassName + "> validItems = chunk.getItems().stream()");
            out.println(INDENT + INDENT + INDENT + INDENT + INDENT + ".filter(Objects::nonNull)");
            out.println(INDENT + INDENT + INDENT + INDENT + INDENT + ".collect(Collectors.toList());");
            out.println();
            out.println(INDENT + INDENT + INDENT + "if (validItems.isEmpty()) {");
            out.println(INDENT + INDENT + INDENT + INDENT + "log.warn(\"No valid items to write in current chunk\");");
            out.println(INDENT + INDENT + INDENT + INDENT + "return;");
            out.println(INDENT + INDENT + INDENT + "}");
            out.println();
            out.println(INDENT + INDENT + INDENT + "log.debug(\"Writing {} items to persistence layer\", validItems.size());");
            out.println(INDENT + INDENT + INDENT + "delegate.save(validItems);");
            out.println(INDENT + INDENT + "};");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    private void generateSkipListener(String packageName, String className, String stepName,
                                      String dtoClassName, String wrapperClassName,
                                      String dtoClassFqn, String wrapperClassFqn) throws IOException {
        String generatedClassName = className + "SkipListener";
        JavaFileObject file = processingEnv.getFiler().createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(file.openWriter())) {
            out.println("package " + packageName + ";");
            out.println();
            out.println("import " + dtoClassFqn + ";");
            out.println("import " + wrapperClassFqn + ";");
            out.println("import lombok.RequiredArgsConstructor;");
            out.println("import lombok.extern.slf4j.Slf4j;");
            out.println("import org.springframework.batch.core.SkipListener;");
            out.println("import org.springframework.lang.NonNull;");
            out.println("import org.springframework.stereotype.Component;");
            out.println();
            out.println("import static com.eazy.batch.utility.BatchUtility.addSkippedItem;");
            out.println();

            out.println("/**");
            out.println(" * Skip Listener for " + className);
            out.println(" * <p>Tracks and logs skipped items during read, process, and write phases.</p>");
            out.println(" * <p><b>DO NOT MODIFY</b> - Auto-generated by {@link BatchJobAnnotationProcessor}.</p>");
            out.println(" * ");
            out.println(" * @see " + className);
            out.println(" * @generated " + getClass().getName());
            out.println(" */");
            out.println("@Slf4j");
            out.println("@Component");
            out.println("@RequiredArgsConstructor");
            out.println("public class " + generatedClassName + " implements SkipListener<" + dtoClassName + ", " + wrapperClassName + "> {");
            out.println();

            out.println(INDENT + "private final " + className + " delegate;");
            out.println();

            // onSkipInRead
            out.println(INDENT + "/**");
            out.println(INDENT + " * Handles skipped items during the read phase.");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @param throwable the exception that caused the skip");
            out.println(INDENT + " */");
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void onSkipInRead(@NonNull Throwable throwable) {");
            out.println(INDENT + INDENT + "String errorMessage = throwable.getMessage();");
            out.println(INDENT + INDENT + "addSkippedItem(null, \"READ\", errorMessage);");
            out.println(INDENT + INDENT + "log.error(\"[SKIP-READ] Error reading item: {}\", errorMessage, throwable);");
            out.println(INDENT + "}");
            out.println();

            // onSkipInProcess
            out.println(INDENT + "/**");
            out.println(INDENT + " * Handles skipped items during the process phase.");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @param dto the DTO that failed processing");
            out.println(INDENT + " * @param throwable the exception that caused the skip");
            out.println(INDENT + " */");
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void onSkipInProcess(@NonNull " + dtoClassName + " dto, @NonNull Throwable throwable) {");
            out.println(INDENT + INDENT + "String identifier = delegate.getIdentifier(dto);");
            out.println(INDENT + INDENT + "String errorMessage = throwable.getMessage();");
            out.println(INDENT + INDENT + "addSkippedItem(dto, \"PROCESS\", errorMessage);");
            out.println(INDENT + INDENT + "log.error(\"[SKIP-PROCESS] Error processing item [{}]: {}\", identifier, errorMessage, throwable);");
            out.println(INDENT + "}");
            out.println();

            // onSkipInWrite
            out.println(INDENT + "/**");
            out.println(INDENT + " * Handles skipped items during the write phase.");
            out.println(INDENT + " * ");
            out.println(INDENT + " * @param wrapper the wrapper that failed to write");
            out.println(INDENT + " * @param throwable the exception that caused the skip");
            out.println(INDENT + " */");
            out.println(INDENT + "@Override");
            out.println(INDENT + "public void onSkipInWrite(@NonNull " + wrapperClassName + " wrapper, @NonNull Throwable throwable) {");
            out.println(INDENT + INDENT + "String identifier = delegate.getIdentifier(wrapper);");
            out.println(INDENT + INDENT + "String errorMessage = throwable.getMessage();");
            out.println(INDENT + INDENT + "addSkippedItem(wrapper, \"WRITE\", errorMessage);");
            out.println(INDENT + INDENT + "log.error(\"[SKIP-WRITE] Error writing item [{}]: {}\", identifier, errorMessage, throwable);");
            out.println(INDENT + "}");
            out.println("}");
        }
    }

    /**
     * Extracts the fully qualified class name from annotation attribute using MirroredTypeException.
     *
     * @param annotation the BatchJob annotation
     * @param methodName the method name ("dtoClass" or "wrapperClass")
     * @return fully qualified class name
     */
    private String getClassFqn(BatchJob annotation, String methodName) {
        try {
            if ("dtoClass".equals(methodName)) {
                annotation.dtoClass();
            } else {
                annotation.wrapperClass();
            }
            return null; // Should never reach here
        } catch (MirroredTypeException mte) {
            DeclaredType classTypeMirror = (DeclaredType) mte.getTypeMirror();
            TypeElement classTypeElement = (TypeElement) classTypeMirror.asElement();
            return classTypeElement.getQualifiedName().toString();
        }
    }

    /**
     * Extracts simple class name from fully qualified name.
     *
     * @param fqn fully qualified class name
     * @return simple class name
     */
    private String getSimpleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return "";
        }
        int lastDotIndex = fqn.lastIndexOf('.');
        return lastDotIndex >= 0 ? fqn.substring(lastDotIndex + 1) : fqn;
    }

    /**
     * Logs informational messages during annotation processing.
     *
     * @param message the message to log
     */
    private void logInfo(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message);
    }

    /**
     * Logs error messages during annotation processing.
     *
     * @param message the error message
     * @param element the element that caused the error
     */
    private void logError(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}