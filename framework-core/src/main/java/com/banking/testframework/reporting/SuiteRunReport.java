package com.banking.testframework.reporting;

import com.banking.testframework.test.TestResult;
import com.banking.testframework.test.TestStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Aggregate report for a complete test suite execution.
 * Passed to all {@link TestReporter} implementations after the suite finishes.
 */
@Getter
@Builder
public class SuiteRunReport {

    /** Suite name from {@link com.banking.testframework.test.TestSuiteDefinition#getSuiteName()}. */
    private final String suiteName;

    /** Unique run identifier (UUID). Used as the S3 key prefix. */
    private final String runId;

    /** When the suite started. */
    private final Instant startTime;

    /** Total wall-clock duration of the suite run. */
    private final Duration totalDuration;

    /** All test results in execution order. */
    private final List<TestResult> results;

    /** Docker network name used for this run. */
    private final String networkName;

    /** Environment name / label (e.g. "integration", "staging"). */
    private final String environment;

    // -------------------------------------------------------------------------
    // Derived counts (computed lazily)
    // -------------------------------------------------------------------------

    public long countByStatus(TestStatus status) {
        return results.stream().filter(r -> r.getStatus() == status).count();
    }

    public long passedCount()  { return countByStatus(TestStatus.PASSED); }
    public long failedCount()  { return countByStatus(TestStatus.FAILED); }
    public long errorCount()   { return countByStatus(TestStatus.ERROR); }
    public long skippedCount() { return countByStatus(TestStatus.SKIPPED); }
    public int  totalCount()   { return results.size(); }

    public boolean isAllPassed() {
        return results.stream().allMatch(TestResult::isPassed);
    }

    public double passRate() {
        if (results.isEmpty()) return 0.0;
        return (double) passedCount() / totalCount() * 100.0;
    }
}
