package com.eazy.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that {@code BatchJobAnnotationProcessor} and
 * {@code BatchExportJobAnnotationProcessor} actually ran during test
 * compilation and produced every expected generated class, by loading each
 * one via reflection.
 *
 * <p>This is deliberately NOT testing behavior - it's testing that code
 * generation happened and produced classes with the expected names. The
 * real value of this test is upstream of any assertion here: the fixture
 * classes in {@code com.eazy.batch.testfixtures} (annotated with
 * {@code @BatchJob}/{@code @BatchExportJob}) only compile in the first place
 * if the annotation processors emitted syntactically and semantically valid
 * Java - a bad import, a call to a nonexistent constructor/method, or a
 * type mismatch in generated source fails the build right here, at test
 * time, instead of only surfacing later in a downstream consumer's
 * project.</p>
 */
class AnnotationProcessorCodegenTest {

    private static final String PKG = "com.eazy.batch.testfixtures.";

    @ParameterizedTest
    @ValueSource(strings = {
            // Generated for SampleBatchJobConfig (@BatchJob)
            PKG + "SampleBatchJobConfigConfiguration",
            PKG + "SampleBatchJobConfigReader",
            PKG + "SampleBatchJobConfigProcessor",
            PKG + "SampleBatchJobConfigWriter",
            PKG + "SampleBatchJobConfigSkipListener",
            // notifyOnCompletion = true -> a notification listener must exist
            PKG + "SampleBatchJobConfigNotificationListener",

            // Generated for SampleParallelBatchJobConfig (@BatchJob, parallelProcessing = true)
            PKG + "SampleParallelBatchJobConfigConfiguration",
            PKG + "SampleParallelBatchJobConfigReader",
            PKG + "SampleParallelBatchJobConfigProcessor",
            PKG + "SampleParallelBatchJobConfigWriter",
            PKG + "SampleParallelBatchJobConfigSkipListener",

            // Generated for SampleBatchExportJobConfig (@BatchExportJob)
            PKG + "SampleBatchExportJobConfigExportConfiguration",
            PKG + "SampleBatchExportJobConfigExportReader",
            PKG + "SampleBatchExportJobConfigExportWriter",
            PKG + "SampleBatchExportJobConfigExportStepListener",
            PKG + "SampleBatchExportJobConfigExportSkipListener",
            // notifyOnFailure = true -> a notification listener must exist
            PKG + "SampleBatchExportJobConfigExportNotificationListener",

            // Generated for SampleDryRunBatchExportJobConfig (@BatchExportJob, dryRun = true)
            PKG + "SampleDryRunBatchExportJobConfigExportConfiguration",
            PKG + "SampleDryRunBatchExportJobConfigExportReader",
            PKG + "SampleDryRunBatchExportJobConfigExportWriter",
            PKG + "SampleDryRunBatchExportJobConfigExportStepListener",
            PKG + "SampleDryRunBatchExportJobConfigExportSkipListener",
    })
    void generatedClassIsLoadable(String fqcn) {
        assertThatCode(() -> Class.forName(fqcn))
                .as("Expected the annotation processor to generate and successfully compile: " + fqcn)
                .doesNotThrowAnyException();
    }

    @Test
    void noNotificationListenerGeneratedWhenNotRequested() {
        // SampleParallelBatchJobConfig doesn't set notifyOnCompletion/notifyOnFailure,
        // so no *NotificationListener class should have been generated for it.
        assertThatCode(() -> Class.forName(PKG + "SampleParallelBatchJobConfigNotificationListener"))
                .as("No NotificationListener should be generated when notifyOnCompletion/notifyOnFailure are both false")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void generatedJobConfigurationIsASpringConfigurationClass() throws ClassNotFoundException {
        Class<?> configClass = Class.forName(PKG + "SampleBatchJobConfigConfiguration");
        assertThat(configClass.isAnnotationPresent(org.springframework.context.annotation.Configuration.class))
                .as("Generated *Configuration class should be a Spring @Configuration")
                .isTrue();

        boolean hasJobBean = java.util.Arrays.stream(configClass.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)
                        && m.getReturnType().equals(org.springframework.batch.core.job.Job.class));
        assertThat(hasJobBean)
                .as("Generated *Configuration class should declare a @Bean method returning Job")
                .isTrue();
    }
}
