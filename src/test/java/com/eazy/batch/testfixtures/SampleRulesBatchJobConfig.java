package com.eazy.batch.testfixtures;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.config.SimpleBatchProcessor;
import com.eazy.batch.enums.SkipLimitMode;

import java.util.List;

/**
 * Exercises the skip-rule, collect-all-errors, unique-key and row-isolation options of
 * {@code @BatchJob}: the generated sources must compile and be loadable.
 */
@BatchJob(
        jobName = "sampleRulesJob",
        stepName = "sampleRulesStep",
        dtoClass = SampleDto.class,
        wrapperClass = SampleWrapper.class,
        onSkipLimit = SkipLimitMode.CONTINUE,
        skipOn = {IllegalArgumentException.class, IllegalStateException.class},
        noSkipOn = {OutOfMemoryError.class},
        uniqueKey = {"name"},
        rowIsolation = true
)
public class SampleRulesBatchJobConfig implements SimpleBatchProcessor<SampleDto, SampleWrapper> {

    @Override
    public SampleWrapper process(SampleDto dto) {
        return new SampleWrapper(List.of(new SamplePerson(dto.getName(), dto.getAge())));
    }

    @Override
    public void save(List<SampleWrapper> wrappers) {
        // No-op: code generation fixture.
    }
}
