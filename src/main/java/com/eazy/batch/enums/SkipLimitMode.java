package com.eazy.batch.enums;

/** What a job does when more rows fail than {@code skipLimit} allows. */
public enum SkipLimitMode {
    /** Abort the job (default); rows not yet processed are reported as NOT_IMPORTED. */
    FAIL,
    /** Never abort because of bad rows: every row is tried and every failure ends up in the report. */
    CONTINUE
}
