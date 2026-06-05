package com.banking.testframework.test;

import java.util.List;

/**
 * Implemented by test projects to declare what should be deployed and which
 * tests should be run.
 *
 * <p>This is the primary extension point for the framework. Each test project
 * implements this interface (or extends {@link AbstractTestSuiteDefinition})
 * and passes an instance to {@link com.banking.testframework.lifecycle.TestOrchestrator}.</p>
 *
 * <p>Separation of concerns:</p>
 * <ul>
 *   <li>The framework handles: Docker lifecycle, health-checking, teardown,
 *       result collection, and reporting.</li>
 *   <li>The implementor handles: which components to deploy, environment
 *       configuration, stub definitions, test logic, and assertions.</li>
 * </ul>
 */
public interface TestSuiteDefinition {

    /**
     * Unique name for this test suite. Used as a grouping key in reports and
     * as a suffix on Docker network names to allow parallel suite execution.
     */
    String getSuiteName();

    /**
     * The ordered list of deployable components (services, mocks, infrastructure).
     * The framework deploys them in order, respecting
     * {@link com.banking.testframework.container.DeployableComponent#getDependsOn()} constraints.
     */
    List<com.banking.testframework.container.DeployableComponent> getComponents();

    /**
     * Suite-level properties injected into {@link TestContext#getProperty}.
     * Typical uses: shared credentials, environment names, feature flags.
     */
    default java.util.Map<String, String> getSuiteProperties() {
        return java.util.Collections.emptyMap();
    }

    /**
     * The test cases to execute, in registration order.
     * The framework respects {@link TestCaseDefinition#getDependsOn()} for skipping.
     */
    List<TestCaseDefinition> getTestCases();

    /**
     * Optional hook called once after all components are deployed and health-checked,
     * before any test cases run. Use for global test data setup.
     *
     * @param ctx  the fully-initialized test context
     */
    default void beforeAll(TestContext ctx) throws Exception {
        // no-op by default
    }

    /**
     * Optional hook called once after all test cases have run, before teardown.
     * Use for assertions on aggregate state, or final data cleanup.
     *
     * @param ctx      the test context
     * @param results  immutable list of results from all test cases
     */
    default void afterAll(TestContext ctx, List<TestResult> results) throws Exception {
        // no-op by default
    }

    /**
     * Optional hook called before each individual test case.
     * Use for resetting WireMock stubs, clearing caches, etc.
     *
     * @param ctx        the test context
     * @param testCase   the definition of the test about to run
     */
    default void beforeEach(TestContext ctx, TestCaseDefinition testCase) throws Exception {
        // no-op by default
    }

    /**
     * Optional hook called after each individual test case, regardless of outcome.
     *
     * @param ctx     the test context
     * @param result  the result of the test that just ran
     */
    default void afterEach(TestContext ctx, TestResult result) throws Exception {
        // no-op by default
    }
}
