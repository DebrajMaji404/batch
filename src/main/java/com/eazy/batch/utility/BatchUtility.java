package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class for batch operations
 * FIXED: Memory leak - now uses Caffeine cache with TTL
 */
@Slf4j
public class BatchUtility {

    /**
     * FIXED: cleanupAfterHours from eazy.batch.cleanup-after-hours was
     * previously ignored - the TTL below was hardcoded to 24 hours. It's now
     * read dynamically on every entry via Expiry, and can be changed at
     * runtime with configureCleanupTtl(), which BatchProcessorAutoConfiguration
     * calls once at startup with the configured property value.
     */
    private static final AtomicLong ttlNanos = new AtomicLong(TimeUnit.HOURS.toNanos(24));

    /**
     * Store skipped items per job execution ID with automatic expiration
     * FIXED: Using Caffeine cache to prevent memory leaks
     */
    private static final Cache<Long, List<BatchSkippedItem<?>>> skippedItemsByJobId =
            Caffeine.newBuilder()
                    .expireAfter(new Expiry<Long, List<BatchSkippedItem<?>>>() {
                        @Override
                        public long expireAfterCreate(@NotNull Long key, @NotNull List<BatchSkippedItem<?>> value, long currentTime) {
                            return ttlNanos.get();
                        }

                        @Override
                        public long expireAfterUpdate(@NotNull Long key, @NotNull List<BatchSkippedItem<?>> value, long currentTime, long currentDuration) {
                            return currentDuration;
                        }

                        @Override
                        public long expireAfterRead(@NotNull Long key, @NotNull List<BatchSkippedItem<?>> value, long currentTime, long currentDuration) {
                            return currentDuration;
                        }
                    })
                    .maximumSize(1000)
                    .recordStats()
                    .build();

    /**
     * Configure how long skipped-item data is retained before automatic
     * expiration. Intended to be called once at application startup from
     * eazy.batch.cleanup-after-hours.
     *
     * @param hours Hours to retain data for (values <= 0 are ignored)
     */
    public static void configureCleanupTtl(int hours) {
        if (hours <= 0) {
            log.warn("Ignoring invalid cleanup-after-hours value: {}. Keeping current TTL.", hours);
            return;
        }
        ttlNanos.set(TimeUnit.HOURS.toNanos(hours));
        log.info("Skipped-item cache TTL configured to {} hour(s)", hours);
    }

    /**
     * Get the current job execution ID from StepSynchronizationManager
     */
    private static @Nullable Long getJobExecutionId() {
        try {
            StepExecution stepExecution = Objects.requireNonNull(
                    StepSynchronizationManager.getContext()
            ).getStepExecution();
            return stepExecution.getJobExecution().getId();
        } catch (Exception e) {
            log.debug("Failed to get jobExecutionId from StepSynchronizationManager: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the current job execution ID for this thread
     *
     * @return Current job execution ID
     */
    public static Long getCurrentJobExecutionId() {
        return getJobExecutionId();
    }

    /**
     * Add a skipped item to the current job's list
     *
     * @param item The skipped item
     * @param phase The phase where skip occurred (READ, PROCESS, WRITE)
     * @param reason The reason for skipping
     */
    public static <T> void addSkippedItem(T item, String phase, String reason) {
        Long jobExecutionId = getJobExecutionId();
        if (jobExecutionId == null) {
            log.warn("No job execution ID available. Skipped item will not be tracked.");
            return;
        }

        BatchSkippedItem<T> skippedItem = new BatchSkippedItem<>(item, phase, reason);

        List<BatchSkippedItem<?>> items = skippedItemsByJobId.get(
                jobExecutionId,
                k -> new ArrayList<>()
        );
        items.add(skippedItem);

        log.debug("Added skipped item to job {}: {} - {}", jobExecutionId, phase, reason);
    }

    /**
     * Records a skipped item at THROW time, de-duplicated by object identity.
     *
     * <p>Spring Batch only fires SkipListener callbacks when a chunk
     * successfully commits. If a step dies mid-chunk - most commonly on
     * SkipLimitExceededException - that chunk rolls back and its listener
     * callbacks never fire, so nothing was ever recorded here and the final
     * error report comes out empty even though skipCount is non-zero. The
     * generated ItemProcessor therefore calls this directly from its catch
     * block, before rethrowing, so the record survives an aborted chunk.</p>
     *
     * <p>That creates a double-counting risk: when a chunk fails, Spring
     * Batch re-processes its items ONE AT A TIME to isolate the culprit, so
     * a failing item's process() runs more than once. De-duplication is by
     * reference equality (==) rather than equals()/toString() on purpose -
     * the rescan hands back the very same item instances from the chunk, so
     * identity matches exactly the repeats we want to collapse, while two
     * genuinely identical-but-distinct rows in the file remain distinct
     * instances and are both reported. Items that are null (READ-phase
     * failures, where nothing was parsed) are never identity-deduped, since
     * every null would otherwise collapse into one.</p>
     */
    public static <T> void addSkippedItemOnce(T item, String phase, String reason) {
        Long jobExecutionId = getJobExecutionId();
        if (jobExecutionId == null) {
            log.warn("No job execution ID available. Skipped item will not be tracked.");
            return;
        }

        List<BatchSkippedItem<?>> items = skippedItemsByJobId.get(
                jobExecutionId,
                k -> new ArrayList<>()
        );

        synchronized (items) {
            if (item != null) {
                for (BatchSkippedItem<?> existing : items) {
                    if (existing.getItem() == item && phase.equals(existing.getPhase())) {
                        log.trace("Skip already recorded for this item instance in phase {} - not duplicating", phase);
                        return;
                    }
                }
            }
            items.add(new BatchSkippedItem<>(item, phase, reason));
        }

        log.debug("Recorded skipped item for job {}: {} - {}", jobExecutionId, phase, reason);
    }

    /**
     * Add a skipped item with detailed error information
     *
     * @param item The skipped item (DTO)
     * @param errorType The error type/phase
     * @param errorMessage The detailed error message
     */
    public static <T> void addSkippedItemWithError(T item, String errorType, String errorMessage) {
        addSkippedItem(item, errorType, errorMessage);
    }

    /**
     * Get a skipped item if it exists for the current job
     */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable BatchSkippedItem<T> getSkippedItem(T item, String phase, String reason) {
        Long jobExecutionId = getJobExecutionId();
        if (jobExecutionId == null) {
            return null;
        }

        List<BatchSkippedItem<?>> items = skippedItemsByJobId.getIfPresent(jobExecutionId);
        if (items == null) {
            return null;
        }

        return (BatchSkippedItem<T>) items.stream()
                .filter(si -> {
                    boolean dtoMatches = Objects.equals(si.getItem(), item);
                    boolean errorTypeMatches = Objects.equals(si.getPhase(), phase);
                    boolean errorMessageMatches = Objects.equals(si.getReason(), reason);
                    return dtoMatches && errorTypeMatches && errorMessageMatches;
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all skipped items for the current job
     */
    @Contract(" -> new")
    public static @NotNull List<BatchSkippedItem<?>> getSkippedItems() {
        Long jobExecutionId = getJobExecutionId();
        if (jobExecutionId == null) {
            log.warn("No job execution ID available. Returning empty list.");
            return new ArrayList<>();
        }

        List<BatchSkippedItem<?>> items = skippedItemsByJobId.getIfPresent(jobExecutionId);
        return items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    /**
     * Get all skipped items for a specific job execution ID
     *
     * @param jobExecutionId The job execution ID
     * @return List of skipped items for that job
     */
    @Contract("_ -> new")
    public static @NotNull List<BatchSkippedItem<?>> getSkippedItems(Long jobExecutionId) {
        if (jobExecutionId == null) {
            return new ArrayList<>();
        }

        List<BatchSkippedItem<?>> items = skippedItemsByJobId.getIfPresent(jobExecutionId);
        return items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    /**
     * Clear skipped items for the current job
     */
    public static void clearSkippedItems() {
        Long jobExecutionId = getJobExecutionId();
        if (jobExecutionId != null) {
            skippedItemsByJobId.invalidate(jobExecutionId);
            log.debug("Cleared skipped items for job execution ID: {}", jobExecutionId);
        }
    }

    /**
     * Clear skipped items for a specific job execution ID
     *
     * @param jobExecutionId The job execution ID
     */
    public static void clearSkippedItems(Long jobExecutionId) {
        if (jobExecutionId != null) {
            skippedItemsByJobId.invalidate(jobExecutionId);
            log.debug("Cleared skipped items for job execution ID: {}", jobExecutionId);
        }
    }

    /**
     * Get count of skipped items for the current job
     */
    public static int getSkippedItemCount() {
        Long jobExecutionId = getJobExecutionId();
        if (jobExecutionId == null) {
            return 0;
        }

        List<BatchSkippedItem<?>> items = skippedItemsByJobId.getIfPresent(jobExecutionId);
        return items != null ? items.size() : 0;
    }

    /**
     * Get count of skipped items for a specific job
     *
     * @param jobExecutionId The job execution ID
     * @return Count of skipped items
     */
    public static int getSkippedItemCount(Long jobExecutionId) {
        if (jobExecutionId == null) {
            return 0;
        }

        List<BatchSkippedItem<?>> items = skippedItemsByJobId.getIfPresent(jobExecutionId);
        return items != null ? items.size() : 0;
    }

    /**
     * Get skipped items by error type for the current job
     */
    public static List<BatchSkippedItem<?>> getSkippedItemsByType(String errorType) {
        return getSkippedItems().stream()
                .filter(item -> errorType.equals(item.getPhase()))
                .toList();
    }

    /**
     * Clean up old job data
     * FIXED: TTL is now driven by configureCleanupTtl() / eazy.batch.cleanup-after-hours
     * instead of the previously-hardcoded 24h. This method just forces Caffeine
     * to proactively evict anything past that TTL rather than waiting for it
     * to happen lazily on next access.
     *
     * @param olderThanHours Unused - retained for backward source compatibility.
     *                        The actual retention window is set once via configureCleanupTtl().
     */
    public static void cleanupOldJobData(int olderThanHours) {
        skippedItemsByJobId.cleanUp();
        log.info("Cleaned up expired job data from cache. Current size: {}",
                skippedItemsByJobId.estimatedSize());
    }

    /**
     * Get all active job execution IDs (for monitoring)
     */
    @Contract(" -> new")
    public static @NotNull List<Long> getActiveJobExecutionIds() {
        return new ArrayList<>(skippedItemsByJobId.asMap().keySet());
    }

    /**
     * Get cache statistics
     */
    public static String getCacheStats() {
        var stats = skippedItemsByJobId.stats();
        return String.format(
                "Cache Stats - Size: %d, Hits: %d, Misses: %d, Evictions: %d",
                skippedItemsByJobId.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.evictionCount()
        );
    }

    /**
     * Save entities with fallback to individual saves on batch failure.
     *
     * <p><b>Known limitation:</b> the fallback loop calls {@code saveAndFlush()}
     * per entity so a constraint violation is caught and attributed to the
     * specific entity that caused it, rather than surfacing later (deferred
     * to commit time) where it's impossible to tell which entity was at
     * fault. However, all saves in this loop still share the same
     * transaction (Spring Batch's chunk-scoped transaction around the
     * whole write() call) - depending on your JPA provider, a failed flush
     * can mark that shared transaction rollback-only, in which case every
     * subsequent saveAndFlush() call in the same loop (i.e. the rest of
     * this chunk) will also fail, even for otherwise-valid entities. A
     * smaller chunkSize limits how many unrelated rows can be caught in
     * that blast radius. Fully isolating each fallback save in its own
     * transaction (e.g. via REQUIRES_NEW propagation) would avoid this,
     * but requires a proper Spring-managed bean rather than a static
     * utility method - out of scope for this fix.
     */
    public static <E> void saveWithFallback(@NotNull List<E> entities, JpaRepository<E, ?> repository) {
        if (entities.isEmpty()) {
            log.debug("No entities to save, skipping");
            return;
        }

        try {
            // Try bulk save first
            repository.saveAll(entities);
            // FIXED: repository.saveAll() maps to entityManager.persist()
            // per entity, which by default defers actually executing the
            // INSERT SQL until the next flush - normally at transaction
            // commit time, which happens AFTER this method has already
            // returned. That means a constraint violation (NOT NULL, FK,
            // unique, etc.) would surface well outside this try/catch,
            // deep inside Spring Batch's own chunk-commit machinery -
            // never triggering the individual-save fallback below, and
            // never getting recorded via addSkippedItem, no matter how
            // real the failure was. Flushing here forces Hibernate to
            // actually execute the batched INSERTs now, while we're still
            // inside the try block that can catch and fall back on them.
            repository.flush();
            log.info("✅ Successfully saved {} entities in bulk", entities.size());
        } catch (Exception e) {
            log.warn("⚠️ Bulk save failed, falling back to individual saves: {}", e.getMessage());

            int successCount = 0;
            int failCount = 0;
            List<String> failedEntities = new ArrayList<>();

            for (E entity : entities) {
                try {
                    // FIXED: same reasoning as above - saveAndFlush (rather
                    // than save) forces this specific entity's INSERT to
                    // execute right now, so a constraint violation is
                    // caught and attributed to THIS entity specifically,
                    // instead of surfacing later at commit time where it's
                    // impossible to tell which of the batch's entities
                    // actually caused it.
                    repository.saveAndFlush(entity);
                    successCount++;
                } catch (Exception ex) {
                    failCount++;
                    String entityInfo = entity.toString();
                    failedEntities.add(entityInfo);
                    log.error("❌ Failed to save entity: {} - Error: {}",
                            entityInfo, ex.getMessage());
                    // FIXED: record the failure through the same skip-tracking
                    // mechanism used by the SkipListener, since this failure
                    // happens inside the writer's business logic and can never
                    // reach Spring Batch's own onSkipInWrite callback.
                    addSkippedItem(entity, "WRITE", ex.getMessage());
                }
            }

            log.info("Individual save completed: ✅ {} succeeded, ❌ {} failed",
                    successCount, failCount);

            if (failCount > 0) {
                log.error("Failed entities: {}", failedEntities);
            }
        }
    }

    /**
     * Format duration in human-readable format
     */
    public static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        long millis = duration.toMillis() % 1000;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else if (secs > 0) {
            return String.format("%d.%03ds", secs, millis);
        } else {
            return String.format("%dms", millis);
        }
    }
}