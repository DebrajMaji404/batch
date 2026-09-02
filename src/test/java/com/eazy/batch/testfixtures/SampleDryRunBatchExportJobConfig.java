package com.eazy.batch.testfixtures;

import com.eazy.batch.annotation.BatchExportJob;
import com.eazy.batch.config.SimpleExportProcessor;
import com.eazy.batch.enums.StorageType;
import com.eazy.batch.model.ExportColumn;

import java.util.List;

/**
 * Exercises {@code @BatchExportJob(dryRun = true)} code generation - the
 * no-op ItemWriter branch and the StepListener branch that skips
 * writer.finalizeAndSave(), which is otherwise untested by
 * {@link SampleBatchExportJobConfig}.
 */
@BatchExportJob(
        jobName = "sampleDryRunExportCodegenJob",
        stepName = "sampleDryRunExportCodegenStep",
        entityClass = SampleEmployee.class,
        storageType = StorageType.LOCAL,
        fileName = "sample-dry-run",
        dryRun = true
)
public class SampleDryRunBatchExportJobConfig implements SimpleExportProcessor<SampleEmployee> {

    @Override
    public String getJpqlQuery() {
        return "SELECT e FROM SampleEmployee e";
    }

    @Override
    public List<ExportColumn<SampleEmployee>> getColumns() {
        return List.of(col("Name", SampleEmployee::getName));
    }
}
