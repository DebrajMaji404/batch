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
}