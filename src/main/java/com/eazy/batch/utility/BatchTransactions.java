package com.eazy.batch.utility;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;


/**
 * Row-isolation support. While {@link #run} is active on a thread, {@code BatchUtility.saveWithFallback}
 * executes the bulk save and every individual save in its own REQUIRES_NEW transaction, so a failing
 * row cannot mark the surrounding chunk transaction rollback-only.
 */
public final class BatchTransactions {

    private static final ThreadLocal<Boolean> ISOLATED = new ThreadLocal<>();
    private static volatile TransactionTemplate template;
    private static volatile boolean globalDefault;

    private BatchTransactions() {
    }

    /** Called once at start-up with the application's transaction manager. */
    public static void configure(PlatformTransactionManager manager, boolean isolateAllJobs) {
        globalDefault = isolateAllJobs;
        if (manager == null) {
            template = null;
            return;
        }
        TransactionTemplate t = new TransactionTemplate(manager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template = t;
    }

    /** Runs {@code action}, with row isolation when the job asked for it or it is on globally. */
    public static void run(boolean jobWantsIsolation, Runnable action) {
        if (!(jobWantsIsolation || globalDefault) || template == null) {
            action.run();
            return;
        }
        ISOLATED.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            ISOLATED.remove();
        }
    }

    static boolean active() {
        return Boolean.TRUE.equals(ISOLATED.get()) && template != null;
    }

    /** Runs {@code work} in a fresh transaction when isolation is active, otherwise inline. */
    static void inNewTransaction(Runnable work) {
        if (!active()) {
            work.run();
            return;
        }
        template.executeWithoutResult(status -> work.run());
    }
}
