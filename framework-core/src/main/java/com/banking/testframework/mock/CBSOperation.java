package com.banking.testframework.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;

/**
 * Represents a single Core Banking System (CBS) operation that can be stubbed.
 *
 * <p>Each operation is identified by a name (e.g. "FUNDS_TRANSFER") and provides
 * one or more WireMock stub mappings. Implementations are created via
 * {@link BankingMockBuilder}.</p>
 */
public interface CBSOperation {

    /** Canonical name used for logging and verification (e.g. "FUNDS_TRANSFER"). */
    String getOperationName();

    /**
     * Registers this operation's stub(s) on the given WireMock server.
     * Called once when the mock component starts (for default stubs) and
     * can be called again by tests to override the default behaviour.
     */
    void registerStub(WireMockServer server);

    /**
     * Returns the underlying WireMock mapping builder so test code can
     * override individual fields before registering.
     */
    MappingBuilder getMappingBuilder();
}
