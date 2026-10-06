package com.eazy.batch.report;

/** Outcome of one uploaded row, as shown in the report's Status column. */
public enum RowStatus {
    /** The row was imported (its chunk committed). */
    SUCCESS,
    /** The row itself was rejected - see the Reason column. */
    FAILED,
    /**
     * The row was not imported but is not necessarily at fault: its chunk was
     * rolled back, or the job stopped before reaching it.
     */
    NOT_IMPORTED
}
