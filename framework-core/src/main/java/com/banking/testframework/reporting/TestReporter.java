package com.banking.testframework.reporting;

/**
 * Pluggable reporter that receives the completed {@link SuiteRunReport} and
 * emits it to some destination (console, S3, Slack, etc.).
 *
 * <p>Multiple reporters can be active simultaneously — the framework calls
 * {@link #report} on each registered reporter sequentially.</p>
 */
public interface TestReporter {

    /**
     * Called once when the suite has finished (after teardown).
     *
     * @param report  the complete suite run report
     */
    void report(SuiteRunReport report);

    /**
     * Human-readable name of this reporter, used in log output.
     */
    String getName();
}
