package com.banking.tests;

import com.banking.testframework.lifecycle.IntegrationTestExtension;
import com.banking.testframework.reporting.SuiteRunReport;
import com.banking.testframework.test.TestResult;
import com.banking.testframework.test.TestStatus;
import com.banking.tests.suites.FundsTransferSuite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 runner for the {@link FundsTransferSuite}.
 *
 * <p>Maven Failsafe picks this up because the class name ends in {@code IT}.
 * Run with: {@code mvn verify -pl examples}</p>
 *
 * <p>To run against a real channel-service image built locally:</p>
 * <pre>
 *   docker build -t banking/channel-service:latest ./channel-service
 *   mvn verify -pl examples
 * </pre>
 *
 * <p>To enable S3 reporting, add {@code @ReportToS3} to this class or
 * set the {@code TEST_S3_BUCKET} environment variable.</p>
 */
@ExtendWith(IntegrationTestExtension.class)
@IntegrationTestExtension.SuiteDefinitionClass(FundsTransferSuite.class)
// Uncomment to enable S3 reporting:
// @IntegrationTestExtension.ReportToS3(bucket = "my-test-results", prefix = "banking/funds-transfer/")
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Funds Transfer Integration Tests")
class FundsTransferIT {

    /**
     * Top-level guard: if ANY test fails, this assertion fires first and
     * prints the summary from the console reporter before Maven marks the build failed.
     *
     * <p>Comment this out if you want individual test assertions below to be
     * the primary failure signal.</p>
     */
    @Test
    @DisplayName("All funds transfer tests pass")
    void allTestsPass(SuiteRunReport report) {
        assertThat(report.failedCount() + report.errorCount())
                .as("Expected 0 failures/errors but got %d failures and %d errors.%nResults: %s",
                        report.failedCount(), report.errorCount(),
                        report.getResults().stream()
                                .filter(r -> r.isNotPassed())
                                .map(r -> r.getTestId() + " [" + r.getStatus() + "] " + r.getFailureMessage())
                                .toList())
                .isZero();
    }

    @Test
    @DisplayName("FT-001: Successful funds transfer")
    void ft001SuccessfulFundsTransfer(SuiteRunReport report) {
        assertTestPassed(report, "FT-001");
    }

    @Test
    @DisplayName("FT-002: Insufficient funds returns 422")
    void ft002InsufficientFunds(SuiteRunReport report) {
        assertTestPassed(report, "FT-002");
    }

    @Test
    @DisplayName("FT-003: Idempotency check")
    void ft003Idempotency(SuiteRunReport report) {
        assertTestPassed(report, "FT-003");
    }

    @Test
    @DisplayName("FT-004: CBS timeout handled gracefully")
    void ft004CbsTimeout(SuiteRunReport report) {
        assertTestPassed(report, "FT-004");
    }

    @Test
    @DisplayName("FT-005: Missing mandatory field rejected")
    void ft005MissingField(SuiteRunReport report) {
        assertTestPassed(report, "FT-005");
    }

    @Test
    @DisplayName("FT-006: Over-limit amount rejected")
    void ft006OverLimit(SuiteRunReport report) {
        assertTestPassed(report, "FT-006");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertTestPassed(SuiteRunReport report, String testId) {
        TestResult result = report.getResults().stream()
                .filter(r -> r.getTestId().equals(testId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Test '" + testId + "' was not found in the suite report. " +
                        "Available IDs: " + report.getResults().stream()
                                .map(TestResult::getTestId).toList()));

        assertThat(result.getStatus())
                .as("Test [%s] '%s' expected PASSED but was %s.%nDetail: %s",
                        testId, result.getTestName(), result.getStatus(),
                        result.getFailureMessage())
                .isEqualTo(TestStatus.PASSED);
    }
}
