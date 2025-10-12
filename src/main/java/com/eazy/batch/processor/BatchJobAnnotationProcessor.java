package com.eazy.batch.processor;

import com.eazy.batch.annotation.BatchJob;
import org.jetbrains.annotations.NotNull;

import javax.annotation.processing.*;
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
 * Annotation processor that generates Spring Batch configuration classes
 * at compile time to avoid manual bean definitions and reduce boilerplate.
 */
@SupportedAnnotationTypes("com.eazy.batch.annotation.BatchJob")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class BatchJobAnnotationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(BatchJob.class)) {
            if (element instanceof TypeElement typeElement) {
                try {
                    generateBatchConfig(typeElement);
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Generated batch configuration for: " + typeElement.getSimpleName()
                    );
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Failed to generate batch config: " + e.getMessage(),
                            element
                    );
                }
            }
        }
        return true;
    }

    private void generateBatchConfig(TypeElement element) throws IOException {
        BatchJob annotation = element.getAnnotation(BatchJob.class);
        String packageName = processingEnv.getElementUtils().getPackageOf(element).toString();
        String className = element.getSimpleName().toString();
        String generatedClassName = className + "Generated";

        String jobName = annotation.jobName();
        String stepName = annotation.stepName();
        String batchName = annotation.batchName().isEmpty() ? jobName : annotation.batchName();
        int chunkSize = annotation.chunkSize();
        int skipLimit = annotation.skipLimit();

        // Get fully qualified names for DTO and Wrapper classes
        String dtoClassFqn = getClassFqn(annotation, "dtoClass");
        String wrapperClassFqn = getClassFqn(annotation, "wrapperClass");
        String dtoClassName = getSimpleName(dtoClassFqn);
        String wrapperClassName = getSimpleName(wrapperClassFqn);

        JavaFileObject builderFile = processingEnv.getFiler()
                .createSourceFile(packageName + "." + generatedClassName);

        try (PrintWriter out = new PrintWriter(builderFile.openWriter())) {
            writePackageAndImports(out, packageName, dtoClassFqn, wrapperClassFqn);
            writeClassDeclaration(out, generatedClassName, className);
            writeConstructor(out, generatedClassName, className);
            writeJobBean(out, jobName, stepName);
            writeStepBean(out, stepName, dtoClassName, wrapperClassName, chunkSize, skipLimit);
            writeReaderBean(out, stepName, dtoClassName);
            writeProcessorBean(out, stepName, dtoClassName, wrapperClassName);
            writeWriterBean(out, stepName, wrapperClassName);
            writeSkipListenerBean(out, stepName, dtoClassName, wrapperClassName);
            out.println("}");
        }
    }

    private String getClassFqn(BatchJob annotation, String methodName) {
        try {
            // This will throw MirroredTypeException
            if ("dtoClass".equals(methodName)) {
                annotation.dtoClass();
            } else {
                annotation.wrapperClass();
            }
            return null;
        } catch (MirroredTypeException mte) {
            DeclaredType classTypeMirror = (DeclaredType) mte.getTypeMirror();
            TypeElement classTypeElement = (TypeElement) classTypeMirror.asElement();
            return classTypeElement.getQualifiedName().toString();
        }
    }

    private String getSimpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private void writePackageAndImports(PrintWriter out, String packageName,
                                        String dtoClassFqn, String wrapperClassFqn) {
        out.println("package " + packageName + ";");
        out.println();
        out.println("import com.eazy.batch.listener.JobCompletionListener;");
        out.println("import com.eazy.batch.constant.AppConstant;");
        out.println("import com.eazy.batch.dto.BatchSkippedItem;");
        out.println("import com.eazy.batch.reader.ExcelItemReaderWithHeaderValidation;");
        out.println("import " + dtoClassFqn + ";");
        out.println("import " + wrapperClassFqn + ";");
        out.println("import jakarta.validation.ConstraintViolation;");
        out.println("import jakarta.validation.ConstraintViolationException;");
        out.println("import jakarta.validation.Validator;");
        out.println("import jakarta.validation.constraints.NotNull;");
        out.println("import lombok.extern.slf4j.Slf4j;");
        out.println("import org.springframework.batch.core.Job;");
        out.println("import org.springframework.batch.core.SkipListener;");
        out.println("import org.springframework.batch.core.Step;");
        out.println("import org.springframework.batch.core.configuration.annotation.StepScope;");
        out.println("import org.springframework.batch.core.job.builder.JobBuilder;");
        out.println("import org.springframework.batch.core.launch.support.RunIdIncrementer;");
        out.println("import org.springframework.batch.core.repository.JobRepository;");
        out.println("import org.springframework.batch.core.step.builder.StepBuilder;");
        out.println("import org.springframework.batch.item.ItemProcessor;");
        out.println("import org.springframework.batch.item.ItemReader;");
        out.println("import org.springframework.batch.item.ItemWriter;");
        out.println("import org.springframework.beans.factory.annotation.Value;");
        out.println("import org.springframework.context.annotation.Bean;");
        out.println("import org.springframework.context.annotation.Configuration;");
        out.println("import org.springframework.core.io.FileSystemResource;");
        out.println("import org.springframework.transaction.PlatformTransactionManager;");
        out.println();
        out.println("import java.util.ArrayList;");
        out.println("import java.util.List;");
        out.println("import java.util.Objects;");
        out.println("import java.util.Set;");
        out.println();
        out.println("import static com.eazy.batch.utility.BatchUtility.addSkippedItem;");
        out.println("import static com.eazy.batch.utility.BatchUtility.getSkippedItem;");
        out.println();
    }

    private void writeClassDeclaration(PrintWriter out, String generatedClassName, String className) {
        out.println("/**");
        out.println(" * Generated Spring Batch Configuration for " + className);
        out.println(" * DO NOT EDIT - This file is auto-generated by BatchJobAnnotationProcessor");
        out.println(" */");
        out.println("@Configuration");
        out.println("@Slf4j");
        out.println("public class " + generatedClassName + " {");
        out.println();
        out.println("    private final " + className + " delegate;");
        out.println("    private final JobRepository jobRepository;");
        out.println("    private final PlatformTransactionManager transactionManager;");
        out.println("    private final JobCompletionListener jobCompletionListener;");
        out.println("    private final Validator validator;");
        out.println();
    }

    private void writeConstructor(PrintWriter out, String generatedClassName, String className) {
        out.println("    public " + generatedClassName + "(");
        out.println("            " + className + " delegate,");
        out.println("            JobRepository jobRepository,");
        out.println("            PlatformTransactionManager transactionManager,");
        out.println("            JobCompletionListener jobCompletionListener,");
        out.println("            Validator validator) {");
        out.println("        this.delegate = delegate;");
        out.println("        this.jobRepository = jobRepository;");
        out.println("        this.transactionManager = transactionManager;");
        out.println("        this.jobCompletionListener = jobCompletionListener;");
        out.println("        this.validator = validator;");
        out.println("    }");
        out.println();
    }

    private void writeJobBean(PrintWriter out, String jobName, String stepName) {
        out.println("    @Bean(name = \"" + jobName + "\")");
        out.println("    public Job " + jobName + "(");
        out.println("            @org.springframework.beans.factory.annotation.Qualifier(\"" + stepName + "\") Step step) {");
        out.println("        return new JobBuilder(\"" + jobName + "\", jobRepository)");
        out.println("                .incrementer(new RunIdIncrementer())");
        out.println("                .start(step)");
        out.println("                .listener(jobCompletionListener)");
        out.println("                .build();");
        out.println("    }");
        out.println();
    }

    private void writeStepBean(PrintWriter out, String stepName, String dtoClassName,
                               String wrapperClassName, int chunkSize, int skipLimit) {
        out.println("    @Bean(name = \"" + stepName + "\")");
        out.println("    public Step " + stepName + "(");
        out.println("            @org.springframework.beans.factory.annotation.Qualifier(\"" + stepName + "Reader\") ItemReader<" + dtoClassName + "> reader,");
        out.println("            @org.springframework.beans.factory.annotation.Qualifier(\"" + stepName + "Processor\") ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> processor,");
        out.println("            @org.springframework.beans.factory.annotation.Qualifier(\"" + stepName + "Writer\") ItemWriter<" + wrapperClassName + "> writer,");
        out.println("            @org.springframework.beans.factory.annotation.Qualifier(\"" + stepName + "SkipListener\") SkipListener<" + dtoClassName + ", " + wrapperClassName + "> skipListener) {");
        out.println();
        out.println("        return new StepBuilder(\"" + stepName + "\", jobRepository)");
        out.println("                .<" + dtoClassName + ", " + wrapperClassName + ">chunk(" + chunkSize + ", transactionManager)");
        out.println("                .reader(reader)");
        out.println("                .processor(processor)");
        out.println("                .writer(writer)");
        out.println("                .faultTolerant()");
        out.println("                .skipLimit(" + skipLimit + ")");
        out.println("                .skip(Exception.class)");
        out.println("                .listener(skipListener)");
        out.println("                .build();");
        out.println("    }");
        out.println();
    }

    private void writeReaderBean(PrintWriter out, String stepName, String dtoClassName) {
        out.println("    @Bean(name = \"" + stepName + "Reader\")");
        out.println("    @StepScope");
        out.println("    public ItemReader<" + dtoClassName + "> " + stepName + "Reader(");
        out.println("            @Value(\"#{jobParameters['filePath']}\") String filePath) {");
        out.println("        return new ExcelItemReaderWithHeaderValidation<>(");
        out.println("                new FileSystemResource(filePath),");
        out.println("                " + dtoClassName + ".class");
        out.println("        );");
        out.println("    }");
        out.println();
    }

    private void writeProcessorBean(PrintWriter out, String stepName, String dtoClassName, String wrapperClassName) {
        out.println("    @Bean(name = \"" + stepName + "Processor\")");
        out.println("    public ItemProcessor<" + dtoClassName + ", " + wrapperClassName + "> " + stepName + "Processor() {");
        out.println("        return dto -> {");
        out.println("            Set<ConstraintViolation<" + dtoClassName + ">> violations = validator.validate(dto);");
        out.println("            if (!violations.isEmpty()) {");
        out.println("                throw new ConstraintViolationException(violations);");
        out.println("            }");
        out.println("            return delegate.process(dto);");
        out.println("        };");
        out.println("    }");
        out.println();
    }

    private void writeWriterBean(PrintWriter out, String stepName, String wrapperClassName) {
        out.println("    @Bean(name = \"" + stepName + "Writer\")");
        out.println("    public ItemWriter<" + wrapperClassName + "> " + stepName + "Writer() {");
        out.println("        return chunk -> {");
        out.println("            List<" + wrapperClassName + "> items = chunk.getItems().stream()");
        out.println("                    .filter(Objects::nonNull)");
        out.println("                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));");
        out.println("            delegate.save(items);");
        out.println("        };");
        out.println("    }");
        out.println();
    }

    private void writeSkipListenerBean(@NotNull PrintWriter out, String stepName, String dtoClassName, String wrapperClassName) {
        out.println("    @Bean(name = \"" + stepName + "SkipListener\")");
        out.println("    public SkipListener<" + dtoClassName + ", " + wrapperClassName + "> " + stepName + "SkipListener() {");
        out.println("        return new SkipListener<" + dtoClassName + ", " + wrapperClassName + ">() {");
        out.println("            @Override");
        out.println("            public void onSkipInRead(@NotNull Throwable t) {");
        out.println("                BatchSkippedItem<Object> batchSkippedItem = getSkippedItem(null, \"READ\", t.getMessage());");
        out.println("                if (batchSkippedItem == null) {");
        out.println("                    addSkippedItem(null, \"READ\", t.getMessage());");
        out.println("                    log.error(\"Read error: {}\", t.getMessage());");
        out.println("                }");
        out.println("            }");
        out.println();
        out.println("            @Override");
        out.println("            public void onSkipInProcess(@NotNull " + dtoClassName + " dto, @NotNull Throwable t) {");
        out.println("                addSkippedItem(dto, \"PROCESS\", t.getMessage());");
        out.println("                log.error(\"Process error for {}: {}\", delegate.getIdentifier(dto), t.getMessage());");
        out.println("            }");
        out.println();
        out.println("            @Override");
        out.println("            public void onSkipInWrite(@NotNull " + wrapperClassName + " wrapper, @NotNull Throwable t) {");
        out.println("                addSkippedItem(wrapper, \"WRITE\", t.getMessage());");
        out.println("                log.error(\"Write error for {}: {}\", delegate.getIdentifier(wrapper), t.getMessage());");
        out.println("            }");
        out.println("        };");
        out.println("    }");
    }
}