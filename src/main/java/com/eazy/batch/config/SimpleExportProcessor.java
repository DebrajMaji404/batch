package com.eazy.batch.config;

import com.eazy.batch.model.ExportColumn;

import java.util.List;

/**
 * Base interface for export batch processing.
 * Annotate your implementing class with {@code @BatchExportJob} to auto-generate Spring Batch beans.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * @Component
 * @BatchExportJob(
 *     jobName       = "exportEmployeeJob",
 *     stepName      = "exportEmployeeStep",
 *     entityClass   = Employee.class,
 *     storageType   = StorageType.LOCAL,
 *     fileName      = "employees"
 * )
 * public class EmployeeExportConfig implements SimpleExportProcessor<Employee> {
 *
 *     // JPA query - no raw SQL, no rowMapper needed
 *     @Override
 *     public String getJpqlQuery() {
 *         return "SELECT e FROM Employee e WHERE e.active = true ORDER BY e.name";
 *     }
 *
 *     // Column definitions using method references (no rs.getString() ever again)
 *     @Override
 *     public List<ExportColumn<Employee>> getColumns() {
 *         return List.of(
 *             col("ID",             Employee::getId),
 *             col("Name",           Employee::getName),
 *             col("Email",          Employee::getEmail),
 *             col("Manager Email",  e -> e.getManager().getEmail()),  // nested object
 *             col("Department",     e -> e.getDept().getName())       // nested object
 *         );
 *     }
 *
 *     // Called after file is successfully saved — receive the URL here
 *     @Override
 *     public void onSaveComplete(String fileUrl) {
 *         notificationService.notifyAdmins("Export ready: " + fileUrl);
 *     }
 * }
 * }</pre>
 *
 * @param <ENTITY> JPA entity type to export
 */
public interface SimpleExportProcessor<ENTITY> {

    /**
     * JPQL query to load data from the database.
     *
     * <p>Write standard JPQL — Spring Data JPA handles the mapping.
     * No {@code rs.getLong()}, no {@code rowMapper} — just a clean query string.</p>
     *
     * <p>Examples:</p>
     * <pre>
     * "SELECT e FROM Employee e"
     * "SELECT e FROM Employee e WHERE e.active = true ORDER BY e.name"
     * "SELECT e FROM Employee e JOIN FETCH e.department"
     * </pre>
     *
     * @return JPQL query string
     */
    String getJpqlQuery();

    /**
     * Column definitions for the output file.
     *
     * <p>Use {@link ExportColumn#col} with method references to map entity fields to columns.
     * Supports nested access like {@code e -> e.getUser().getEmail()}.</p>
     *
     * @return Ordered list of columns to write
     */
    List<ExportColumn<ENTITY>> getColumns();

    /**
     * Called after the file is successfully saved to storage.
     *
     * <p>Use this to send notifications, update a DB record, trigger a webhook, etc.</p>
     *
     * @param fileUrl The URL or path where the file was saved. For the built-in LOCAL
     *                storage this is an absolute file path (e.g.
     *                {@code /exports/employees_20240101.xlsx}); for a CUSTOM
     *                {@link com.eazy.batch.service.ExportStorageService} it's whatever
     *                URL string your implementation returns.
     */
    default void onSaveComplete(String fileUrl) {
        // Override to add custom post-save logic
    }

    /**
     * Called when saving the file fails.
     *
     * @param error The exception that caused the failure
     */
    default void onSaveFailure(Throwable error) {
        // Override to add custom error handling
    }

    /**
     * Optional: called before the export job starts.
     * Use for initialization, logging, or setup.
     */
    default void onExportStart() {
        // Override to add custom logic
    }

    /**
     * Shortcut to create an ExportColumn — avoids the static import boilerplate.
     *
     * <pre>{@code
     * col("Name", Employee::getName)
     * }</pre>
     */
    default <T> ExportColumn<T> col(String header, java.util.function.Function<T, Object> extractor) {
        return ExportColumn.col(header, extractor);
    }
}