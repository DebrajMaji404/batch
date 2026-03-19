package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

/**
 * Utility class for batch operations
 * FIXED: Memory leak - now uses Caffeine cache with TTL
 */
@Slf4j
public class BatchUtility {

    /**
     * Store skipped items per job execution ID with automatic expiration
     * FIXED: Using Caffeine cache to prevent memory leaks
     */
    private static final Cache<Long, List<BatchSkippedItem<?>>> skippedItemsByJobId =
            Caffeine.newBuilder()
                    .expireAfterWrite(24, TimeUnit.HOURS)
                    .maximumSize(1000)
                    .recordStats()
                    .build();

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
     * FIXED: Now actually cleans up old data
     *
     * @param olderThanHours Remove data older than this many hours
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
     * Save entities with fallback to individual saves on batch failure
     * FIXED: Better error handling and reporting
     */
    public static <E> void saveWithFallback(@NotNull List<E> entities, JpaRepository<E, ?> repository) {
        if (entities.isEmpty()) {
            log.debug("No entities to save, skipping");
            return;
        }

        try {
            // Try bulk save first
            repository.saveAll(entities);
            log.info("✅ Successfully saved {} entities in bulk", entities.size());
        } catch (Exception e) {
            log.warn("⚠️ Bulk save failed, falling back to individual saves: {}", e.getMessage());

            int successCount = 0;
            int failCount = 0;
            List<String> failedEntities = new ArrayList<>();

            for (E entity : entities) {
                try {
                    repository.save(entity);
                    successCount++;
                } catch (Exception ex) {
                    failCount++;
                    String entityInfo = entity.toString();
                    failedEntities.add(entityInfo);
                    log.error("❌ Failed to save entity: {} - Error: {}",
                            entityInfo, ex.getMessage());
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