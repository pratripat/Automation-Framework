package com.banking.testframework.reporting;

import com.banking.testframework.test.TestResult;
import com.banking.testframework.test.TestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes a structured test report to stdout/console using ANSI color codes.
 *
 * <p>Output format:</p>
 * <pre>
 * ╔══════════════════════════════════════════════════════════════╗
 * ║  INTEGRATION TEST REPORT — funds-transfer-suite              ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 *  ✓  FT-001  Successful funds transfer              (1234ms)
 *  ✗  FT-002  Transfer with insufficient funds       (567ms)
 *     └─ AssertionError: expected 422 but was 200
 *  ⊘  FT-003  Idempotency check                      (SKIPPED)
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  Passed: 1 | Failed: 1 | Errors: 0 | Skipped: 1 | Total: 3
 *  Pass rate: 33.3% | Duration: 4.2s
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * </pre>
 */
public class ConsoleReporter implements TestReporter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleReporter.class);

    // ANSI color codes
    private static final String RESET   = "\u001B[0m";
    private static final String GREEN   = "\u001B[32m";
    private static final String RED     = "\u001B[31m";
    private static final String YELLOW  = "\u001B[33m";
    private static final String CYAN    = "\u001B[36m";
    private static final String BOLD    = "\u001B[1m";
    private static final String DIM     = "\u001B[2m";

    private final boolean useColors;

    public ConsoleReporter() {
        // Detect if running in a terminal that supports ANSI
        this.useColors = System.getenv("NO_COLOR") == null &&
                (System.console() != null || System.getenv("CI") != null);
    }

    public ConsoleReporter(boolean useColors) {
        this.useColors = useColors;
    }

    @Override
    public String getName() {
        return "ConsoleReporter";
    }

    @Override
    public void report(SuiteRunReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");

        // Header
        String header = "  INTEGRATION TEST REPORT — " + report.getSuiteName() + "  ";
        String border = "═".repeat(header.length());
        sb.append(color(BOLD + CYAN, "╔" + border + "╗")).append("\n");
        sb.append(color(BOLD + CYAN, "║")).append(color(BOLD, header)).append(color(BOLD + CYAN, "║")).append("\n");
        sb.append(color(BOLD + CYAN, "╚" + border + "╝")).append("\n");
        sb.append("\n");

        // Metadata
        sb.append(color(DIM, "  Run ID   : ")).append(report.getRunId()).append("\n");
        sb.append(color(DIM, "  Started  : "))
                .append(report.getStartTime().atZone(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))).append("\n");
        sb.append(color(DIM, "  Network  : ")).append(report.getNetworkName()).append("\n");
        sb.append("\n");

        // Test result rows
        for (TestResult result : report.getResults()) {
            sb.append(formatResultRow(result));
        }
        sb.append("\n");

        // Summary
        String divider = "━".repeat(header.length() + 2);
        sb.append(color(DIM, " " + divider)).append("\n");

        String summary = String.format(
                "  %s Passed: %d  %s Failed: %d  %s Errors: %d  %s Skipped: %d  │  Total: %d",
                color(GREEN, "✓"), report.passedCount(),
                color(RED, "✗"), report.failedCount(),
                color(RED, "!"), report.errorCount(),
                color(YELLOW, "⊘"), report.skippedCount(),
                report.totalCount());
        sb.append(summary).append("\n");

        String passRate = String.format("  Pass rate: %s  │  Duration: %.2fs",
                colorByRate(report.passRate()),
                report.getTotalDuration().toMillis() / 1000.0);
        sb.append(passRate).append("\n");
        sb.append(color(DIM, " " + divider)).append("\n");

        // Overall result line
        if (report.isAllPassed()) {
            sb.append("\n").append(color(GREEN + BOLD, "  ✓ ALL TESTS PASSED")).append("\n\n");
        } else {
            sb.append("\n").append(color(RED + BOLD, "  ✗ SUITE FAILED")).append("\n\n");
        }

        System.out.println(sb);

        // Print failure details separately so they stand out
        List<TestResult> failures = report.getResults().stream()
                .filter(r -> r.getStatus() == TestStatus.FAILED || r.getStatus() == TestStatus.ERROR)
                .toList();

        if (!failures.isEmpty()) {
            System.out.println(color(BOLD + RED, " FAILURE DETAILS:"));
            System.out.println(color(DIM, " " + divider));
            for (TestResult f : failures) {
                System.out.printf("%n %s[%s] %s%s%n",
                        color(RED, ""), f.getTestId(), f.getTestName(), RESET);
                if (f.getFailureMessage() != null) {
                    System.out.println("   Message: " + f.getFailureMessage());
                }
                if (f.getStackTrace() != null) {
                    String trace = f.getStackTrace();
                    // Show only first 10 lines of stack trace
                    String[] traceLines = trace.split("\n");
                    int limit = Math.min(10, traceLines.length);
                    for (int i = 0; i < limit; i++) {
                        System.out.println("   " + color(DIM, traceLines[i]));
                    }
                    if (traceLines.length > limit) {
                        System.out.printf("   %s... (%d more lines)%s%n", DIM, traceLines.length - limit, RESET);
                    }
                }
                if (!f.getContainerLogsOnFailure().isEmpty()) {
                    System.out.println("   Container logs:");
                    f.getContainerLogsOnFailure().forEach((container, logs) -> {
                        System.out.printf("     [%s]:%n", container);
                        System.out.println("     " + color(DIM, logs.replace("\n", "\n     ")));
                    });
                }
            }
            System.out.println();
        }
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private String formatResultRow(TestResult result) {
        String icon;
        String durationOrStatus;

        switch (result.getStatus()) {
            case PASSED  -> { icon = color(GREEN, " ✓ "); durationOrStatus = "(" + result.getDuration().toMillis() + "ms)"; }
            case FAILED  -> { icon = color(RED,   " ✗ "); durationOrStatus = "(" + result.getDuration().toMillis() + "ms)"; }
            case ERROR   -> { icon = color(RED,   " ! "); durationOrStatus = "(" + result.getDuration().toMillis() + "ms)"; }
            case SKIPPED -> { icon = color(YELLOW," ⊘ "); durationOrStatus = "(SKIPPED)"; }
            default      -> { icon = "   "; durationOrStatus = ""; }
        }

        String row = String.format("  %s %-10s %-50s %s%s%n",
                icon,
                color(DIM, "[" + result.getTestId() + "]"),
                result.getTestName(),
                color(DIM, durationOrStatus),
                RESET);

        if (result.getFailureMessage() != null
                && result.getStatus() != TestStatus.SKIPPED
                && result.getStatus() != TestStatus.PASSED) {
            String shortMsg = result.getFailureMessage();
            if (shortMsg.length() > 120) shortMsg = shortMsg.substring(0, 120) + "…";
            row += String.format("  %s└─ %s%s%n", DIM, shortMsg, RESET);
        }

        return row;
    }

    private String color(String ansiCode, String text) {
        if (!useColors) return text;
        return ansiCode + text + RESET;
    }

    private String colorByRate(double rate) {
        if (!useColors) return String.format("%.1f%%", rate);
        String ansi = rate == 100.0 ? GREEN : rate >= 80.0 ? YELLOW : RED;
        return ansi + String.format("%.1f%%", rate) + RESET;
    }
}
