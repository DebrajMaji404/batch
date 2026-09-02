package com.eazy.batch.testfixtures;

import com.eazy.batch.annotation.BatchExportJob;
import com.eazy.batch.config.SimpleExportProcessor;
import com.eazy.batch.enums.StorageType;
import com.eazy.batch.model.ExportColumn;

import java.util.List;

/**
 * Exercises {@code @BatchExportJob} code generation: LOCAL storage, EXCEL
 * format (default), the JobCompletionListener/SkipListener/
 * BatchProgressChunkListener wiring, and notifyOnFailure/recipients
 * (-> generated ExportNotificationListener).
 *
 * <p>As with {@link SampleBatchJobConfig}, this class compiling successfully
 * is itself the regression test for {@code BatchExportJobAnnotationProcessor}'s
 * generated source.</p>
 */
@BatchExportJob(
        jobName = "sampleExportCodegenJob",
        stepName = "sampleExportCodegenStep",
        entityClass = SampleEmployee.class,
        storageType = StorageType.LOCAL,
        fileName = "sample-employees",
        notifyOnFailure = true,
        recipients = {"ops@example.com"}
)
public class SampleBatchExportJobConfig implements SimpleExportProcessor<SampleEmployee> {

    @Override
    public String getJpqlQuery() {
        return "SELECT e FROM SampleEmployee e";
    }

    @Override
    public List<ExportColumn<SampleEmployee>> getColumns() {
        return List.of(
                col("ID", SampleEmployee::getId),
                col("Name", SampleEmployee::getName),
                col("Email", SampleEmployee::getEmail)
        );
    }
}
