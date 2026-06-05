package com.banking.testframework.test;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable result object produced by a single {@link TestCase} execution.
 * Captures all information needed for reporting and debugging.
 */
@Getter
@Builder
@ToString
public class TestResult {

    /** Unique identifier of the test case. */
    private final String testId;

    /** Human-readable display name. */
    private final String testName;

    /** Logical group / suite this test belongs to. */
    private final String suiteName;

    /** Execution outcome. */
    private final TestStatus status;

    /** When the test started. */
    private final Instant startTime;

    /** How long the test ran. */
    private final Duration duration;

    /** Failure message (null when status is PASSED or SKIPPED). */
    private final String failureMessage;

    /** Full stack trace when status is FAILED or ERROR (null otherwise). */
    private final String stackTrace;

    /**
     * Container log snapshots captured at the moment of failure.
     * Key = container alias, Value = last N lines of logs.
     */
    @Builder.Default
    private final Map<String, String> containerLogsOnFailure = Collections.emptyMap();

    /**
     * Arbitrary metadata attached by the test (e.g. request/response bodies,
     * correlation IDs, CBS operation names).
     */
    @Builder.Default
    private final Map<String, String> metadata = Collections.emptyMap();

    /**
     * Ordered list of named steps completed before the test ended.
     * Useful for diagnosing where in a multi-step flow a test failed.
     */
    @Builder.Default
    private final List<String> completedSteps = Collections.emptyList();

    /** Convenience: returns true only when status is PASSED. */
    public boolean isPassed() {
        return TestStatus.PASSED.equals(status);
    }

    /** Convenience: returns true when the test did not pass for any reason. */
    public boolean isNotPassed() {
        return !isPassed();
    }

    /**
     * Creates a minimal SKIPPED result — useful when a prerequisite test failed
     * and the current test should not run.
     */
    public static TestResult skipped(String testId, String testName, String suiteName, String reason) {
        return TestResult.builder()
                .testId(testId)
                .testName(testName)
                .suiteName(suiteName)
                .status(TestStatus.SKIPPED)
                .startTime(Instant.now())
                .duration(Duration.ZERO)
                .failureMessage(reason)
                .build();
    }
}
