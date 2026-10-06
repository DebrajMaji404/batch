package com.eazy.batch.testfixtures;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.config.SimpleBatchProcessor;
import com.eazy.batch.enums.FileType;

import java.util.List;

/** Code-generation fixture for {@code fileType = JSON}. */
@BatchJob(
        jobName = "sampleJsonJob",
        stepName = "sampleJsonStep",
        dtoClass = SampleDto.class,
        wrapperClass = SampleWrapper.class,
        fileType = FileType.JSON
)
public class SampleJsonBatchJobConfig implements SimpleBatchProcessor<SampleDto, SampleWrapper> {

    @Override
    public SampleWrapper process(SampleDto dto) {
        return new SampleWrapper(List.of(new SamplePerson(dto.getName(), dto.getAge())));
    }

    @Override
    public void save(List<SampleWrapper> wrappers) {
        // No-op: code generation fixture.
    }
}
