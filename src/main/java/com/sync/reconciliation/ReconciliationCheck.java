package com.sync.reconciliation;

/**
 * Abstraction for a reconciliation check.
 * Each check compares a specific invariant between legacy and new schemas.
 *
 * Teams implement one ReconciliationCheck per business invariant they want to verify.
 * The reconciliation engine auto-discovers all checks and runs them on schedule.
 *
 * Examples:
 * - Row count comparison between related tables
 * - Sum of monetary amounts
 * - Checksum of key business fields
 * - Latest record timestamp comparison
 */
public interface ReconciliationCheck {

    /**
     * Unique name for this check (used in logging and alerting).
     */
    String name();

    /**
     * Execute the reconciliation check.
     * Returns a result indicating whether the check passed or failed.
     */
    ReconciliationResult check();

    record ReconciliationResult(
            String checkName,
            boolean passed,
            String message,
            Object expectedValue,
            Object actualValue
    ) {
        public static ReconciliationResult pass(String checkName, String message) {
            return new ReconciliationResult(checkName, true, message, null, null);
        }

        public static ReconciliationResult fail(String checkName, String message,
                                                 Object expected, Object actual) {
            return new ReconciliationResult(checkName, false, message, expected, actual);
        }
    }
}
