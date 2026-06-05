package com.banking.testframework.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Fluent builder for CBS mock components. Updated to support exposeUrlAs() so
 * dependent containers receive the Docker-reachable URL automatically.
 */
public class BankingMockBuilder {

    private static final Logger log = LoggerFactory.getLogger(BankingMockBuilder.class);
    public static final String DEFAULT_CBS_BASE_PATH = "/cbs/operations";

    private final String alias;
    private final List<OperationConfig> operations = new ArrayList<>();
    private long globalLatencyMs = 0;
    private double faultRate = 0.0;
    private String cbsBasePath = DEFAULT_CBS_BASE_PATH;
    private final List<String> dependsOn = new ArrayList<>();
    private String urlEnvKey;   // NEW: env var to expose Docker-reachable URL

    private BankingMockBuilder(String alias) {
        this.alias = alias;
    }

    public static BankingMockBuilder forCBS(String alias) {
        return new BankingMockBuilder(alias);
    }

    public BankingMockBuilder withOperation(String operationName,
            Function<Void, ResponseDefinitionBuilder> responseBuilder) {
        operations.add(new OperationConfig(
                operationName,
                cbsBasePath + "/" + operationName.toLowerCase().replace("_", "-"),
                responseBuilder.apply(null),
                globalLatencyMs));
        return this;
    }

    public BankingMockBuilder withOperation(String operationName,
            String urlPath,
            ResponseDefinitionBuilder response) {
        operations.add(new OperationConfig(operationName, urlPath, response, globalLatencyMs));
        return this;
    }

    public BankingMockBuilder withOperation(String operationName,
            ResponseDefinitionBuilder response,
            long latencyMs) {
        operations.add(new OperationConfig(
                operationName,
                cbsBasePath + "/" + operationName.toLowerCase().replace("_", "-"),
                response, latencyMs));
        return this;
    }

    public BankingMockBuilder withGlobalLatency(long value, TimeUnit unit) {
        this.globalLatencyMs = unit.toMillis(value);
        return this;
    }

    public BankingMockBuilder withFaultRate(double rate) {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Fault rate must be 0.0-1.0");
        }
        this.faultRate = rate;
        return this;
    }

    public BankingMockBuilder withBasePath(String basePath) {
        this.cbsBasePath = basePath;
        return this;
    }

    public BankingMockBuilder dependsOn(String... aliases) {
        this.dependsOn.addAll(Arrays.asList(aliases));
        return this;
    }

    /**
     * Sets the env var key that will be injected into dependent containers with
     * the Docker-reachable base URL of this mock.
     *
     * Example: .exposeUrlAs("CBS_BASE_URL") causes dependent containers to
     * receive CBS_BASE_URL=http://172.17.0.1:PORT/cbs/operations
     *
     * Note: the path suffix (e.g. /cbs/operations) is NOT appended here — the
     * service's application.yml should include it in the property, or the suite
     * can append it when building the env.
     */
    public BankingMockBuilder exposeUrlAs(String envKey) {
        this.urlEnvKey = envKey;
        return this;
    }

    public MockDownstreamComponent build() {
        List<OperationConfig> capturedOps = List.copyOf(operations);
        double capturedFaultRate = faultRate;

        return MockDownstreamComponent.builder(alias)
                .dependsOn(dependsOn.toArray(new String[0]))
                .exposeUrlAs(urlEnvKey)
                .withStub(server -> registerAllStubs(server, capturedOps, capturedFaultRate))
                .build();
    }

    // ── Pre-built operations ───────────────────────────────────────────────
    public BankingMockBuilder withSuccessfulFundsTransfer() {
        return withOperation("FUNDS_TRANSFER", "/cbs/operations/funds-transfer",
                okJson("""
                        {"txnReferenceNumber":"TXN-DEFAULT","status":"SUCCESS",
                         "responseCode":"00","description":"Transaction successful"}
                        """));
    }

    public BankingMockBuilder withAccountInquiry(String accountNumber, String accountName,
            String currency, double balance) {
        return withOperation("ACCOUNT_INQUIRY", "/cbs/operations/account-inquiry",
                okJson(String.format("""
                        {"accountNumber":"%s","accountName":"%s","currency":"%s",
                         "availableBalance":%.2f,"status":"ACTIVE","responseCode":"00"}
                        """, accountNumber, accountName, currency, balance)));
    }

    public BankingMockBuilder withCBSUnavailable(String operationName) {
        return withOperation(operationName,
                cbsBasePath + "/" + operationName.toLowerCase().replace("_", "-"),
                serviceUnavailable().withBody(
                        "{\"responseCode\":\"96\",\"description\":\"CBS temporarily unavailable\"}"));
    }

    // ── Internal ───────────────────────────────────────────────────────────
    private static void registerAllStubs(WireMockServer server,
            List<OperationConfig> ops,
            double faultRate) {
        for (OperationConfig op : ops) {
            ResponseDefinitionBuilder response = op.response();
            if (op.latencyMs() > 0) {
                response = response.withFixedDelay((int) op.latencyMs());
            }
            server.stubFor(post(urlPathEqualTo(op.urlPath())).willReturn(response));
            log.debug("Registered CBS stub: POST {}", op.urlPath());
        }
        if (faultRate > 0.0) {
            server.stubFor(any(anyUrl())
                    .withHeader("X-Inject-Fault", equalTo("true"))
                    .atPriority(1)
                    .willReturn(serviceUnavailable()
                            .withBody("{\"responseCode\":\"96\",\"description\":\"Injected fault\"}")));
        }
    }

    private record OperationConfig(String operationName, String urlPath,
            ResponseDefinitionBuilder response, long latencyMs) {

    }
}
