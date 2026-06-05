package com.banking.testframework.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Runtime context injected into every {@link TestCase}.
 *
 * <p>Provides access to deployed service base URLs, mock servers, a pre-configured
 * HTTP client, structured logging, and arbitrary key-value metadata set during
 * suite setup. Implementations should treat this as read-only; the framework
 * populates it before handing it to test cases.</p>
 */
public class TestContext {

    private static final Logger log = LoggerFactory.getLogger(TestContext.class);

    /** Base URLs keyed by the component's alias (e.g. "channel-service" → "http://localhost:32789"). */
    private final Map<String, String> serviceUrls;

    /** WireMock server instances keyed by mock component alias. */
    private final Map<String, WireMockServer> mockServers;

    /** Shared HTTP client for firing requests inside tests. */
    private final HttpTestClient httpClient;

    /** Suite-level properties set by the test suite definition (e.g. shared credentials). */
    private final Map<String, String> suiteProperties;

    /** Mutable per-test metadata bag — tests can write to this for reporting. */
    private final Map<String, String> testMetadata = new HashMap<>();

    /** Suite-level metadata persisted across tests (tests may record values for later tests). */
    private final Map<String, String> suiteMetadata = new HashMap<>();

    public TestContext(Map<String, String> serviceUrls,
                       Map<String, WireMockServer> mockServers,
                       HttpTestClient httpClient,
                       Map<String, String> suiteProperties) {
        this.serviceUrls = Collections.unmodifiableMap(serviceUrls);
        this.mockServers = Collections.unmodifiableMap(mockServers);
        this.httpClient = httpClient;
        this.suiteProperties = Collections.unmodifiableMap(suiteProperties);
    }

    // -------------------------------------------------------------------------
    // Service URL resolution
    // -------------------------------------------------------------------------

    /**
     * Returns the base HTTP URL for a deployed service component.
     *
     * @param alias  the component alias as registered in the suite config
     * @throws IllegalArgumentException if no service with that alias was deployed
     */
    public String getServiceUrl(String alias) {
        String url = serviceUrls.get(alias);
        if (url == null) {
            throw new IllegalArgumentException(
                    "No service deployed with alias '" + alias + "'. Available: " + serviceUrls.keySet());
        }
        return url;
    }

    /** Returns all deployed service URLs. */
    public Map<String, String> getAllServiceUrls() {
        return serviceUrls;
    }

    // -------------------------------------------------------------------------
    // Mock server access
    // -------------------------------------------------------------------------

    /**
     * Returns the WireMockServer instance for a mock downstream component so that
     * tests can register stubs, verify calls, and reset state.
     *
     * @param alias  the mock component alias
     * @throws IllegalArgumentException if no mock with that alias was deployed
     */
    public WireMockServer getMockServer(String alias) {
        WireMockServer server = mockServers.get(alias);
        if (server == null) {
            throw new IllegalArgumentException(
                    "No mock server deployed with alias '" + alias + "'. Available: " + mockServers.keySet());
        }
        return server;
    }

    /** Returns true if a mock server with the given alias is present. */
    public boolean hasMockServer(String alias) {
        return mockServers.containsKey(alias);
    }

    // -------------------------------------------------------------------------
    // HTTP client
    // -------------------------------------------------------------------------

    /** Returns the shared {@link HttpTestClient}. */
    public HttpTestClient getHttpClient() {
        return httpClient;
    }

    // -------------------------------------------------------------------------
    // Suite properties
    // -------------------------------------------------------------------------

    /**
     * Returns a suite-level property (e.g. shared test credentials, environment name).
     *
     * @throws IllegalArgumentException if the property is not set
     */
    public String getProperty(String key) {
        String value = suiteProperties.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Suite property '" + key + "' is not set. Available: " + suiteProperties.keySet());
        }
        return value;
    }

    /** Returns a suite-level property, or the given default if not set. */
    public String getProperty(String key, String defaultValue) {
        return suiteProperties.getOrDefault(key, defaultValue);
    }

    // -------------------------------------------------------------------------
    // Per-test metadata (for reporting)
    // -------------------------------------------------------------------------

    /**
     * Records a metadata entry that will appear in the test report.
     * Useful for capturing correlation IDs, CBS reference numbers, etc.
     */
    public void recordMetadata(String key, String value) {
        // Persisted at suite-level so tests can pass values to later tests
        suiteMetadata.put(key, value);
    }

    /** Returns the metadata accumulated by the currently running test and suite-level values. */
    public Map<String, String> getTestMetadata() {
        Map<String, String> merged = new LinkedHashMap<>(suiteMetadata);
        merged.putAll(testMetadata);
        return Collections.unmodifiableMap(merged);
    }

    /** Clears per-test metadata — called by the framework between tests. */
    public void clearTestMetadata() {
        testMetadata.clear();
    }

    // -------------------------------------------------------------------------
    // Logging
    // -------------------------------------------------------------------------

    /** Returns a logger scoped to the test context. */
    public Logger getLogger() {
        return log;
    }
}
