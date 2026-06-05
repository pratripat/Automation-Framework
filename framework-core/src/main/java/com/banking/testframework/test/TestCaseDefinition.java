package com.banking.testframework.test;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Associates a {@link TestCase} with its metadata for registration in a suite.
 *
 * <p>Test implementations register cases via {@link TestSuiteDefinition#addTest},
 * which accepts {@code TestCaseDefinition} objects built with this class.</p>
 */
@Getter
@Builder
public class TestCaseDefinition {

    /** Unique identifier — used as the key in reports and S3 artefacts. */
    private final String id;

    /** Human-readable name displayed in console and HTML reports. */
    private final String name;

    /**
     * Optional description shown in the HTML report.
     * Useful for documenting what business scenario the test covers.
     */
    @Builder.Default
    private final String description = "";

    /**
     * IDs of other tests that must have passed before this test runs.
     * If any dependency has status != PASSED, this test is auto-skipped.
     */
    @Builder.Default
    private final List<String> dependsOn = Collections.emptyList();

    /**
     * Tags for grouping and filtering (e.g. "smoke", "regression", "funds-transfer").
     * The suite runner can be configured to run only tests matching certain tags.
     */
    @Builder.Default
    private final List<String> tags = Collections.emptyList();

    /** The actual test logic. */
    private final TestCase testCase;

    /**
     * Whether a failure in this test should abort the remainder of the suite.
     * Default: false — all tests run regardless of individual failures.
     */
    @Builder.Default
    private final boolean abortOnFailure = false;

    /** Convenience factory for the common single-step case. */
    public static TestCaseDefinition of(String id, String name, TestCase testCase) {
        return TestCaseDefinition.builder()
                .id(id)
                .name(name)
                .testCase(testCase)
                .build();
    }
}
