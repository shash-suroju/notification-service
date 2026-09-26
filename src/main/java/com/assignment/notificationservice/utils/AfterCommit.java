package com.assignment.notificationservice.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers side effects until the surrounding transaction has committed. */
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Runs {@code action} after the current transaction commits (never if it rolls back), or
     * immediately when no transaction is active.
     *
     * <p>Used for cache invalidation: evicting before commit lets another thread reload the
     * old, still-visible value and cache it again.
     */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
