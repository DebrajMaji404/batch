package com.eazy.batch.testfixtures;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.config.SimpleBatchProcessor;

import java.util.List;

/**
 * Exercises {@code @BatchJob} code generation with: default cacheValidation,
 * custom requiredParameters/optionalParameters (-> DefaultJobParametersValidator
 * wiring), and notifyOnCompletion/recipients (-> generated NotificationListener).
 *
 * <p>This class existing and compiling successfully - together with
 * {@link com.eazy.batch.AnnotationProcessorCodegenTest} asserting the
 * generated classes are loadable - is itself the regression test: any bug
 * in {@code BatchJobAnnotationProcessor}'s generated source (a bad import, a
 * non-existent method/constructor call, a type mismatch) fails the build
 * right here instead of only surfacing in a downstream consumer's project.</p>
 */
@BatchJob(
        jobName = "sampleCodegenJob",
        stepName = "sampleCodegenStep",
        dtoClass = SampleDto.class,
        wrapperClass = SampleWrapper.class,
        requiredParameters = {"filePath"},
        optionalParameters = {"note"},
        notifyOnCompletion = true,
        recipients = {"ops@example.com"}
)
public class SampleBatchJobConfig implements SimpleBatchProcessor<SampleDto, SampleWrapper> {

    @Override
    public SampleWrapper process(SampleDto dto) {
        return new SampleWrapper(List.of(new SamplePerson(dto.getName(), dto.getAge())));
    }

    @Override
    public void save(List<SampleWrapper> wrappers) {
        // No-op: this fixture only exists to exercise code generation.
    }
}
