package com.eazy.batch.utility;

import com.eazy.batch.dto.BatchSkippedItem;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for batch operations
 */
@Slf4j
public class BatchUtility {

    /**
     * Store skipped items per job execution ID
     * This allows multiple batch jobs to run concurrently without interfering with each other
     */
    private static final Map<Long, List<BatchSkippedItem<Object>>> skippedItemsByJobId =
            new ConcurrentHashMap<>();

    /**
     * ThreadLocal to store current job execution ID for the current thread
     */
    private static @Nullable Long getJobExecutionId() {
        try {
            StepExecution stepExecution = Objects.requireNonNull(StepSynchronizationManager.getContext()).getStepExecution();
            stepExecution.getJobExecution();
            return stepExecution.getJobExecution().getId();
        } catch (Exception e) {
            log.error("Failed to get jobExecutionId from StepSynchronizationManager: {}", e.getMessage());
        }

        log.warn("JobExecutionId is null - cannot track skipped items");
        return null;
    }

//    /**
//     * Set the current job execution ID for this thread
//     * Call this at the start of job execution
//     *
//     * @param jobExecutionId The job execution ID
//     */
//    public static void setCurrentJobExecutionId(Long jobExecutionId) {
//        currentJobExecutionId.set(jobExecutionId);
//        skippedItemsByJobId.putIfAbsent(jobExecutionId, new ArrayList<>());
//        log.debug("Set job execution ID: {} for thread: {}", jobExecutionId, Thread.currentThread().getName());
//    }

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
        Long jobExecutionId =getJobExecutionId();
        if (jobExecutionId == null) {
            log.warn("No job execution ID set for current thread. Skipped item will not be tracked.");
            return;
        }

        BatchSkippedItem<Object> skippedItem = new BatchSkippedItem<>();
        skippedItem.setItem(item);
        skippedItem.setPhase(phase);
        skippedItem.setReason(reason);

        skippedItemsByJobId.computeIfAbsent(jobExecutionId, k -> new ArrayList<>()).add(skippedItem);

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
        Long jobExecutionId =getJobExecutionId();
        if (jobExecutionId == null) {
            log.warn("No job execution ID set for current thread. Skipped item will not be tracked.");
            return;
        }

        BatchSkippedItem<Object> skippedItem = new BatchSkippedItem<>();
        skippedItem.setItem(item);
        skippedItem.setPhase(errorType);
        skippedItem.setReason(errorMessage);

        skippedItemsByJobId.computeIfAbsent(jobExecutionId, k -> new ArrayList<>()).add(skippedItem);
    }

    /**
     * Get a skipped item if it exists for the current job
     */
    public static <T> @Nullable BatchSkippedItem<Object> getSkippedItem(T item, String phase, String reason) {
        Long jobExecutionId =getJobExecutionId();
        if (jobExecutionId == null) {
            return null;
        }

        List<BatchSkippedItem<Object>> items = skippedItemsByJobId.get(jobExecutionId);
        if (items == null) {
            return null;
        }

        return items.stream()
                .filter(si -> {
                    boolean itemMatches = (si.getItem() == null && item == null) ||
                            (si.getItem() != null && si.getItem().equals(item));
                    boolean phaseMatches = si.getPhase() != null && si.getPhase().equals(phase);
                    return itemMatches && phaseMatches;
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all skipped items for the current job
     */
    @Contract(" -> new")
    public static @NotNull List<BatchSkippedItem<Object>> getSkippedItems() {
        Long jobExecutionId =getJobExecutionId();
        if (jobExecutionId == null) {
            log.warn("No job execution ID set for current thread. Returning empty list.");
            return new ArrayList<>();
        }
        return new ArrayList<>(skippedItemsByJobId.getOrDefault(jobExecutionId, new ArrayList<>()));
    }

    /**
     * Get all skipped items for a specific job execution ID
     *
     * @param jobExecutionId The job execution ID
     * @return List of skipped items for that job
     */
    @Contract("_ -> new")
    public static @NotNull List<BatchSkippedItem<Object>> getSkippedItems(Long jobExecutionId) {
        if (jobExecutionId == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(skippedItemsByJobId.getOrDefault(jobExecutionId, new ArrayList<>()));
    }

    /**
     * Clear skipped items for the current job
     */
    public static void clearSkippedItems() {
        Long jobExecutionId =getJobExecutionId();
        if (jobExecutionId != null) {
            skippedItemsByJobId.remove(jobExecutionId);
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
            skippedItemsByJobId.remove(jobExecutionId);
            log.debug("Cleared skipped items for job execution ID: {}", jobExecutionId);
        }
    }

    /**
     * Get count of skipped items for the current job
     */
    public static int getSkippedItemCount() {
        Long jobExecutionId =getJobExecutionId();
        if (jobExecutionId == null) {
            return 0;
        }
        List<BatchSkippedItem<Object>> items = skippedItemsByJobId.get(jobExecutionId);
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
        List<BatchSkippedItem<Object>> items = skippedItemsByJobId.get(jobExecutionId);
        return items != null ? items.size() : 0;
    }

    /**
     * Get skipped items by error type for the current job
     */
    public static List<BatchSkippedItem<Object>> getSkippedItemsByType(String errorType) {
        return getSkippedItems().stream()
                .filter(item -> errorType.equals(item.getPhase()))
                .toList();
    }

    /**
     * Clean up old job data (call this periodically to prevent memory leaks)
     * Removes data for jobs that completed more than the specified hours ago
     *
     * @param olderThanHours Remove data older than this many hours
     */
    public static void cleanupOldJobData(int olderThanHours) {
        // This is a simple cleanup - in production, you might want to track timestamps
        // For now, we'll just log a warning if the map gets too large
        if (skippedItemsByJobId.size() > 100) {
            log.warn("Skipped items map has {} entries. Consider cleaning up old job data.",
                    skippedItemsByJobId.size());
        }
    }

    /**
     * Get all active job execution IDs (for monitoring)
     */
    @Contract(" -> new")
    public static @NotNull List<Long> getActiveJobExecutionIds() {
        return new ArrayList<>(skippedItemsByJobId.keySet());
    }
    /**
     * Save entities with fallback to individual saves on batch failure
     */
    public static <E> void saveWithFallback(@NotNull List<E> entities, JpaRepository<E, ?> repository) {
        if (entities.isEmpty()) {
            return;
        }

        try {
            // Try bulk save first
            repository.saveAll(entities);
            log.info("Successfully saved {} entities in bulk", entities.size());
        } catch (Exception e) {
            log.warn("Bulk save failed, falling back to individual saves: {}", e.getMessage());

            // Fallback to individual saves
            int successCount = 0;
            int failCount = 0;

            for (E entity : entities) {
                try {
                    repository.save(entity);
                    successCount++;
                } catch (Exception ex) {
                    failCount++;
                    log.error("Failed to save entity: {}", ex.getMessage());
                }
            }

            log.info("Individual save completed: {} succeeded, {} failed", successCount, failCount);
        }
    }
}