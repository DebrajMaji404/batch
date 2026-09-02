package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BatchUtilityTest {

    @AfterEach
    void tearDown() {
        // Never leave a step context registered for the next test on this thread.
        try {
            StepSynchronizationManager.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * NOTE: JobInstance/JobExecution/StepExecution constructor signatures here
     * are sourced from Spring Batch's own "Unit Testing" reference docs
     * (the same pattern used in their NoWorkFoundStepExecutionListener
     * example: {@code new JobInstance(id, jobParameters, jobName)} and
     * {@code new JobExecution(jobInstance, id, jobParameters)}). If a future
     * Spring Batch release changes these constructors, this helper - not the
     * assertions below it - is the single place to update.
     */
    private StepExecution registerFakeStepContext(long jobExecutionId) {
        JobInstance jobInstance = new JobInstance(1L, new JobParameters(), "testJob");
        JobExecution jobExecution = new JobExecution(jobInstance, jobExecutionId, new JobParameters());
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);
        StepSynchronizationManager.register(stepExecution);
        return stepExecution;
    }

    // ─────────────────────────────────────────────────────────────────
    // formatDuration
    // ─────────────────────────────────────────────────────────────────

    @Test
    void formatDuration_millisOnly() {
        assertThat(BatchUtility.formatDuration(Duration.ofMillis(250))).isEqualTo("250ms");
    }

    @Test
    void formatDuration_secondsAndMillis() {
        assertThat(BatchUtility.formatDuration(Duration.ofMillis(3_500))).isEqualTo("3.500s");
    }

    @Test
    void formatDuration_minutesAndSeconds() {
        assertThat(BatchUtility.formatDuration(Duration.ofSeconds(125))).isEqualTo("2m 5s");
    }

    @Test
    void formatDuration_hoursMinutesSeconds() {
        assertThat(BatchUtility.formatDuration(Duration.ofSeconds(3725))).isEqualTo("1h 2m 5s");
    }

    // ─────────────────────────────────────────────────────────────────
    // configureCleanupTtl - just verify it doesn't throw on either input
    // ─────────────────────────────────────────────────────────────────

    @Test
    void configureCleanupTtl_acceptsPositiveValue() {
        BatchUtility.configureCleanupTtl(48);
        // No exception + no observable getter, but this at minimum exercises the code path.
    }

    @Test
    void configureCleanupTtl_ignoresInvalidValue() {
        BatchUtility.configureCleanupTtl(0);
        BatchUtility.configureCleanupTtl(-5);
        // Should log a warning and leave the previous TTL untouched, not throw.
    }

    // ─────────────────────────────────────────────────────────────────
    // Skip tracking (via explicit jobExecutionId overloads, and via the
    // StepSynchronizationManager-backed "current job" overloads)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void addSkippedItem_recordsUnderCurrentStepContext() {
        registerFakeStepContext(1001L);

        BatchUtility.addSkippedItem("bad-row", "READ", "malformed CSV row");

        List<BatchSkippedItem<?>> items = BatchUtility.getSkippedItems(1001L);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getItem()).isEqualTo("bad-row");
        assertThat(items.get(0).getPhase()).isEqualTo("READ");
        assertThat(items.get(0).getReason()).isEqualTo("malformed CSV row");
    }

    @Test
    void addSkippedItem_withoutStepContext_isSilentlyDropped() {
        // No StepSynchronizationManager.register() call - simulates calling
        // this outside of an active step execution.
        BatchUtility.addSkippedItem("orphan", "READ", "no context");

        assertThat(BatchUtility.getSkippedItems()).isEmpty();
    }

    @Test
    void getSkippedItemCount_matchesNumberOfAddedItems() {
        registerFakeStepContext(1002L);

        BatchUtility.addSkippedItem("a", "READ", "r1");
        BatchUtility.addSkippedItem("b", "PROCESS", "r2");
        BatchUtility.addSkippedItem("c", "WRITE", "r3");

        assertThat(BatchUtility.getSkippedItemCount(1002L)).isEqualTo(3);
        assertThat(BatchUtility.getSkippedItemCount()).isEqualTo(3);
    }

    @Test
    void clearSkippedItems_removesAllItemsForJob() {
        registerFakeStepContext(1003L);
        BatchUtility.addSkippedItem("a", "READ", "r1");
        assertThat(BatchUtility.getSkippedItemCount(1003L)).isEqualTo(1);

        BatchUtility.clearSkippedItems(1003L);

        assertThat(BatchUtility.getSkippedItemCount(1003L)).isZero();
    }

    @Test
    void getSkippedItems_withNullJobExecutionId_returnsEmptyList() {
        assertThat(BatchUtility.getSkippedItems((Long) null)).isEmpty();
    }

    @Test
    void getSkippedItemsByType_filtersByPhase() {
        registerFakeStepContext(1004L);
        BatchUtility.addSkippedItem("a", "READ", "r1");
        BatchUtility.addSkippedItem("b", "WRITE", "r2");
        BatchUtility.addSkippedItem("c", "READ", "r3");

        List<BatchSkippedItem<?>> readOnly = BatchUtility.getSkippedItemsByType("READ");

        assertThat(readOnly).hasSize(2);
        assertThat(readOnly).allSatisfy(i -> assertThat(i.getPhase()).isEqualTo("READ"));
    }

    // ─────────────────────────────────────────────────────────────────
    // saveWithFallback
    // ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void saveWithFallback_bulkSaveSucceeds_doesNotFallBackToIndividualSaves() {
        JpaRepository<String, ?> repository = mock(JpaRepository.class);
        List<String> entities = List.of("a", "b", "c");

        BatchUtility.saveWithFallback(entities, repository);

        verify(repository, times(1)).saveAll(entities);
        verify(repository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void saveWithFallback_bulkSaveFails_fallsBackAndTracksIndividualFailures() {
        registerFakeStepContext(1005L);

        JpaRepository<String, ?> repository = mock(JpaRepository.class);
        doThrow(new RuntimeException("bulk insert failed")).when(repository).saveAll(anyList());
        // "bad" fails individually, the rest succeed
        doThrow(new RuntimeException("constraint violation")).when(repository).save("bad");

        List<String> entities = List.of("good1", "bad", "good2");

        BatchUtility.saveWithFallback(entities, repository);

        verify(repository, times(1)).save("good1");
        verify(repository, times(1)).save("bad");
        verify(repository, times(1)).save("good2");

        // FIXED (earlier review): individual failures inside saveWithFallback
        // are recorded via addSkippedItem, since they can never trigger
        // Spring Batch's own SkipListener.onSkipInWrite.
        List<BatchSkippedItem<?>> skipped = BatchUtility.getSkippedItems(1005L);
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).getItem()).isEqualTo("bad");
        assertThat(skipped.get(0).getPhase()).isEqualTo("WRITE");
    }

    @SuppressWarnings("unchecked")
    @Test
    void saveWithFallback_emptyList_doesNothing() {
        JpaRepository<String, ?> repository = mock(JpaRepository.class);

        BatchUtility.saveWithFallback(List.of(), repository);

        verifyNoInteractions(repository);
    }
}
