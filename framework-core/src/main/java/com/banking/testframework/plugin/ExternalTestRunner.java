package com.banking.testframework.plugin;

import com.banking.testframework.test.TestContext;
import com.banking.testframework.test.TestResult;
import com.banking.testframework.test.TestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extension point for running non-Java test suites (Playwright, Jest, Cypress, etc.)
 * as part of the framework lifecycle.
 *
 * <p>The Java framework manages Docker lifecycle and health-checking; external test
 * runners receive service URLs via environment variables and their exit code + JUnit
 * XML output is parsed back into {@link TestResult} objects for unified reporting.</p>
 */
public interface ExternalTestRunner {

    /**
     * Executes the external test process.
     *
     * @param ctx         the fully-initialized test context (provides service URLs)
     * @param workingDir  directory to run the command from
     * @return            list of test results parsed from the runner's output
     */
    List<TestResult> run(TestContext ctx, Path workingDir) throws Exception;

    /** Descriptive name for log output. */
    String getName();
}


/**
 * Runs a Node.js test suite (Playwright, Jest, or any test framework that
 * produces JUnit XML output) as a subprocess.
 *
 * <p>Service URLs from the Java framework are injected as environment variables
 * with a configurable prefix. For example, with prefix "TEST_":
 * <ul>
 *   <li>channel-service base URL → {@code TEST_CHANNEL_SERVICE_URL}</li>
 *   <li>core-banking-mock base URL → {@code TEST_CORE_BANKING_MOCK_URL}</li>
 * </ul>
 *
 * <pre>{@code
 * NodeTestRunner playwrightRunner = NodeTestRunner.builder()
 *     .command("npx", "playwright", "test", "--reporter=junit")
 *     .workingDir(Path.of("web-console-tests"))
 *     .junitOutputPath("test-results/results.xml")
 *     .envPrefix("TEST_")
 *     .timeout(Duration.ofMinutes(10))
 *     .build();
 * }</pre>
 */
class NodeTestRunner implements ExternalTestRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeTestRunner.class);

    private final List<String> command;
    private final String envPrefix;
    private final Duration timeout;
    private final String junitOutputRelPath;

    private NodeTestRunner(Builder builder) {
        this.command = List.copyOf(builder.command);
        this.envPrefix = builder.envPrefix;
        this.timeout = builder.timeout;
        this.junitOutputRelPath = builder.junitOutputRelPath;
    }

    @Override
    public String getName() { return "NodeTestRunner"; }

    @Override
    public List<TestResult> run(TestContext ctx, Path workingDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);

        // Inject service URLs as env vars
        Map<String, String> env = pb.environment();
        ctx.getAllServiceUrls().forEach((alias, url) -> {
            String envKey = envPrefix + alias.toUpperCase().replace("-", "_") + "_URL";
            env.put(envKey, url);
            log.debug("Injected env: {}={}", envKey, url);
        });

        log.info("[NodeTestRunner] Running: {}", String.join(" ", command));
        Instant start = Instant.now();

        Process process = pb.start();

        // Stream output to our logger
        List<String> outputLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLines.add(line);
                log.info("[node-test] {}", line);
            }
        }

        boolean finished = process.waitFor(
                timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            log.error("[NodeTestRunner] Timed out after {}s", timeout.toSeconds());
        }

        int exitCode = finished ? process.exitValue() : -1;
        Duration duration = Duration.between(start, Instant.now());

        log.info("[NodeTestRunner] Exited with code {} in {}ms", exitCode, duration.toMillis());

        // Parse JUnit XML if available
        Path junitFile = workingDir.resolve(junitOutputRelPath);
        if (junitFile.toFile().exists()) {
            return JUnitXmlParser.parse(junitFile, duration);
        }

        // Fallback: single synthetic result based on exit code
        return List.of(TestResult.builder()
                .testId("node-suite")
                .testName("Node.js test suite (" + String.join(" ", command) + ")")
                .suiteName("external")
                .status(exitCode == 0 ? TestStatus.PASSED : TestStatus.FAILED)
                .startTime(start)
                .duration(duration)
                .failureMessage(exitCode != 0
                        ? "Process exited with code " + exitCode
                        : null)
                .build());
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<String> command = List.of("npx", "playwright", "test");
        private String envPrefix = "TEST_";
        private Duration timeout = Duration.ofMinutes(10);
        private String junitOutputRelPath = "test-results/junit.xml";

        public Builder command(String... cmd) { this.command = List.of(cmd); return this; }
        public Builder envPrefix(String prefix) { this.envPrefix = prefix; return this; }
        public Builder timeout(Duration t) { this.timeout = t; return this; }
        public Builder junitOutputPath(String path) { this.junitOutputRelPath = path; return this; }

        public NodeTestRunner build() { return new NodeTestRunner(this); }
    }
}
