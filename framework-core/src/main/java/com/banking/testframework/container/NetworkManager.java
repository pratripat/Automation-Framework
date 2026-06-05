package com.banking.testframework.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.util.UUID;

/**
 * Creates and manages an isolated Docker bridge network for a single test suite run.
 *
 * <p>Each run uses a UUID-suffixed network name so multiple CI jobs can execute
 * on the same Docker host without interfering with each other.</p>
 */
public class NetworkManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkManager.class);

    private final String networkName;
    private Network network;

    public NetworkManager(String suiteName) {
        // UUID suffix prevents collisions between parallel suite runs
        this.networkName = suiteName + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Creates the Docker bridge network. Idempotent — safe to call more than once.
     *
     * @return the created {@link Network} instance
     */
    public Network createNetwork() {
        if (network == null) {
            log.info("Creating Docker network '{}'", networkName);
            network = Network.builder()
                    .createNetworkCmdModifier(cmd -> cmd.withName(networkName))
                    .build();
            log.info("Docker network '{}' created", networkName);
        }
        return network;
    }

    /** Returns the active network (null if not yet created). */
    public Network getNetwork() {
        return network;
    }

    /** Returns the network name (including UUID suffix). */
    public String getNetworkName() {
        return networkName;
    }

    /**
     * Removes the Docker network. Safe to call even if {@link #createNetwork()}
     * was never called.
     */
    @Override
    public void close() {
        if (network != null) {
            try {
                log.info("Removing Docker network '{}'", networkName);
                network.close();
                network = null;
                log.info("Docker network '{}' removed", networkName);
            } catch (Exception e) {
                log.warn("Failed to remove Docker network '{}': {}", networkName, e.getMessage());
            }
        }
    }
}
