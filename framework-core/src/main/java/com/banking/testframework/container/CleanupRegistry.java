package com.banking.testframework.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks all resources allocated during a test suite run and ensures they are
 * released in reverse-registration order when the suite ends.
 *
 * <p>
 * Resources are released in LIFO order (last registered = first released),
 * which mirrors typical dependency teardown order.</p>
 *
 * <p>
 * A JVM shutdown hook is registered so that Ctrl+C or an abrupt JVM exit still
 * triggers cleanup.</p>
 */
public class CleanupRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CleanupRegistry.class);

    private final Deque<CleanupAction> actions = new ArrayDeque<>();
    private final Thread shutdownHook;
    private volatile boolean closed = false;

    public CleanupRegistry() {
        this.shutdownHook = new Thread(() -> {
            if (!closed) {
                log.warn("JVM shutdown detected — running emergency cleanup");
                runCleanup();
            }
        }, "cleanup-registry-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Registers a Runnable for cleanup.
     */
    public synchronized void register(String description, Runnable action) {
        actions.push(new CleanupAction(description, action));
        log.debug("Registered cleanup action: {}", description);
    }

    /**
     * Registers an {@link AutoCloseable} for cleanup. Explicitly named to avoid
     * ambiguity with the Runnable overload.
     */
    public synchronized void registerCloseable(String description, AutoCloseable resource) {
        register(description, () -> {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Error closing resource '{}': {}", description, e.getMessage());
            }
        });
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        runCleanup();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM already shutting down
        }
    }

    private void runCleanup() {
        log.info("Running cleanup for {} registered resources", actions.size());
        while (!actions.isEmpty()) {
            CleanupAction action = actions.pop();
            try {
                log.debug("Cleaning up: {}", action.description);
                action.action.run();
                log.debug("Cleaned up: {}", action.description);
            } catch (Exception e) {
                log.error("Cleanup failed for '{}': {}", action.description, e.getMessage(), e);
            }
        }
        log.info("Cleanup complete");
    }

    private record CleanupAction(String description, Runnable action) {

    }
}
