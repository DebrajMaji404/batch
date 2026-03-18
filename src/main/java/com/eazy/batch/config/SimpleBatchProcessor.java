package com.eazy.batch.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static com.eazy.batch.utility.BatchUtility.saveWithFallback;

/**
 * Simplified base interface for batch processing.
 * Annotate implementing classes with @BatchJob to auto-generate Spring Batch beans.
 *
 * @param <DTO> The input DTO type to be processed
 * @param <WRAPPER> The output wrapper type after processing
 */
public interface SimpleBatchProcessor<DTO, WRAPPER> {

    /**
     * Implement your business logic here.
     * This method processes a single DTO and returns a wrapper.
     *
     * @param dto The input DTO to process
     * @return The processed wrapper object
     * @throws Exception if processing fails
     */
    WRAPPER process(DTO dto) throws Exception;

    /**
     * Implement your save logic here.
     * This method is called with a chunk of processed wrappers.
     *
     * @param wrappers List of processed wrappers to save
     */
    void save(List<WRAPPER> wrappers);

    /**
     * Optional: Override to provide custom identifier for logging
     * Default: "item"
     *
     * @param item The item to get identifier for
     * @return String identifier for logging
     */
    default String getIdentifier(Object item) {
        return "item: " + (item != null ? item.toString() : "null");
    }

    // ========================================================================
    // NEW: LIFECYCLE HOOKS
    // ========================================================================

    /**
     * Optional: Pre-processing hook called before process()
     * Use for data normalization, cleaning, or validation
     *
     * @param dto The input DTO
     * @return Modified DTO or same DTO
     */
    default DTO preProcess(DTO dto) {
        return dto;
    }

    /**
     * Optional: Post-processing hook called after process()
     * Use for additional transformations or enrichment
     *
     * @param wrapper The processed wrapper
     * @return Modified wrapper or same wrapper
     */
    default WRAPPER postProcess(WRAPPER wrapper) {
        return wrapper;
    }

    /**
     * Optional: Conditional processing filter
     * Return false to skip processing this item without error
     *
     * @param dto The input DTO
     * @return true to process, false to skip
     */
    default boolean shouldProcess(DTO dto) {
        return true;
    }

    /**
     * Optional: Custom validation logic beyond Jakarta validation
     * Return null or empty list if valid, otherwise return validation errors
     *
     * @param dto The input DTO
     * @return List of validation error messages (empty if valid)
     */
    default List<String> customValidate(DTO dto) {
        return List.of();
    }

    /**
     * Optional: Called when job starts
     * Use for initialization, setup, or logging
     */
    default void onJobStart() {
        // Override to add custom logic
    }

    /**
     * Optional: Called when job completes successfully
     * Use for cleanup, reporting, or notifications
     *
     * @param itemsProcessed Total items processed
     * @param itemsSkipped Total items skipped
     */
    default void onJobComplete(long itemsProcessed, long itemsSkipped) {
        // Override to add custom logic
    }

    /**
     * Optional: Called when job fails
     * Use for error handling, alerts, or rollback
     *
     * @param error The exception that caused the failure
     */
    default void onJobFailure(Throwable error) {
        // Override to add custom logic
    }

    // ========================================================================
    // HELPER METHODS FOR SAVE LOGIC
    // ========================================================================

    /**
     * Helper to extract and save single entities.
     * Use this when your wrapper contains a single entity.
     *
     * Example:
     * <pre>
     * {@code
     * extractAndSave(wrappers, MyWrapper::getEntity, entityRepository);
     * }
     * </pre>
     *
     * @param wrappers List of wrappers
     * @param extractor Function to extract entity from wrapper
     * @param repository JPA repository to save entities
     * @param <E> Entity type
     */
    default <E> void extractAndSave(
            List<WRAPPER> wrappers,
            Function<WRAPPER, E> extractor,
            JpaRepository<E, ?> repository) {

        List<E> entities = wrappers.stream()
                .filter(Objects::nonNull)
                .map(extractor)
                .filter(Objects::nonNull)
                .toList();

        saveWithFallback(entities, repository);
    }

    /**
     * Helper to extract and save lists of entities (flat map).
     * Use this when your wrapper contains a list of entities.
     *
     * Example:
     * <pre>
     * {@code
     * extractAndSaveFlat(wrappers, MyWrapper::getEntities, entityRepository);
     * }
     * </pre>
     *
     * @param wrappers List of wrappers
     * @param extractor Function to extract list of entities from wrapper
     * @param repository JPA repository to save entities
     * @param <E> Entity type
     */
    default <E> void extractAndSaveFlat(
            List<WRAPPER> wrappers,
            Function<WRAPPER, List<E>> extractor,
            JpaRepository<E, ?> repository) {

        List<E> entities = wrappers.stream()
                .filter(Objects::nonNull)
                .map(extractor)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .toList();

        saveWithFallback(entities, repository);
    }

    /**
     * Helper to conditionally save entities based on a predicate
     *
     * Example:
     * <pre>
     * {@code
     * extractAndSaveIf(wrappers, MyWrapper::getEntity,
     *                  entity -> entity.isValid(), entityRepository);
     * }
     * </pre>
     *
     * @param wrappers List of wrappers
     * @param extractor Function to extract entity from wrapper
     * @param predicate Condition to check before saving
     * @param repository JPA repository to save entities
     * @param <E> Entity type
     */
    default <E> void extractAndSaveIf(
            List<WRAPPER> wrappers,
            Function<WRAPPER, E> extractor,
            java.util.function.Predicate<E> predicate,
            JpaRepository<E, ?> repository) {

        List<E> entities = wrappers.stream()
                .filter(Objects::nonNull)
                .map(extractor)
                .filter(Objects::nonNull)
                .filter(predicate)
                .toList();

        saveWithFallback(entities, repository);
    }
}