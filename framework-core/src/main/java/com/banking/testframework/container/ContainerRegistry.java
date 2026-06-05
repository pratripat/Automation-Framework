package com.banking.testframework.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.util.*;

/**
 * Manages the full lifecycle of all {@link DeployableComponent} instances in a
 * suite.
 */
public class ContainerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ContainerRegistry.class);

    private final CleanupRegistry cleanupRegistry;
    private final List<DeployableComponent> orderedComponents = new ArrayList<>();
    private final Map<String, String> serviceUrls = new LinkedHashMap<>();
    private final Map<String, String> accumulatedEnv = new LinkedHashMap<>();

    public ContainerRegistry(CleanupRegistry cleanupRegistry) {
        this.cleanupRegistry = cleanupRegistry;
    }

    public void deployAll(List<DeployableComponent> components,
            Network network,
            Map<String, String> extraEnv) {
        List<DeployableComponent> sorted = topologicalSort(components);
        accumulatedEnv.putAll(extraEnv);
        for (DeployableComponent component : sorted) {
            deploy(component, network);
        }
    }

    private void deploy(DeployableComponent component, Network network) {
        String alias = component.getAlias();
        log.info("[{}] Building container ({})…", alias, component.getComponentType());

        Map<String, String> envForComponent = new LinkedHashMap<>(accumulatedEnv);
        component.build(network, envForComponent);

        log.info("[{}] Starting container…", alias);
        long startMs = System.currentTimeMillis();
        component.start();
        long elapsedMs = System.currentTimeMillis() - startMs;

        String url = component.getBaseUrl();
        serviceUrls.put(alias, url);
        orderedComponents.add(component);

        accumulatedEnv.putAll(component.getExposedEnvironment());

        // Use Runnable explicitly to avoid ambiguity — component::stop is a Runnable
        cleanupRegistry.register("stop-container:" + alias, (Runnable) component::stop);

        log.info("[{}] Started successfully in {}ms → {}", alias, elapsedMs, url);
    }

    public Map<String, String> getServiceUrls() {
        return Collections.unmodifiableMap(serviceUrls);
    }

    public String getServiceUrl(String alias) {
        String url = serviceUrls.get(alias);
        if (url == null) {
            throw new IllegalArgumentException("No component deployed with alias: " + alias);
        }
        return url;
    }

    public List<DeployableComponent> getOrderedComponents() {
        return Collections.unmodifiableList(orderedComponents);
    }

    private List<DeployableComponent> topologicalSort(List<DeployableComponent> components) {
        Map<String, DeployableComponent> byAlias = new LinkedHashMap<>();
        for (DeployableComponent c : components) {
            if (byAlias.put(c.getAlias(), c) != null) {
                throw new IllegalArgumentException("Duplicate component alias: " + c.getAlias());
            }
        }

        for (DeployableComponent c : components) {
            for (String dep : c.getDependsOn()) {
                if (!byAlias.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "Component '" + c.getAlias() + "' depends on '" + dep
                            + "' which is not registered in the suite");
                }
            }
        }

        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String alias : byAlias.keySet()) {
            inDegree.put(alias, 0);
            dependents.put(alias, new ArrayList<>());
        }
        for (DeployableComponent c : components) {
            for (String dep : c.getDependsOn()) {
                dependents.get(dep).add(c.getAlias());
                inDegree.merge(c.getAlias(), 1, Integer::sum);
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((alias, degree) -> {
            if (degree == 0) {
                queue.add(alias);
            }
        });

        List<DeployableComponent> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String alias = queue.poll();
            sorted.add(byAlias.get(alias));
            for (String dependent : dependents.get(alias)) {
                int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (sorted.size() != components.size()) {
            throw new IllegalStateException(
                    "Circular dependency detected in component definitions.");
        }

        log.info("Resolved component startup order: {}", sorted.stream()
                .map(DeployableComponent::getAlias).toList());
        return sorted;
    }
}
