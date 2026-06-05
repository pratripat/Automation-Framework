package com.banking.testframework.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * General-purpose infrastructure container (PostgreSQL, Kafka, Redis, etc.).
 *
 * Fixed: exposeEnv() now stores plain String values resolved at build time
 * (after start) rather than lambdas, so getExposedEnvironment() always returns
 * correct values regardless of when it is called.
 */
public class GenericInfraComponent implements DeployableComponent {

    private static final Logger log = LoggerFactory.getLogger(GenericInfraComponent.class);

    private final String alias;
    private final String dockerImage;
    private final int containerPort;
    private final WaitStrategy waitStrategy;
    private final Map<String, String> staticEnv;
    private final List<String> dependsOn;
    private final List<ExposedEnvEntry> exposedEnvEntries;
    private final Consumer<GenericContainer<?>> containerCustomizer;
    private final String componentType;

    // Resolved after start() — populated by resolveExposedEnv()
    private final Map<String, String> resolvedExposedEnv = new LinkedHashMap<>();

    private GenericContainer<?> container;

    private GenericInfraComponent(Builder builder) {
        this.alias = builder.alias;
        this.dockerImage = builder.dockerImage;
        this.containerPort = builder.containerPort;
        this.waitStrategy = builder.waitStrategy;
        this.staticEnv = Collections.unmodifiableMap(builder.staticEnv);
        this.dependsOn = Collections.unmodifiableList(builder.dependsOn);
        this.exposedEnvEntries = Collections.unmodifiableList(builder.exposedEnvEntries);
        this.containerCustomizer = builder.containerCustomizer;
        this.componentType = builder.componentType;
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
        return componentType;
    }

    @Override
    @SuppressWarnings("resource")
    public void build(Network network, Map<String, String> extraEnv) {
        Map<String, String> mergedEnv = new LinkedHashMap<>(staticEnv);
        mergedEnv.putAll(extraEnv);

        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(dockerImage))
                .withExposedPorts(containerPort)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withEnv(mergedEnv)
                .withLogConsumer(new Slf4jLogConsumer(
                        LoggerFactory.getLogger("container." + alias)))
                .waitingFor(waitStrategy.withStartupTimeout(Duration.ofMinutes(2)));

        if (containerCustomizer != null) {
            containerCustomizer.accept(c);
        }
        this.container = c;
    }

    @Override
    public void start() {
        Objects.requireNonNull(container);
        container.start();
        // Resolve exposed env values NOW — container is running, mapped port is known
        resolveExposedEnv();
        log.info("[{}] Started. Exposed env: {}", alias, resolvedExposedEnv);
    }

    /**
     * Resolves all exposeEnv() lambdas now that the container is started and
     * mapped ports are available.
     */
    private void resolveExposedEnv() {
        for (ExposedEnvEntry entry : exposedEnvEntries) {
            try {
                String value = entry.valueSupplier().apply(this);
                resolvedExposedEnv.put(entry.key(), value);
                log.debug("[{}] Resolved exposed env: {}={}", alias, entry.key(), value);
            } catch (Exception e) {
                log.error("[{}] Failed to resolve exposed env key '{}': {}",
                        alias, entry.key(), e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        if (container != null && container.isRunning()) {
            log.info("[{}] Stopping infra container", alias);
            container.stop();
        }
    }

    @Override
    public String getBaseUrl() {
        Objects.requireNonNull(container);
        return container.getHost() + ":" + container.getMappedPort(containerPort);
    }

    /**
     * Returns the pre-resolved exposed environment variables. These are
     * resolved in start() so they are always available when ContainerRegistry
     * calls this method after start().
     */
    @Override
    public Map<String, String> getExposedEnvironment() {
        return Collections.unmodifiableMap(resolvedExposedEnv);
    }

    @Override
    public String getRecentLogs(int maxLines) {
        if (container == null) {
            return "(not started)";
        }
        try {
            String logs = container.getLogs();
            String[] lines = logs.split("\n");
            int start = Math.max(0, lines.length - maxLines);
            return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
        } catch (Exception e) {
            return "(log retrieval failed: " + e.getMessage() + ")";
        }
    }

    /**
     * Returns the mapped host port for the primary container port.
     */
    public int getMappedPort() {
        return container.getMappedPort(containerPort);
    }

    // ── Builder ────────────────────────────────────────────────────────────
    public static Builder builder(String alias, String dockerImage) {
        return new Builder(alias, dockerImage);
    }

    public static class Builder {

        private final String alias;
        private final String dockerImage;
        private int containerPort = 80;
        private WaitStrategy waitStrategy = Wait.forListeningPort();
        private final Map<String, String> staticEnv = new LinkedHashMap<>();
        private final List<String> dependsOn = new ArrayList<>();
        private final List<ExposedEnvEntry> exposedEnvEntries = new ArrayList<>();
        private Consumer<GenericContainer<?>> containerCustomizer;
        private String componentType = "infra";

        public Builder(String alias, String dockerImage) {
            this.alias = alias;
            this.dockerImage = dockerImage;
        }

        public Builder port(int port) {
            this.containerPort = port;
            return this;
        }

        public Builder waitStrategy(WaitStrategy strategy) {
            this.waitStrategy = strategy;
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

        public Builder componentType(String type) {
            this.componentType = type;
            return this;
        }

        /**
         * Declares an environment variable that this component will expose to
         * dependent components after it starts.
         *
         * The value supplier receives the started component so it can reference
         * resolved host/port/alias information.
         */
        public Builder exposeEnv(String envKey,
                Function<GenericInfraComponent, String> valueSupplier) {
            this.exposedEnvEntries.add(new ExposedEnvEntry(envKey, valueSupplier));
            return this;
        }

        public Builder customize(Consumer<GenericContainer<?>> customizer) {
            this.containerCustomizer = customizer;
            return this;
        }

        public GenericInfraComponent build() {
            return new GenericInfraComponent(this);
        }
    }

    private record ExposedEnvEntry(String key,
            Function<GenericInfraComponent, String> valueSupplier) {

    }
}
