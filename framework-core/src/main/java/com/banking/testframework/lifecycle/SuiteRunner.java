package com.banking.testframework.lifecycle;

import com.banking.testframework.reporting.ReportConfig;
import com.banking.testframework.reporting.SuiteRunReport;
import com.banking.testframework.test.TestSuiteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Standalone programmatic runner for executing one or more test suites without
 * requiring JUnit. Useful for:
 * <ul>
 *   <li>Running suites from a {@code main()} method in CI scripts</li>
 *   <li>Chaining multiple suites with shared report config</li>
 *   <li>Integrating the framework into non-Java CI tooling via a fat JAR</li>
 * </ul>
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     int exitCode = SuiteRunner.builder()
 *         .addSuite(new FundsTransferSuite())
 *         .addSuite(new AccountInquirySuite())
 *         .reportConfig(ReportConfig.withS3("my-bucket", "banking/integration/"))
 *         .failFast(false)
 *         .build()
 *         .run();
 *
 *     System.exit(exitCode); // 0 = all passed, 1 = failures
 * }
 * }</pre>
 */
public class SuiteRunner {

    private static final Logger log = LoggerFactory.getLogger(SuiteRunner.class);

    private final List<TestSuiteDefinition> suites;
    private final ReportConfig reportConfig;
    private final boolean failFast;
    private final Map<String, String> environmentOverrides;

    private SuiteRunner(Builder builder) {
        this.suites = List.copyOf(builder.suites);
        this.reportConfig = builder.reportConfig != null
                ? builder.reportConfig : ReportConfig.consoleOnly();
        this.failFast = builder.failFast;
        this.environmentOverrides = Map.copyOf(builder.environmentOverrides);
    }

    /**
     * Runs all registered suites sequentially.
     *
     * @return 0 if all suites passed, 1 if any suite had failures or errors
     */
    public int run() {
        log.info("SuiteRunner: executing {} suite(s)", suites.size());

        List<SuiteRunReport> allReports = new ArrayList<>();
        boolean anyFailed = false;

        for (TestSuiteDefinition suite : suites) {
            log.info("━━━ Running suite: {} ━━━", suite.getSuiteName());

            SuiteRunReport report = TestOrchestrator.builder()
                    .suiteDefinition(suite)
                    .reportConfig(reportConfig)
                    .env(environmentOverrides)
                    .build()
                    .run();

            allReports.add(report);

            if (!report.isAllPassed()) {
                anyFailed = true;
                if (failFast) {
                    log.warn("failFast=true — stopping after first failed suite: {}",
                            suite.getSuiteName());
                    break;
                }
            }
        }

        printAggregateSummary(allReports);
        return anyFailed ? 1 : 0;
    }

    private void printAggregateSummary(List<SuiteRunReport> reports) {
        if (reports.size() <= 1) return;

        long totalTests   = reports.stream().mapToLong(SuiteRunReport::totalCount).sum();
        long totalPassed  = reports.stream().mapToLong(SuiteRunReport::passedCount).sum();
        long totalFailed  = reports.stream().mapToLong(SuiteRunReport::failedCount).sum();
        long totalErrors  = reports.stream().mapToLong(SuiteRunReport::errorCount).sum();
        long totalSkipped = reports.stream().mapToLong(SuiteRunReport::skippedCount).sum();

        log.info("═══════════════════════════════════════════════════");
        log.info("  AGGREGATE SUMMARY — {} suites", reports.size());
        log.info("  Passed: {}  Failed: {}  Errors: {}  Skipped: {}  / Total: {}",
                totalPassed, totalFailed, totalErrors, totalSkipped, totalTests);
        for (SuiteRunReport r : reports) {
            String icon = r.isAllPassed() ? "✓" : "✗";
            log.info("  {} {} — {}/{} passed ({}ms)",
                    icon, r.getSuiteName(), r.passedCount(), r.totalCount(),
                    r.getTotalDuration().toMillis());
        }
        log.info("═══════════════════════════════════════════════════");
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<TestSuiteDefinition> suites = new ArrayList<>();
        private ReportConfig reportConfig;
        private boolean failFast = false;
        private final Map<String, String> environmentOverrides = new LinkedHashMap<>();

        public Builder addSuite(TestSuiteDefinition suite) {
            this.suites.add(suite); return this;
        }

        public Builder reportConfig(ReportConfig config) {
            this.reportConfig = config; return this;
        }

        /** Stop after the first suite that has any failures. Default: false. */
        public Builder failFast(boolean failFast) {
            this.failFast = failFast; return this;
        }

        public Builder env(String key, String value) {
            this.environmentOverrides.put(key, value); return this;
        }

        public Builder env(Map<String, String> overrides) {
            this.environmentOverrides.putAll(overrides); return this;
        }

        public SuiteRunner build() {
            if (suites.isEmpty()) throw new IllegalStateException("At least one suite must be added");
            return new SuiteRunner(this);
        }
    }
}
