package com.eazy.batch.testfixtures;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.config.SimpleBatchProcessor;

import java.util.List;

/**
 * Exercises {@code @BatchJob(parallelProcessing = true)} code generation -
 * the SynchronizedItemStreamReaderBuilder wrapping and .taskExecutor(...)
 * wiring path, which is otherwise untested by {@link SampleBatchJobConfig}.
 */
@BatchJob(
        jobName = "sampleParallelCodegenJob",
        stepName = "sampleParallelCodegenStep",
        dtoClass = SampleDto.class,
        wrapperClass = SampleWrapper.class,
        parallelProcessing = true,
        threadPoolSize = 2
)
public class SampleParallelBatchJobConfig implements SimpleBatchProcessor<SampleDto, SampleWrapper> {

    @Override
    public SampleWrapper process(SampleDto dto) {
        return new SampleWrapper(List.of(new SamplePerson(dto.getName(), dto.getAge())));
    }

    @Override
    public void save(List<SampleWrapper> wrappers) {
        // No-op: this fixture only exists to exercise code generation.
    }
}
