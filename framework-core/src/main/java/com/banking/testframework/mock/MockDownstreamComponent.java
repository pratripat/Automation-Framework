package com.banking.testframework.mock;

import com.banking.testframework.container.DeployableComponent;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

/**
 * A mock downstream system backed by an embedded WireMock server.
 *
 * WireMock runs in-process on the test JVM. Because Docker containers cannot
 * reach the test JVM via "localhost", this component resolves the correct
 * host-reachable address:
 *
 * - On Linux CI/Docker: uses the Docker bridge IP (172.17.0.1 or whichever
 * docker0 interface is present). - On Mac/Windows Docker Desktop: uses
 * "host.docker.internal".
 *
 * The base URL returned by getBaseUrl() is always the localhost URL (for
 * test-code use). The Docker-reachable URL is exposed via
 * getExposedEnvironment() so dependent service containers can reach it.
 */
public class MockDownstreamComponent implements DeployableComponent {

    private static final Logger log = LoggerFactory.getLogger(MockDownstreamComponent.class);

    private final String alias;
    private final List<String> dependsOn;
    private final List<StubInitializer> initialStubs;
    private final int fixedPort;

    /**
     * Env var key that dependent containers use to find this mock.
     */
    private final String urlEnvKey;

    private WireMockServer wireMockServer;

    private MockDownstreamComponent(Builder builder) {
        this.alias = builder.alias;
        this.dependsOn = Collections.unmodifiableList(builder.dependsOn);
        this.initialStubs = Collections.unmodifiableList(builder.initialStubs);
        this.fixedPort = builder.fixedPort;
        this.urlEnvKey = builder.urlEnvKey;
    }

    @Override
    public String getAlias() {
        return alias;
    }

    @Override
    public List<String> getDependsOn() {
        return dependsOn;
    }

    @Override
    public String getComponentType() {
        return "cbs-mock";
    }

    @Override
    public void build(Network network, Map<String, String> extraEnv) {
        WireMockConfiguration config = fixedPort > 0
                ? WireMockConfiguration.options().port(fixedPort)
                : WireMockConfiguration.options().dynamicPort();
        wireMockServer = new WireMockServer(config);
        log.info("[{}] WireMock configured on {}",
                alias, fixedPort > 0 ? "port " + fixedPort : "dynamic port");
    }

    @Override
    public void start() {
        Objects.requireNonNull(wireMockServer, "build() must be called before start()");
        wireMockServer.start();
        for (StubInitializer stub : initialStubs) {
            stub.register(wireMockServer);
        }
        log.info("[{}] WireMock started on port {} — Docker-reachable at {}",
                alias, wireMockServer.port(), getDockerReachableUrl());
    }

    @Override
    public void stop() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            log.info("[{}] Stopping WireMock", alias);
            wireMockServer.stop();
        }
    }

    /**
     * Returns localhost URL — for use by test code running on the test JVM.
     */
    @Override
    public String getBaseUrl() {
        Objects.requireNonNull(wireMockServer, "start() must be called before getBaseUrl()");
        return "http://localhost:" + wireMockServer.port();
    }

    /**
     * Returns the URL Docker containers should use to reach this WireMock
     * server. On Linux: uses the docker0 bridge IP. On Mac/Windows Docker
     * Desktop: uses host.docker.internal.
     */
    public String getDockerReachableUrl() {
        Objects.requireNonNull(wireMockServer, "start() must be called before getDockerReachableUrl()");
        return "http://" + resolveDockerHostAddress() + ":" + wireMockServer.port();
    }

    /**
     * Exposes the Docker-reachable URL as an env var so dependent service
     * containers can connect to this mock.
     */
    @Override
    public Map<String, String> getExposedEnvironment() {
        if (wireMockServer == null || !wireMockServer.isRunning()) {
            return Collections.emptyMap();
        }
        if (urlEnvKey == null || urlEnvKey.isBlank()) {
            return Collections.emptyMap();
        }
        String dockerUrl = getDockerReachableUrl();
        log.info("[{}] Exposing env {}={}", alias, urlEnvKey, dockerUrl);
        return Map.of(urlEnvKey, dockerUrl);
    }

    @Override
    public String getRecentLogs(int maxLines) {
        if (wireMockServer == null) {
            return "(WireMock not started)";
        }
        try {
            var requests = wireMockServer.getAllServeEvents();
            int start = Math.max(0, requests.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < requests.size(); i++) {
                var event = requests.get(i);
                sb.append(String.format("[WireMock] %s %s → %d%n",
                        event.getRequest().getMethod(),
                        event.getRequest().getUrl(),
                        event.getResponse().getStatus()));
            }
            return sb.isEmpty() ? "(no requests recorded)" : sb.toString();
        } catch (Exception e) {
            return "(log retrieval failed: " + e.getMessage() + ")";
        }
    }

    public WireMockServer getWireMockServer() {
        return wireMockServer;
    }

    public void resetAll() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.resetAll();
            for (StubInitializer stub : initialStubs) {
                stub.register(wireMockServer);
            }
        }
    }

    // ── Docker host resolution ─────────────────────────────────────────────
    /**
     * Resolves the IP address that Docker containers can use to reach the test
     * JVM host.
     *
     * Strategy: 1. If "host.docker.internal" resolves (Mac/Windows Docker
     * Desktop) → use it. 2. Otherwise scan network interfaces for the docker0
     * bridge (Linux). 3. Fall back to 172.17.0.1 (standard Docker bridge
     * default on Linux).
     */
    private static String resolveDockerHostAddress() {
        // Try host.docker.internal first (Docker Desktop on Mac/Windows)
        try {
            InetAddress hostDockerInternal = InetAddress.getByName("host.docker.internal");
            if (!hostDockerInternal.isLoopbackAddress()) {
                return "host.docker.internal";
            }
        } catch (IOException ignored) {
            // Not available — likely Linux
        }

        // On Linux, find the docker0 bridge interface IP
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                String name = iface.getName();
                if (name.startsWith("docker") || name.startsWith("br-")) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            log.debug("Resolved Docker host via interface {}: {}", name, addr.getHostAddress());
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not enumerate network interfaces: {}", e.getMessage());
        }

        // Standard Linux Docker bridge default
        log.warn("Could not resolve Docker host address — falling back to 172.17.0.1");
        return "172.17.0.1";
    }

    // ── Builder ────────────────────────────────────────────────────────────
    public static Builder builder(String alias) {
        return new Builder(alias);
    }

    public static class Builder {

        private final String alias;
        private final List<String> dependsOn = new ArrayList<>();
        private final List<StubInitializer> initialStubs = new ArrayList<>();
        private int fixedPort = 0;
        private String urlEnvKey;

        public Builder(String alias) {
            this.alias = alias;
        }

        public Builder dependsOn(String... aliases) {
            this.dependsOn.addAll(Arrays.asList(aliases));
            return this;
        }

        public Builder withStub(StubInitializer initializer) {
            this.initialStubs.add(initializer);
            return this;
        }

        public Builder fixedPort(int port) {
            this.fixedPort = port;
            return this;
        }

        /**
         * Env var key that will be set to the Docker-reachable URL of this
         * mock. Example: "CBS_BASE_URL" → dependent containers get
         * CBS_BASE_URL=http://172.17.0.1:PORT
         */
        public Builder exposeUrlAs(String envKey) {
            this.urlEnvKey = envKey;
            return this;
        }

        public MockDownstreamComponent build() {
            return new MockDownstreamComponent(this);
        }
    }

    @FunctionalInterface
    public interface StubInitializer {

        void register(WireMockServer server);
    }
}
