package com.banking.tests.suites;

import com.banking.testframework.container.TomcatServiceComponent;
import com.banking.testframework.mock.BankingMockBuilder;
import com.banking.testframework.mock.MockDownstreamComponent;
import com.banking.testframework.test.*;
import com.banking.tests.stubs.StubPayloads;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test suite covering the Funds Transfer flow through the banking
 * platform's channel service.
 *
 * <p>
 * Architecture under test:</p>
 * <pre>
 *   Test ─HTTP→ channel-service ─HTTP→ core-banking-mock (WireMock)
 * </pre>
 *
 * <p>
 * This suite demonstrates:</p>
 * <ul>
 * <li>Happy path: successful transfer</li>
 * <li>Business error: insufficient funds (CBS returns 51)</li>
 * <li>Idempotency: duplicate request detection</li>
 * <li>Resilience: CBS timeout handling</li>
 * <li>Validation: missing mandatory field rejection</li>
 * <li>Dependency chaining: FT-003 depends on FT-001 passing</li>
 * </ul>
 */
public class FundsTransferSuite extends AbstractTestSuiteDefinition {

    // Component aliases — used to look up URLs and mock servers in TestContext
    public static final String CHANNEL_SERVICE = "channel-service";
    public static final String CBS_MOCK = "core-banking-mock";

    public FundsTransferSuite() {
        // ── Components ──────────────────────────────────────────────────────

        // CBS mock: pre-stubbed with domain operations, 30ms simulated latency
        MockDownstreamComponent cbsMock = BankingMockBuilder.forCBS(CBS_MOCK)
                .withGlobalLatency(30, TimeUnit.MILLISECONDS)
                .build();
        addComponent(cbsMock);

        // Channel service: Spring Boot on Tomcat, depends on the CBS mock being up first
        addComponent(TomcatServiceComponent.builder(CHANNEL_SERVICE, "banking/channel-service:latest")
                .port(8080)
                .healthCheckPath("/actuator/health")
                .dependsOn(CBS_MOCK)
                .env("CBS_BASE_URL", "http://localhost:${CBS_PORT}/cbs/operations")
                .env("SPRING_PROFILES_ACTIVE", "integration")
                .build());

        // ── Suite properties ────────────────────────────────────────────────
        addProperty("env", "integration");
        addProperty("debitAccount", "1234567890");
        addProperty("creditAccount", "0987654321");
        addProperty("currency", "INR");

        // ── Test cases ───────────────────────────────────────────────────────
        addTest(TestCaseDefinition.builder()
                .id("FT-001")
                .name("Successful funds transfer — happy path")
                .tags(List.of("smoke", "happy-path"))
                .testCase(this::testSuccessfulFundsTransfer)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("FT-002")
                .name("Insufficient funds — CBS returns 51, channel returns 422")
                .tags(List.of("regression", "error-handling"))
                .testCase(this::testInsufficientFunds)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("FT-003")
                .name("Idempotency — duplicate request uses same txn reference")
                .tags(List.of("regression", "idempotency"))
                .dependsOn(List.of("FT-001")) // Requires FT-001 to have passed
                .testCase(this::testIdempotentTransfer)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("FT-004")
                .name("CBS timeout — channel returns 504 Gateway Timeout")
                .tags(List.of("regression", "resilience"))
                .testCase(this::testCbsTimeout)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("FT-005")
                .name("Missing mandatory field — channel returns 400 Bad Request")
                .tags(List.of("regression", "validation"))
                .testCase(this::testMissingMandatoryField)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("FT-006")
                .name("Large amount transfer — over-limit rejected by channel")
                .tags(List.of("regression", "validation"))
                .testCase(this::testOverLimitAmount)
                .build());
    }

    @Override
    public String getSuiteName() {
        return "funds-transfer-suite";
    }

    // ── Lifecycle hooks ──────────────────────────────────────────────────────
    @Override
    public void beforeEach(TestContext ctx, TestCaseDefinition testCase) {
        // Reset WireMock between tests so stubs from one test don't bleed into the next
        ctx.getMockServer(CBS_MOCK).resetAll();
        ctx.getLogger().debug("WireMock reset for test [{}]", testCase.getId());
    }

    // ── Test implementations ─────────────────────────────────────────────────
    /**
     * FT-001: Happy path. CBS returns a success payload, channel service should
     * return HTTP 200 with a txnReferenceNumber in the body.
     */
    private TestResult testSuccessfulFundsTransfer(TestContext ctx) throws Exception {
        String txnRef = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Arrange: stub the CBS endpoint
        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/funds-transfer"))
                        .withRequestBody(matchingJsonPath("$.debitAccountNumber"))
                        .withRequestBody(matchingJsonPath("$.creditAccountNumber"))
                        .withRequestBody(matchingJsonPath("$.amount"))
                        .willReturn(okJson(StubPayloads.fundsTransferSuccess(txnRef))));

        // Act: call channel service
        String url = ctx.getServiceUrl(CHANNEL_SERVICE) + "/api/v1/funds-transfer";
        String requestBody = buildTransferRequest(
                ctx.getProperty("debitAccount"),
                ctx.getProperty("creditAccount"),
                10000.00, ctx.getProperty("currency"), UUID.randomUUID().toString());

        HttpTestClient.TestHttpResponse response
                = ctx.getHttpClient().post(url, requestBody);

        // Assert: HTTP layer
        assertThat(response.code())
                .as("Channel service should return 200 for successful transfer")
                .isEqualTo(200);

        JsonNode body = response.bodyAsJson();
        assertThat(body.path("status").asText())
                .as("Response status should be SUCCESS")
                .isEqualTo("SUCCESS");
        assertThat(body.path("txnReferenceNumber").asText())
                .as("txnReferenceNumber must be present and non-empty")
                .isNotBlank();

        // Assert: CBS was called exactly once
        ctx.getMockServer(CBS_MOCK).verify(1,
                postRequestedFor(urlEqualTo("/cbs/operations/funds-transfer")));

        // Record metadata for report
        ctx.recordMetadata("txnReferenceNumber", body.path("txnReferenceNumber").asText());
        ctx.recordMetadata("cbsResponseCode", body.path("cbsResponseCode").asText("00"));

        return TestResult.builder()
                .status(TestStatus.PASSED)
                .build();
    }

    /**
     * FT-002: CBS returns responseCode=51 (insufficient funds). Channel service
     * must translate this to HTTP 422 with an error body.
     */
    private TestResult testInsufficientFunds(TestContext ctx) throws Exception {
        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/funds-transfer"))
                        .willReturn(okJson(StubPayloads.fundsTransferInsufficientFunds())
                                .withStatus(200)));

        String url = ctx.getServiceUrl(CHANNEL_SERVICE) + "/api/v1/funds-transfer";
        String requestBody = buildTransferRequest("1234567890", "0987654321",
                999999.00, "INR", UUID.randomUUID().toString());

        HttpTestClient.TestHttpResponse response
                = ctx.getHttpClient().post(url, requestBody);

        assertThat(response.code())
                .as("Channel must return 422 for insufficient funds")
                .isEqualTo(422);

        JsonNode body = response.bodyAsJson();
        assertThat(body.path("errorCode").asText())
                .as("errorCode should map CBS 51 to a domain error code")
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(body.path("message").asText())
                .as("Error message must be present")
                .isNotBlank();

        ctx.recordMetadata("cbsResponseCode", "51");

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    /**
     * FT-003: Idempotency. Sending the same idempotency key twice should result
     * in the same txnReferenceNumber and no duplicate CBS call. Depends on
     * FT-001 to confirm the channel service is working.
     */
    private TestResult testIdempotentTransfer(TestContext ctx) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String txnRef = "TXN-IDEM-001";

        // First call — CBS returns success
        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/funds-transfer"))
                        .willReturn(okJson(StubPayloads.fundsTransferSuccess(txnRef))));

        String url = ctx.getServiceUrl(CHANNEL_SERVICE) + "/api/v1/funds-transfer";
        String body = buildTransferRequest("1234567890", "0987654321",
                5000.00, "INR", idempotencyKey);

        // First request
        HttpTestClient.TestHttpResponse first = ctx.getHttpClient().post(url, body);
        assertThat(first.code()).isEqualTo(200);
        String firstTxnRef = first.bodyAsJson().path("txnReferenceNumber").asText();

        // Second request with same idempotency key — CBS should NOT be called again
        // (channel service should return cached response)
        HttpTestClient.TestHttpResponse second = ctx.getHttpClient().post(url, body);
        assertThat(second.code())
                .as("Idempotent repeat should return 200 (or 208 Already Reported)")
                .isIn(200, 208);

        String secondTxnRef = second.bodyAsJson().path("txnReferenceNumber").asText();
        assertThat(secondTxnRef)
                .as("Idempotent response must return the same txnReferenceNumber")
                .isEqualTo(firstTxnRef);

        // CBS should have been called exactly once
        ctx.getMockServer(CBS_MOCK).verify(1,
                postRequestedFor(urlEqualTo("/cbs/operations/funds-transfer")));

        ctx.recordMetadata("idempotencyKey", idempotencyKey);
        ctx.recordMetadata("txnReferenceNumber", firstTxnRef);

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    /**
     * FT-004: CBS takes too long to respond. Channel service must return HTTP
     * 504 within its configured timeout.
     */
    private TestResult testCbsTimeout(TestContext ctx) throws Exception {
        // Stub CBS to delay for 30 seconds (longer than channel's CBS timeout)
        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/funds-transfer"))
                        .willReturn(okJson(StubPayloads.fundsTransferSuccess("TXN-TIMEOUT"))
                                .withFixedDelay(30_000)));

        String url = ctx.getServiceUrl(CHANNEL_SERVICE) + "/api/v1/funds-transfer";
        String body = buildTransferRequest("1234567890", "0987654321",
                5000.00, "INR", UUID.randomUUID().toString());

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().post(url, body);

        assertThat(response.code())
                .as("Channel must return 504 when CBS times out")
                .isEqualTo(504);

        assertThat(response.bodyAsJson().path("errorCode").asText())
                .as("Error code should indicate upstream timeout")
                .isIn("CBS_TIMEOUT", "UPSTREAM_TIMEOUT", "GATEWAY_TIMEOUT");

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    /**
     * FT-005: Missing mandatory field in request. Channel service must validate
     * and return 400 without hitting CBS at all.
     */
    private TestResult testMissingMandatoryField(TestContext ctx) throws Exception {
        String url = ctx.getServiceUrl(CHANNEL_SERVICE) + "/api/v1/funds-transfer";

        // Request missing the 'amount' field
        String invalidBody = """
                {
                  "debitAccountNumber": "1234567890",
                  "creditAccountNumber": "0987654321",
                  "currency": "INR",
                  "idempotencyKey": "%s"
                }
                """.formatted(UUID.randomUUID());

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().post(url, invalidBody);

        assertThat(response.code())
                .as("Missing 'amount' must result in 400 Bad Request")
                .isEqualTo(400);

        JsonNode body = response.bodyAsJson();
        assertThat(body.path("errors").isArray())
                .as("Validation response should include an 'errors' array")
                .isTrue();
        assertThat(body.path("errors").toString())
                .as("Error detail should mention 'amount'")
                .containsIgnoringCase("amount");

        // CBS must NOT have been called
        ctx.getMockServer(CBS_MOCK).verify(0,
                postRequestedFor(urlEqualTo("/cbs/operations/funds-transfer")));

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    /**
     * FT-006: Amount exceeds per-transaction limit. Channel service should
     * reject before reaching CBS.
     */
    private TestResult testOverLimitAmount(TestContext ctx) throws Exception {
        String url = ctx.getServiceUrl(CHANNEL_SERVICE) + "/api/v1/funds-transfer";

        // Assume the channel service enforces a ₹10,00,000 per-transaction limit
        String body = buildTransferRequest("1234567890", "0987654321",
                10_00_001.00, "INR", UUID.randomUUID().toString());

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().post(url, body);

        assertThat(response.code())
                .as("Amount over limit must result in 422 or 400")
                .isIn(400, 422);
        assertThat(response.bodyAsJson().path("errorCode").asText())
                .isIn("AMOUNT_EXCEEDS_LIMIT", "TRANSACTION_LIMIT_EXCEEDED");

        ctx.getMockServer(CBS_MOCK).verify(0,
                postRequestedFor(urlEqualTo("/cbs/operations/funds-transfer")));

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static String buildTransferRequest(String debitAcc, String creditAcc,
            double amount, String currency,
            String idempotencyKey) {
        return """
                {
                  "debitAccountNumber": "%s",
                  "creditAccountNumber": "%s",
                  "amount": %.2f,
                  "currency": "%s",
                  "remarks": "Integration test transfer",
                  "idempotencyKey": "%s"
                }
                """.formatted(debitAcc, creditAcc, amount, currency, idempotencyKey);
    }
}
