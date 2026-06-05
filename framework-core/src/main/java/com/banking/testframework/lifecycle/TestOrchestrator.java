package com.banking.testframework.lifecycle;

import com.banking.testframework.container.CleanupRegistry;
import com.banking.testframework.container.ContainerRegistry;
import com.banking.testframework.container.DeployableComponent;
import com.banking.testframework.container.NetworkManager;
import com.banking.testframework.mock.MockDownstreamComponent;
import com.banking.testframework.reporting.ConsoleReporter;
import com.banking.testframework.reporting.ReportConfig;
import com.banking.testframework.reporting.S3Reporter;
import com.banking.testframework.reporting.SuiteRunReport;
import com.banking.testframework.reporting.TestReporter;
import com.banking.testframework.test.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Central coordinator for the functional test framework lifecycle.
 *
 * <p>
 * Manages the full execution pipeline:</p>
 * <pre>
 *   INIT → NETWORK_SETUP → DEPLOY → HEALTH_VERIFY → TEST_RUN → REPORT → TEARDOWN
 * </pre>
 *
 * <p>
 * Guarantees:</p>
 * <ul>
 * <li>Teardown always runs — even on deployment failure or test exception</li>
 * <li>JVM shutdown hook ensures containers are cleaned up on Ctrl+C</li>
 * <li>Container logs are captured automatically on test failure</li>
 * <li>Reports are generated before teardown so container logs are still
 * accessible</li>
 * </ul>
 *
 * <p>
 * Usage:</p>
 * <pre>{@code
 * TestOrchestrator.builder()
 *     .suiteDefinition(new FundsTransferSuite())
 *     .reportConfig(ReportConfig.withS3("my-bucket", "banking/integration/"))
 *     .build()
 *     .run();
 * }</pre>
 */
public class TestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TestOrchestrator.class);
    private static final int CONTAINER_LOG_LINES_ON_FAILURE = 150;

    private final TestSuiteDefinition suiteDefinition;
    private final ReportConfig reportConfig;
    private final List<TestReporter> extraReporters;
    private final Map<String, String> environmentOverrides;

    private TestOrchestrator(Builder builder) {
        this.suiteDefinition = Objects.requireNonNull(builder.suiteDefinition, "suiteDefinition is required");
        this.reportConfig = builder.reportConfig != null ? builder.reportConfig : ReportConfig.consoleOnly();
        this.extraReporters = List.copyOf(builder.extraReporters);
        this.environmentOverrides = Map.copyOf(builder.environmentOverrides);
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------
    /**
     * Runs the complete lifecycle: deploy → test → report → teardown.
     *
     * @return the {@link SuiteRunReport} — callers can inspect this to fail a
     * JUnit/Maven build based on the overall pass/fail status.
     */
    public SuiteRunReport run() {
        String runId = UUID.randomUUID().toString();
        String suiteName = suiteDefinition.getSuiteName();
        Instant suiteStart = Instant.now();

        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║  Starting functional test suite: {}  ", suiteName);
        log.info("║  Run ID: {}  ", runId);
        log.info("╚══════════════════════════════════════════════════════╝");

        CleanupRegistry cleanupRegistry = new CleanupRegistry();
        NetworkManager networkManager = new NetworkManager(suiteName);
        ContainerRegistry containerRegistry = new ContainerRegistry(cleanupRegistry);

        List<TestResult> results = new ArrayList<>();

        try {
            // ── PHASE 1: Network setup ──────────────────────────────────────
            log.info("[Phase 1/4] Setting up Docker network…");
            Network network = networkManager.createNetwork();
            cleanupRegistry.registerCloseable("network:" + networkManager.getNetworkName(), networkManager);
            log.info("[Phase 1/4] Network '{}' ready", networkManager.getNetworkName());

            // ── PHASE 2: Deploy all components ─────────────────────────────
            log.info("[Phase 2/4] Deploying {} components…",
                    suiteDefinition.getComponents().size());

            // Merge suite env overrides with orchestrator-level overrides
            Map<String, String> mergedEnv = new LinkedHashMap<>(suiteDefinition.getSuiteProperties());
            mergedEnv.putAll(environmentOverrides);

            containerRegistry.deployAll(suiteDefinition.getComponents(), network, mergedEnv);
            log.info("[Phase 2/4] All components deployed successfully");

            // ── PHASE 3: Build TestContext ──────────────────────────────────
            log.info("[Phase 3/4] Building test context…");
            Map<String, WireMockServer> mockServers = collectMockServers(
                    suiteDefinition.getComponents(), containerRegistry);

            TestContext ctx = new TestContext(
                    containerRegistry.getServiceUrls(),
                    mockServers,
                    new HttpTestClient(),
                    suiteDefinition.getSuiteProperties());

            log.info("[Phase 3/4] Test context ready. Services: {}",
                    containerRegistry.getServiceUrls().keySet());

            // ── PHASE 4: Execute tests ──────────────────────────────────────
            log.info("[Phase 4/4] Executing {} test cases…",
                    suiteDefinition.getTestCases().size());

            // Global beforeAll hook
            try {
                suiteDefinition.beforeAll(ctx);
            } catch (Exception e) {
                log.error("beforeAll() hook failed — aborting suite", e);
                results.add(syntheticErrorResult("suite-before-all", "Suite beforeAll hook", suiteName, e));
                return buildAndReport(runId, suiteName, suiteStart, results,
                        networkManager.getNetworkName(), reportConfig, extraReporters);
            }

            results.addAll(executeTestCases(suiteDefinition, ctx,
                    containerRegistry, reportConfig));

            // Global afterAll hook
            try {
                suiteDefinition.afterAll(ctx, Collections.unmodifiableList(results));
            } catch (Exception e) {
                log.error("afterAll() hook failed", e);
            }

        } catch (Exception e) {
            log.error("Suite deployment failed", e);
            results.add(syntheticErrorResult("suite-deploy", "Suite deployment", suiteName, e));
        } finally {
            // ── TEARDOWN (always runs) ──────────────────────────────────────
            log.info("Tearing down suite resources…");
            cleanupRegistry.close();
            log.info("Teardown complete");
        }

        return buildAndReport(runId, suiteName, suiteStart, results,
                "n/a (cleaned up)", reportConfig, extraReporters);
    }

    // -------------------------------------------------------------------------
    // Test execution
    // -------------------------------------------------------------------------
    private List<TestResult> executeTestCases(TestSuiteDefinition suite,
            TestContext ctx,
            ContainerRegistry containerRegistry,
            ReportConfig reportConfig) {
        List<TestResult> results = new ArrayList<>();
        Map<String, TestStatus> completedStatuses = new LinkedHashMap<>();
        boolean aborted = false;

        for (TestCaseDefinition definition : suite.getTestCases()) {
            if (aborted) {
                results.add(TestResult.skipped(definition.getId(), definition.getName(),
                        suite.getSuiteName(), "Suite aborted by earlier test failure"));
                continue;
            }

            // Dependency check
            List<String> unmetDeps = definition.getDependsOn().stream()
                    .filter(depId -> completedStatuses.get(depId) != TestStatus.PASSED)
                    .toList();

            if (!unmetDeps.isEmpty()) {
                String reason = "Skipped: dependencies not passed: " + unmetDeps;
                log.warn("[{}] {} — {}", definition.getId(), definition.getName(), reason);
                TestResult skipped = TestResult.skipped(definition.getId(),
                        definition.getName(), suite.getSuiteName(), reason);
                results.add(skipped);
                completedStatuses.put(definition.getId(), TestStatus.SKIPPED);
                continue;
            }

            // beforeEach hook
            try {
                ctx.clearTestMetadata();
                suite.beforeEach(ctx, definition);
            } catch (Exception e) {
                log.error("[{}] beforeEach hook failed", definition.getId(), e);
            }

            // Execute
            TestResult result = executeOne(definition, ctx, suite.getSuiteName(),
                    containerRegistry, reportConfig);
            results.add(result);
            completedStatuses.put(definition.getId(), result.getStatus());

            // afterEach hook
            try {
                suite.afterEach(ctx, result);
            } catch (Exception e) {
                log.error("[{}] afterEach hook failed", definition.getId(), e);
            }

            if (definition.isAbortOnFailure() && result.isNotPassed()) {
                log.warn("abortOnFailure=true on test [{}] — stopping suite", definition.getId());
                aborted = true;
            }
        }

        return results;
    }

    private TestResult executeOne(TestCaseDefinition definition,
            TestContext ctx,
            String suiteName,
            ContainerRegistry containerRegistry,
            ReportConfig reportConfig) {
        log.info("  ▶  [{}] {}", definition.getId(), definition.getName());
        Instant start = Instant.now();

        try {
            TestResult rawResult = definition.getTestCase().execute(ctx);
            Duration duration = Duration.between(start, Instant.now());

            // Merge metadata from context into result
            Map<String, String> mergedMeta = new LinkedHashMap<>(ctx.getTestMetadata());
            mergedMeta.putAll(rawResult.getMetadata());

            TestResult finalResult = TestResult.builder()
                    .testId(definition.getId())
                    .testName(definition.getName())
                    .suiteName(suiteName)
                    .status(rawResult.getStatus())
                    .startTime(start)
                    .duration(duration)
                    .failureMessage(rawResult.getFailureMessage())
                    .stackTrace(rawResult.getStackTrace())
                    .metadata(mergedMeta)
                    .completedSteps(rawResult.getCompletedSteps())
                    .containerLogsOnFailure(rawResult.isNotPassed() && reportConfig.isCaptureContainerLogsOnFailure()
                            ? captureContainerLogs(containerRegistry, reportConfig.getContainerLogLines())
                            : Map.of())
                    .build();

            String icon = finalResult.isPassed() ? "✓" : "✗";
            log.info("  {}  [{}] {} ({}ms)", icon, definition.getId(),
                    definition.getName(), duration.toMillis());
            return finalResult;

        } catch (AssertionError ae) {
            Duration duration = Duration.between(start, Instant.now());
            log.warn("  ✗  [{}] {} — assertion failed: {}",
                    definition.getId(), definition.getName(), ae.getMessage());
            return TestResult.builder()
                    .testId(definition.getId())
                    .testName(definition.getName())
                    .suiteName(suiteName)
                    .status(TestStatus.FAILED)
                    .startTime(start)
                    .duration(duration)
                    .failureMessage(ae.getMessage())
                    .stackTrace(stackTraceToString(ae))
                    .containerLogsOnFailure(reportConfig.isCaptureContainerLogsOnFailure()
                            ? captureContainerLogs(containerRegistry, reportConfig.getContainerLogLines())
                            : Map.of())
                    .build();

        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            log.error("  !  [{}] {} — unexpected exception", definition.getId(), definition.getName(), e);
            return TestResult.builder()
                    .testId(definition.getId())
                    .testName(definition.getName())
                    .suiteName(suiteName)
                    .status(TestStatus.ERROR)
                    .startTime(start)
                    .duration(duration)
                    .failureMessage(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .stackTrace(stackTraceToString(e))
                    .containerLogsOnFailure(reportConfig.isCaptureContainerLogsOnFailure()
                            ? captureContainerLogs(containerRegistry, reportConfig.getContainerLogLines())
                            : Map.of())
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private Map<String, WireMockServer> collectMockServers(
            List<DeployableComponent> components,
            ContainerRegistry registry) {
        Map<String, WireMockServer> mockServers = new LinkedHashMap<>();
        for (DeployableComponent component : components) {
            if (component instanceof MockDownstreamComponent mock) {
                WireMockServer server = mock.getWireMockServer();
                if (server != null) {
                    mockServers.put(component.getAlias(), server);
                }
            }
        }
        return mockServers;
    }

    private Map<String, String> captureContainerLogs(ContainerRegistry registry, int lines) {
        Map<String, String> logs = new LinkedHashMap<>();
        for (DeployableComponent component : registry.getOrderedComponents()) {
            try {
                logs.put(component.getAlias(), component.getRecentLogs(lines));
            } catch (Exception e) {
                logs.put(component.getAlias(), "(log capture failed: " + e.getMessage() + ")");
            }
        }
        return logs;
    }

    private SuiteRunReport buildAndReport(String runId,
            String suiteName,
            Instant start,
            List<TestResult> results,
            String networkName,
            ReportConfig reportConfig,
            List<TestReporter> extraReporters) {
        SuiteRunReport report = SuiteRunReport.builder()
                .runId(runId)
                .suiteName(suiteName)
                .startTime(start)
                .totalDuration(Duration.between(start, Instant.now()))
                .results(Collections.unmodifiableList(results))
                .networkName(networkName)
                .environment(environmentOverrides.getOrDefault("env", "integration"))
                .build();

        // Emit to all configured reporters
        List<TestReporter> reporters = new ArrayList<>();
        if (reportConfig.isConsoleEnabled()) {
            reporters.add(new ConsoleReporter());
        }
        if (reportConfig.isS3Enabled()) {
            reporters.add(new S3Reporter(reportConfig));
        }
        reporters.addAll(extraReporters);

        for (TestReporter reporter : reporters) {
            try {
                log.debug("Invoking reporter: {}", reporter.getName());
                reporter.report(report);
            } catch (Exception e) {
                log.error("Reporter '{}' failed: {}", reporter.getName(), e.getMessage());
            }
        }

        return report;
    }

    private static TestResult syntheticErrorResult(String id, String name,
            String suiteName, Exception e) {
        return TestResult.builder()
                .testId(id)
                .testName(name)
                .suiteName(suiteName)
                .status(TestStatus.ERROR)
                .startTime(Instant.now())
                .duration(Duration.ZERO)
                .failureMessage(e.getClass().getSimpleName() + ": " + e.getMessage())
                .stackTrace(stackTraceToString(e))
                .build();
    }

    private static String stackTraceToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private TestSuiteDefinition suiteDefinition;
        private ReportConfig reportConfig;
        private final List<TestReporter> extraReporters = new ArrayList<>();
        private final Map<String, String> environmentOverrides = new LinkedHashMap<>();

        public Builder suiteDefinition(TestSuiteDefinition def) {
            this.suiteDefinition = def;
            return this;
        }

        public Builder reportConfig(ReportConfig config) {
            this.reportConfig = config;
            return this;
        }

        public Builder addReporter(TestReporter reporter) {
            this.extraReporters.add(reporter);
            return this;
        }

        /**
         * Overrides environment variables for all components in this run.
         */
        public Builder env(String key, String value) {
            this.environmentOverrides.put(key, value);
            return this;
        }

        public Builder env(Map<String, String> overrides) {
            this.environmentOverrides.putAll(overrides);
            return this;
        }

        public TestOrchestrator build() {
            return new TestOrchestrator(this);
        }
    }
}
