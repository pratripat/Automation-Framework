package com.banking.testframework.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Contract for any component that the framework can deploy into Docker.
 *
 * <p>Implementations cover: Spring Boot / Tomcat microservices, WireMock-based
 * CBS mocks, infrastructure services (Postgres, Kafka, Redis), and arbitrary
 * custom containers.</p>
 *
 * <p>The framework calls the methods below in this order during the deployment
 * phase:</p>
 * <ol>
 *   <li>{@link #getAlias()} — used as the Docker network alias and service lookup key</li>
 *   <li>{@link #getDependsOn()} — framework resolves startup ordering</li>
 *   <li>{@link #build(Network, Map)} — constructs (but does not start) the container</li>
 *   <li>{@link #start()} — starts the built container</li>
 *   <li>{@link #getBaseUrl()} — called after start to populate TestContext</li>
 *   <li>{@link #stop()} — called during teardown (always, even on failure)</li>
 * </ol>
 */
public interface DeployableComponent {

    /**
     * Unique alias for this component within the suite.
     * Used as the Docker network alias (so other containers resolve it by this name)
     * and as the key for {@link com.banking.testframework.test.TestContext#getServiceUrl}.
     */
    String getAlias();

    /**
     * Aliases of other components that must be started before this one.
     * The framework uses this to build a startup order graph.
     */
    default List<String> getDependsOn() {
        return Collections.emptyList();
    }

    /**
     * Builds the Testcontainers {@link GenericContainer} (or subclass), attaches
     * it to {@code network} with the component's alias, and applies any
     * environment overrides from {@code extraEnv}.
     *
     * <p>This method must NOT start the container — the framework calls
     * {@link #start()} separately so it can interleave health-check logic.</p>
     *
     * @param network   the suite-scoped Docker bridge network
     * @param extraEnv  environment variable overrides from the suite config
     */
    void build(Network network, Map<String, String> extraEnv);

    /**
     * Starts the previously built container and blocks until Testcontainers'
     * wait strategy reports it ready.
     *
     * <p>Implementors should apply appropriate wait strategies (HTTP health
     * check, log message wait, port listen) inside {@link #build} rather than
     * sleeping here.</p>
     */
    void start();

    /**
     * Stops and removes the container. Must be idempotent — safe to call even
     * if {@link #start()} was never called or already called.
     */
    void stop();

    /**
     * Returns the HTTP base URL that other containers and tests can use to
     * reach this service from outside Docker (i.e. mapped host port).
     *
     * <p>Called only after {@link #start()} has succeeded.</p>
     */
    String getBaseUrl();

    /**
     * Returns the last N lines of this container's stdout+stderr logs.
     * Called by the framework when a test fails to capture context.
     *
     * @param maxLines  number of trailing lines to return
     */
    String getRecentLogs(int maxLines);

    /**
     * Returns container-level environment variables that should be advertised
     * to dependent components (e.g. a Postgres component advertising its JDBC URL).
     *
     * <p>The framework merges these into the extra-env map passed to dependents.</p>
     */
    default Map<String, String> getExposedEnvironment() {
        return Collections.emptyMap();
    }

    /**
     * Component type label used in log output and reports
     * (e.g. "tomcat-service", "cbs-mock", "postgres").
     */
    default String getComponentType() {
        return "generic";
    }
}
