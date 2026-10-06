package com.eazy.batch.report;

import com.eazy.batch.dto.BatchSkippedItem;
import com.eazy.batch.utility.BatchUtility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRowTrackerTest {

    private static final long JOB_ID = 7001L;

    /** Plain stand-ins; distinct instances so identity tracking is what's being exercised. */
    static final class Dto {
    }

    static final class Wrapper {
    }

    static final class Entity {
    }

    @AfterEach
    void tearDown() {
        try {
            StepSynchronizationManager.close();
        } catch (Exception ignored) {
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        BatchRowTracker.clear(JOB_ID);
        BatchUtility.clearSkippedItems(JOB_ID);
        BatchRowTracker.resume();
    }

    private void registerStepContext() {
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution("trackerJob", 1L, JOB_ID);
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution(jobExecution, "trackerStep", 1L);
        StepSynchronizationManager.register(stepExecution);
    }

    /** Fires the registered synchronizations the way a finished transaction would. */
    private void finishTransaction(boolean committed) {
        List<TransactionSynchronization> syncs = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        for (TransactionSynchronization sync : syncs) {
            if (committed) {
                sync.afterCommit();
            }
            sync.afterCompletion(committed
                    ? TransactionSynchronization.STATUS_COMMITTED
                    : TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    @Test
    void outputsInheritTheRowOfTheirInput() {
        registerStepContext();
        Dto dto = new Dto();
        Wrapper wrapper = new Wrapper();

        BatchRowTracker.onRead(dto, 7);
        BatchRowTracker.linkOutput(dto, wrapper);

        assertThat(BatchRowTracker.rowOf(dto)).isEqualTo(7);
        assertThat(BatchRowTracker.rowOf(wrapper)).isEqualTo(7);
        assertThat(BatchRowTracker.rowOf(new Dto())).isNull();
    }

    @Test
    void rowCountsAsCommittedOnlyAfterTheTransactionCommits() {
        registerStepContext();
        Dto dto = new Dto();
        BatchRowTracker.onRead(dto, 5);

        TransactionSynchronizationManager.initSynchronization();
        BatchRowTracker.onProcessed(dto);

        // Registered, but the chunk has not committed yet.
        assertThat(BatchRowTracker.snapshot(JOB_ID).isCommitted(5)).isFalse();

        finishTransaction(true);

        assertThat(BatchRowTracker.snapshot(JOB_ID).isCommitted(5)).isTrue();
    }

    @Test
    void rolledBackRowIsNeverCounted() {
        registerStepContext();
        Dto dto = new Dto();
        BatchRowTracker.onRead(dto, 5);

        TransactionSynchronizationManager.initSynchronization();
        BatchRowTracker.onProcessed(dto);
        finishTransaction(false);

        assertThat(BatchRowTracker.snapshot(JOB_ID).isCommitted(5)).isFalse();
        // ...but the tracker still knows the row was read.
        assertThat(BatchRowTracker.snapshot(JOB_ID).maxRowRead()).isEqualTo(5);
    }

    @Test
    void writtenWrappersCountWhenTheirTransactionCommits() {
        registerStepContext();
        Dto dto = new Dto();
        Wrapper wrapper = new Wrapper();
        BatchRowTracker.onRead(dto, 9);
        BatchRowTracker.linkOutput(dto, wrapper);

        TransactionSynchronizationManager.initSynchronization();
        BatchRowTracker.onWritten(List.of(wrapper));
        finishTransaction(true);

        assertThat(BatchRowTracker.snapshot(JOB_ID).isCommitted(9)).isTrue();
    }

    @Test
    void entitiesAreAttributedToTheRowOfTheWrapperTheyCameFrom() {
        registerStepContext();
        Dto dto = new Dto();
        Wrapper wrapper = new Wrapper();
        Entity entity = new Entity();
        BatchRowTracker.onRead(dto, 12);
        BatchRowTracker.linkOutput(dto, wrapper);

        List<Integer> seenInside = new ArrayList<>();
        BatchRowTracker.withOwners(List.of(entity), List.of(wrapper), () -> {
            seenInside.add(BatchRowTracker.rowOf(entity));
            // A failed save is reported with the row it belongs to.
            BatchUtility.addSkippedItem(entity, "WRITE", "constraint violation");
        });

        assertThat(seenInside).containsExactly(12);
        assertThat(BatchRowTracker.rowOf(entity)).isNull(); // owners only live inside the call

        List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems(JOB_ID);
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).getRowNumber()).isEqualTo(12);
    }

    @Test
    void restartMarksEarlierRowsCommitted() {
        registerStepContext();

        BatchRowTracker.onRestart(40);

        BatchRowTracker.Snapshot snapshot = BatchRowTracker.snapshot(JOB_ID);
        assertThat(snapshot.isCommitted(1)).isTrue();
        assertThat(snapshot.isCommitted(40)).isTrue();
        assertThat(snapshot.isCommitted(41)).isFalse();
    }

    @Test
    void readFailureIsRecordedOncePerRow() {
        registerStepContext();

        // The reader records it at throw time, the SkipListener again on commit.
        BatchRowTracker.onReadFailed(8, "Error parsing row 8: bad date");
        BatchUtility.addSkippedReadOnce(8, "Error parsing row 8: bad date");

        List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems(JOB_ID);
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).getPhase()).isEqualTo("READ");
        assertThat(skipped.get(0).getRowNumber()).isEqualTo(8);
    }

    @Test
    void skipsCarryTheRowOfTheirItem() {
        registerStepContext();
        Dto dto = new Dto();
        BatchRowTracker.onRead(dto, 3);

        BatchUtility.addSkippedItemOnce(dto, "PROCESS", "boom");
        BatchUtility.addSkippedItemOnce(dto, "PROCESS", "boom"); // rescan repeat

        List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems(JOB_ID);
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).getRowNumber()).isEqualTo(3);
    }

    @Test
    void nothingIsTrackedWhilePaused() {
        registerStepContext();
        Dto dto = new Dto();

        BatchRowTracker.pause();
        BatchRowTracker.onRead(dto, 2);
        BatchRowTracker.resume();

        assertThat(BatchRowTracker.rowOf(dto)).isNull();
    }
}
