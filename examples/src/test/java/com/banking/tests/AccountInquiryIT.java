package com.banking.tests;

import com.banking.testframework.lifecycle.IntegrationTestExtension;
import com.banking.testframework.reporting.SuiteRunReport;
import com.banking.testframework.test.TestResult;
import com.banking.testframework.test.TestStatus;
import com.banking.tests.suites.AccountInquirySuite;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 runner for the {@link AccountInquirySuite}.
 *
 * <p>Demonstrates multi-component deployment (Postgres + CBS mock + account-service)
 * with environment variable propagation from the Postgres component to the service.</p>
 *
 * <p>Run with: {@code mvn verify -pl examples}</p>
 */
@ExtendWith(IntegrationTestExtension.class)
@IntegrationTestExtension.SuiteDefinitionClass(AccountInquirySuite.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Account Inquiry Integration Tests")
class AccountInquiryIT {

    @Test
    @DisplayName("All account inquiry tests pass")
    void allTestsPass(SuiteRunReport report) {
        assertThat(report.failedCount() + report.errorCount())
                .as("Expected 0 failures but got %d failures and %d errors",
                        report.failedCount(), report.errorCount())
                .isZero();
    }

    @Test
    @DisplayName("AI-001: Active account inquiry")
    void ai001ActiveAccountInquiry(SuiteRunReport report) {
        assertTestPassed(report, "AI-001");
    }

    @Test
    @DisplayName("AI-002: Account not found returns 404")
    void ai002AccountNotFound(SuiteRunReport report) {
        assertTestPassed(report, "AI-002");
    }

    @Test
    @DisplayName("AI-003: Dormant account handled correctly")
    void ai003DormantAccount(SuiteRunReport report) {
        assertTestPassed(report, "AI-003");
    }

    @Test
    @DisplayName("AI-004: Balance check returns correct balance")
    void ai004BalanceCheck(SuiteRunReport report) {
        assertTestPassed(report, "AI-004");
    }

    @Test
    @DisplayName("AI-005: Mini statement returns 5 transactions")
    void ai005MiniStatement(SuiteRunReport report) {
        assertTestPassed(report, "AI-005");
    }

    @Test
    @DisplayName("AI-006: CBS unavailable triggers circuit breaker")
    void ai006CbsUnavailable(SuiteRunReport report) {
        assertTestPassed(report, "AI-006");
    }

    private void assertTestPassed(SuiteRunReport report, String testId) {
        TestResult result = report.getResults().stream()
                .filter(r -> r.getTestId().equals(testId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test '" + testId + "' not found in report"));

        assertThat(result.getStatus())
                .as("Test [%s] '%s' expected PASSED but was %s. Detail: %s",
                        testId, result.getTestName(), result.getStatus(), result.getFailureMessage())
                .isEqualTo(TestStatus.PASSED);
    }
}
