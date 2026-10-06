package demo;

import com.eazy.batch.annotation.BatchJob;
import com.eazy.batch.config.SimpleBatchProcessor;
import com.eazy.batch.enums.ReportType;
import com.eazy.batch.enums.SkipLimitMode;
import com.eazy.batch.service.BatchContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Upload with:  curl -F file=@people.xlsx -F team=sales http://localhost:8080/batch/personImport/upload
 * Template:     GET http://localhost:8080/batch/personImport/template
 */
@Slf4j
@BatchJob(
        jobName = "personImport",
        stepName = "personImportStep",
        dtoClass = PersonRow.class,
        wrapperClass = PersonBatch.class,
        reportType = ReportType.ALL,
        onSkipLimit = SkipLimitMode.CONTINUE,
        uniqueKey = {"email"}
)
public class PersonImportJob implements SimpleBatchProcessor<PersonRow, PersonBatch> {

    @Override
    public PersonBatch process(PersonRow row) {
        if (row.getAge() != null && row.getAge() > 120) {
            throw new IllegalArgumentException("Age " + row.getAge() + " is not realistic");
        }
        return new PersonBatch(List.of(row));
    }

    @Override
    public void save(List<PersonBatch> batches) {
        BatchContext ctx = BatchContext.current();
        int rows = batches.stream().mapToInt(b -> b.getRows().size()).sum();
        log.info("{} saved {} rows (team={})", ctx.username(), rows, ctx.getString("team", "-"));
    }
}
