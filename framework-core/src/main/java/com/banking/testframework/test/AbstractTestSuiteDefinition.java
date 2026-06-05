package com.banking.testframework.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.banking.testframework.container.DeployableComponent;

/**
 * Convenient base class for {@link TestSuiteDefinition} implementations.
 *
 * <p>Provides a builder-style {@code addComponent} / {@code addTest} API so
 * implementing classes can register everything in their constructor or an
 * {@code init()} method without needing to maintain separate collections.</p>
 *
 * <pre>{@code
 * public class FundsTransferSuite extends AbstractTestSuiteDefinition {
 *
 *     public FundsTransferSuite() {
 *         addComponent(new TomcatServiceComponent("channel-service", "banking/channel:latest"));
 *         addComponent(BankingMockBuilder.forCBS("flexcube-mock").build());
 *
 *         addProperty("env", "integration");
 *
 *         addTest(TestCaseDefinition.builder()
 *             .id("FT-001")
 *             .name("Successful funds transfer")
 *             .testCase(ctx -> { ... })
 *             .build());
 *     }
 *
 *     @Override
 *     public String getSuiteName() { return "funds-transfer-suite"; }
 * }
 * }</pre>
 */
public abstract class AbstractTestSuiteDefinition implements TestSuiteDefinition {

    private final List<DeployableComponent> components = new ArrayList<>();
    private final List<TestCaseDefinition> testCases = new ArrayList<>();
    private final Map<String, String> suiteProperties = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Registration helpers
    // -------------------------------------------------------------------------

    protected void addComponent(DeployableComponent component) {
        components.add(component);
    }

    protected void addTest(TestCaseDefinition definition) {
        testCases.add(definition);
    }

    protected void addTest(String id, String name, TestCase testCase) {
        addTest(TestCaseDefinition.of(id, name, testCase));
    }

    protected void addProperty(String key, String value) {
        suiteProperties.put(key, value);
    }

    // -------------------------------------------------------------------------
    // TestSuiteDefinition implementation
    // -------------------------------------------------------------------------

    @Override
    public List<DeployableComponent> getComponents() {
        return List.copyOf(components);
    }

    @Override
    public List<TestCaseDefinition> getTestCases() {
        return List.copyOf(testCases);
    }

    @Override
    public Map<String, String> getSuiteProperties() {
        return Map.copyOf(suiteProperties);
    }
}
