package com.eazy.batch.report;

import com.eazy.batch.utility.BatchUtility;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers, per job execution, which uploaded rows were really imported.
 *
 * <p>Spring Batch tells us about rows that were skipped, but never about rows
 * that were fine and then rolled back together with a failed chunk, nor about
 * rows after the point where the job died. To report those honestly we track
 * the fate of every row ourselves:</p>
 * <ol>
 *   <li>the readers register each item they return together with its row
 *       number in the uploaded file;</li>
 *   <li>the generated processor and writer register a
 *       {@link TransactionSynchronization} for each item, whose
 *       {@code afterCommit} callback marks that row as <i>committed</i> -
 *       it only fires if the chunk transaction really committed;</li>
 *   <li>at the end of the job, a row that is not failed and not committed was
 *       lost to a rollback or never reached.</li>
 * </ol>
 *
 * <p>Memory stays small: only items currently in flight are held (removed on
 * commit), plus one bit per row.</p>
 */
@Slf4j
public final class BatchRowTracker {

    /** Immutable view of what was committed, taken when the job ends. */
    public record Snapshot(BitSet committedRows, int maxRowRead) {
        public boolean isCommitted(int row) {
            return committedRows.get(row);
        }
    }

    private static final class Ledger {
        /** Identity map: item (DTO or processor output) -> source row. Entries live until commit. */
        final Map<Object, Integer> rowByItem = Collections.synchronizedMap(new IdentityHashMap<>());
        final BitSet committed = new BitSet();
        volatile int maxRowRead;

        synchronized void markCommitted(int row) {
            committed.set(row);
        }

        synchronized void markCommittedThrough(int row) {
            if (row > 0) {
                committed.set(1, row + 1);
            }
        }

        synchronized Snapshot snapshot() {
            return new Snapshot((BitSet) committed.clone(), maxRowRead);
        }
    }

    private static final Cache<Long, Ledger> LEDGERS = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(1000)
            .build();

    /** entity -> the processor output (wrapper) it came from; set only while a save helper runs. */
    private static final ThreadLocal<Map<Object, Object>> ENTITY_OWNERS = new ThreadLocal<>();

    /** When set, tracking is a no-op (used while re-reading the file to build a report). */
    private static final ThreadLocal<Boolean> PAUSED = new ThreadLocal<>();

    private BatchRowTracker() {
    }

    // ── lifecycle ───────────────────────────────────────────────────

    /** Suspends tracking on this thread until {@link #resume()}. */
    public static void pause() {
        PAUSED.set(Boolean.TRUE);
    }

    public static void resume() {
        PAUSED.remove();
    }

    public static Snapshot snapshot(Long jobExecutionId) {
        if (jobExecutionId == null) {
            return null;
        }
        Ledger ledger = LEDGERS.getIfPresent(jobExecutionId);
        return ledger == null ? null : ledger.snapshot();
    }

    public static void clear(Long jobExecutionId) {
        if (jobExecutionId != null) {
            LEDGERS.invalidate(jobExecutionId);
        }
    }

    private static Ledger ledger() {
        if (Boolean.TRUE.equals(PAUSED.get())) {
            return null;
        }
        Long jobExecutionId = BatchUtility.getCurrentJobExecutionId();
        return jobExecutionId == null ? null : LEDGERS.get(jobExecutionId, k -> new Ledger());
    }

    // ── called by the readers ───────────────────────────────────────

    /** A reader returned {@code item}, which came from {@code sourceRow} of the file. */
    public static void onRead(Object item, int sourceRow) {
        Ledger ledger = ledger();
        if (ledger == null || item == null) {
            return;
        }
        ledger.rowByItem.put(item, sourceRow);
        if (sourceRow > ledger.maxRowRead) {
            ledger.maxRowRead = sourceRow;
        }
    }

    /** A reader could not parse {@code sourceRow}; records it as a failed row right away. */
    public static void onReadFailed(int sourceRow, String reason) {
        Ledger ledger = ledger();
        if (ledger == null) {
            return;
        }
        if (sourceRow > ledger.maxRowRead) {
            ledger.maxRowRead = sourceRow;
        }
        BatchUtility.addSkippedReadOnce(sourceRow, reason);
    }

    /** A restarted reader resumed after {@code throughRow}: those rows were committed by an earlier run. */
    public static void onRestart(int throughRow) {
        Ledger ledger = ledger();
        if (ledger != null) {
            ledger.markCommittedThrough(throughRow);
            if (throughRow > ledger.maxRowRead) {
                ledger.maxRowRead = throughRow;
            }
        }
    }

    // ── called by the generated processor / writer / save helpers ───

    /** @return the source row of a DTO / wrapper / entity this tracker has seen, or {@code null} */
    public static Integer rowOf(Object item) {
        if (item == null) {
            return null;
        }
        Ledger ledger = ledger();
        if (ledger == null) {
            return null;
        }
        Integer row = ledger.rowByItem.get(item);
        if (row != null) {
            return row;
        }
        Map<Object, Object> owners = ENTITY_OWNERS.get();
        if (owners != null) {
            Object owner = owners.get(item);
            if (owner != null) {
                return ledger.rowByItem.get(owner);
            }
        }
        return null;
    }

    /** The processor turned {@code input} into {@code output}: the output belongs to the same row. */
    public static void linkOutput(Object input, Object output) {
        if (input == null || output == null || input == output) {
            return;
        }
        Ledger ledger = ledger();
        if (ledger == null) {
            return;
        }
        Integer row = ledger.rowByItem.get(input);
        if (row != null) {
            ledger.rowByItem.put(output, row);
        }
    }

    /** The processor finished {@code item} without error (including "filtered out"). */
    public static void onProcessed(Object item) {
        // Without a surrounding transaction there is nothing to wait for - and
        // nothing to prove the row was saved - so don't guess: the writer
        // stage (which always has one) decides.
        registerCommit(item, false);
    }

    /** The writer is writing these processor outputs in the current transaction. */
    public static void onWritten(Collection<?> items) {
        if (items == null) {
            return;
        }
        for (Object item : items) {
            registerCommit(item, true);
        }
    }

    private static void registerCommit(Object item, boolean markWhenNoTransaction) {
        Ledger ledger = ledger();
        if (ledger == null || item == null) {
            return;
        }
        Integer row = ledger.rowByItem.get(item);
        if (row == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ledger.markCommitted(row);
                    ledger.rowByItem.remove(item);
                }
            });
        } else if (markWhenNoTransaction) {
            // No surrounding transaction to wait for.
            ledger.markCommitted(row);
            ledger.rowByItem.remove(item);
        }
    }

    /**
     * Runs {@code save} while remembering which processor output each entity
     * came from, so a failure inside {@code saveWithFallback} - which only
     * sees the entity - can still be attributed to the right row.
     *
     * @param entities the entities about to be saved
     * @param owners   for each entity (same index), the wrapper it was extracted from
     */
    public static void withOwners(List<?> entities, List<?> owners, Runnable save) {
        Map<Object, Object> map = new IdentityHashMap<>();
        for (int i = 0; i < entities.size() && i < owners.size(); i++) {
            map.put(entities.get(i), owners.get(i));
        }
        ENTITY_OWNERS.set(map);
        try {
            save.run();
        } finally {
            ENTITY_OWNERS.remove();
        }
    }
}
