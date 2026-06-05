package com.banking.testframework.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;

/**
 * Deploys a Spring Boot application running inside Tomcat as a Docker container.
 *
 * <p>Waits for the Spring actuator {@code /actuator/health} endpoint to return
 * HTTP 200 before reporting as ready, so dependent services don't start until
 * the application context is fully initialised.</p>
 *
 * <pre>{@code
 * new TomcatServiceComponent.Builder("channel-service", "banking/channel-service:1.2.0")
 *     .port(8080)
 *     .healthCheckPath("/actuator/health")
 *     .startupTimeout(Duration.ofMinutes(3))
 *     .env("SPRING_PROFILES_ACTIVE", "integration")
 *     .dependsOn("core-banking-mock", "postgres")
 *     .build();
 * }</pre>
 */
public class TomcatServiceComponent implements DeployableComponent {

    private static final Logger log = LoggerFactory.getLogger(TomcatServiceComponent.class);

    private final String alias;
    private final String dockerImage;
    private final int containerPort;
    private final String healthCheckPath;
    private final Duration startupTimeout;
    private final Map<String, String> staticEnv;
    private final List<String> dependsOn;

    private GenericContainer<?> container;

    private TomcatServiceComponent(Builder builder) {
        this.alias = builder.alias;
        this.dockerImage = builder.dockerImage;
        this.containerPort = builder.containerPort;
        this.healthCheckPath = builder.healthCheckPath;
        this.startupTimeout = builder.startupTimeout;
        this.staticEnv = Collections.unmodifiableMap(builder.staticEnv);
        this.dependsOn = Collections.unmodifiableList(builder.dependsOn);
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
        return "tomcat-service";
    }

    @Override
    @SuppressWarnings("resource")
    public void build(Network network, Map<String, String> extraEnv) {
        Map<String, String> mergedEnv = new LinkedHashMap<>(staticEnv);
        mergedEnv.putAll(extraEnv); // extraEnv (from suite + dependencies) wins

        container = new GenericContainer<>(DockerImageName.parse(dockerImage))
                .withExposedPorts(containerPort)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withEnv(mergedEnv)
                .withLogConsumer(new Slf4jLogConsumer(
                        LoggerFactory.getLogger("container." + alias)).withSeparateOutputStreams())
                .waitingFor(new WaitAllStrategy()
                        .withStrategy(Wait.forListeningPort())
                        .withStrategy(Wait.forHttp(healthCheckPath)
                                .forStatusCode(200)
                                .withStartupTimeout(startupTimeout)));
    }

    @Override
    public void start() {
        Objects.requireNonNull(container, "build() must be called before start()");
        container.start();
    }

    @Override
    public void stop() {
        if (container != null && container.isRunning()) {
            log.info("[{}] Stopping container", alias);
            container.stop();
        }
    }

    @Override
    public String getBaseUrl() {
        Objects.requireNonNull(container, "start() must be called before getBaseUrl()");
        return "http://" + container.getHost() + ":" + container.getMappedPort(containerPort);
    }

    @Override
    public String getRecentLogs(int maxLines) {
        if (container == null) return "(container not started)";
        try {
            String logs = container.getLogs();
            String[] lines = logs.split("\n");
            int start = Math.max(0, lines.length - maxLines);
            return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
        } catch (Exception e) {
            return "(could not retrieve logs: " + e.getMessage() + ")";
        }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder(String alias, String dockerImage) {
        return new Builder(alias, dockerImage);
    }

    public static class Builder {
        private final String alias;
        private final String dockerImage;
        private int containerPort = 8080;
        private String healthCheckPath = "/actuator/health";
        private Duration startupTimeout = Duration.ofMinutes(2);
        private final Map<String, String> staticEnv = new LinkedHashMap<>();
        private final List<String> dependsOn = new ArrayList<>();

        public Builder(String alias, String dockerImage) {
            this.alias = Objects.requireNonNull(alias);
            this.dockerImage = Objects.requireNonNull(dockerImage);
        }

        public Builder port(int port) {
            this.containerPort = port;
            return this;
        }

        public Builder healthCheckPath(String path) {
            this.healthCheckPath = path;
            return this;
        }

        public Builder startupTimeout(Duration timeout) {
            this.startupTimeout = timeout;
            return this;
        }

        public Builder env(String key, String value) {
            this.staticEnv.put(key, value);
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.staticEnv.putAll(env);
            return this;
        }

        public Builder dependsOn(String... aliases) {
            this.dependsOn.addAll(Arrays.asList(aliases));
            return this;
        }

        public TomcatServiceComponent build() {
            return new TomcatServiceComponent(this);
        }
    }
}
