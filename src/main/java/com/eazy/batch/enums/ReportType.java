package com.eazy.batch.enums;

/**
 * Which report a {@code @BatchJob} produces when it finishes.
 *
 * <p>Both reports mirror the columns of the uploaded template and append two
 * diagnostic columns, {@code Status} and {@code Reason}. Delete those two
 * columns, fix the rows, and the file can be uploaded again as-is.</p>
 */
public enum ReportType {

    /**
     * Default. Only rows that did NOT end up imported:
     * <ul>
     *   <li>{@code FAILED} - the row itself was rejected (with the reason);</li>
     *   <li>{@code NOT_IMPORTED} - the row may well be fine, but it was lost
     *       because its chunk was rolled back or the job stopped before
     *       reaching it (with the reason).</li>
     * </ul>
     * No report is produced when every row was imported.
     */
    ERRORS,

    /**
     * Every row of the uploaded file, each with a {@code Status} of
     * {@code SUCCESS}, {@code FAILED} or {@code NOT_IMPORTED}, and a
     * {@code Reason} for the last two.
     */
    ALL
}
