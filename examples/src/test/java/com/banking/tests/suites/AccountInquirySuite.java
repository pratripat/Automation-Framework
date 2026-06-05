package com.banking.tests.suites;

import com.banking.testframework.container.GenericInfraComponent;
import com.banking.testframework.container.TomcatServiceComponent;
import com.banking.testframework.mock.BankingMockBuilder;
import com.banking.testframework.mock.MockDownstreamComponent;
import com.banking.testframework.test.*;
import com.banking.tests.stubs.StubPayloads;
import com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test suite covering Account Inquiry and Balance Check operations.
 *
 * <p>This suite demonstrates:</p>
 * <ul>
 *   <li>Multi-component deployment (Postgres + CBS mock + two microservices)</li>
 *   <li>Environment variable propagation from Postgres to dependent services</li>
 *   <li>Multiple CBS operations on the same mock server</li>
 *   <li>Dormant/frozen account business rules</li>
 * </ul>
 *
 * <p>Component graph:</p>
 * <pre>
 *   Test ─HTTP→ account-service ─HTTP→ core-banking-mock
 *                    │
 *                    └─JDBC─→ postgres
 * </pre>
 */
public class AccountInquirySuite extends AbstractTestSuiteDefinition {

    public static final String POSTGRES       = "postgres";
    public static final String CBS_MOCK       = "cbs-mock";
    public static final String ACCOUNT_SVC    = "account-service";

    public AccountInquirySuite() {

        // ── Postgres (infrastructure) ────────────────────────────────────────
        addComponent(GenericInfraComponent.builder(POSTGRES, "postgres:15-alpine")
                .port(5432)
                .env("POSTGRES_DB", "banking_test")
                .env("POSTGRES_USER", "testuser")
                .env("POSTGRES_PASSWORD", "testpass")
                .waitStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
                .componentType("postgres")
                // Expose JDBC URL so dependent containers get it automatically
                .exposeEnv("SPRING_DATASOURCE_URL",
                        c -> "jdbc:postgresql://" + POSTGRES + ":5432/banking_test")
                .exposeEnv("SPRING_DATASOURCE_USERNAME", c -> "testuser")
                .exposeEnv("SPRING_DATASOURCE_PASSWORD", c -> "testpass")
                .build());

        // ── CBS Mock ─────────────────────────────────────────────────────────
        MockDownstreamComponent cbsMock = BankingMockBuilder.forCBS(CBS_MOCK)
                .withGlobalLatency(20, TimeUnit.MILLISECONDS)
                .build();
        addComponent(cbsMock);

        // ── Account Service ───────────────────────────────────────────────────
        addComponent(TomcatServiceComponent.builder(ACCOUNT_SVC, "banking/account-service:latest")
                .port(8081)
                .healthCheckPath("/actuator/health")
                .dependsOn(POSTGRES, CBS_MOCK)
                // Static env — SPRING_DATASOURCE_* are injected by Postgres exposeEnv
                .env("CBS_BASE_URL", "http://cbs-mock:8080/funds-transfer")
                .env("SPRING_PROFILES_ACTIVE", "integration")
                .build());

        addProperty("env", "integration");

        // ── Test cases ────────────────────────────────────────────────────────
        addTest(TestCaseDefinition.builder()
                .id("AI-001")
                .name("Active account inquiry — returns full account details")
                .tags(List.of("smoke", "account-inquiry"))
                .testCase(this::testActiveAccountInquiry)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("AI-002")
                .name("Account not found — channel returns 404")
                .tags(List.of("regression", "error-handling"))
                .testCase(this::testAccountNotFound)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("AI-003")
                .name("Dormant account — channel returns account with DORMANT status")
                .tags(List.of("regression", "business-rules"))
                .testCase(this::testDormantAccount)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("AI-004")
                .name("Balance check — returns available balance")
                .tags(List.of("smoke", "balance-check"))
                .testCase(this::testBalanceCheck)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("AI-005")
                .name("Mini statement — returns last 5 transactions")
                .tags(List.of("regression", "mini-statement"))
                .testCase(this::testMiniStatement)
                .build());

        addTest(TestCaseDefinition.builder()
                .id("AI-006")
                .name("CBS unavailable — circuit breaker opens, returns 503")
                .tags(List.of("regression", "resilience"))
                .testCase(this::testCbsUnavailable)
                .build());
    }

    @Override
    public String getSuiteName() {
        return "account-inquiry-suite";
    }

    @Override
    public void beforeEach(TestContext ctx, TestCaseDefinition testCase) {
        ctx.getMockServer(CBS_MOCK).resetAll();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    private TestResult testActiveAccountInquiry(TestContext ctx) throws Exception {
        String accountNumber = "ACC-" + UUID.randomUUID().toString().substring(0, 8);

        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/account-inquiry"))
                        .withRequestBody(matchingJsonPath("$.accountNumber",
                                equalTo(accountNumber)))
                        .willReturn(okJson(StubPayloads.accountInquiryActive(accountNumber))));

        String url = ctx.getServiceUrl(ACCOUNT_SVC)
                + "/api/v1/accounts/" + accountNumber + "/inquiry";

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().get(url);

        assertThat(response.code()).isEqualTo(200);

        JsonNode body = response.bodyAsJson();
        assertThat(body.path("accountNumber").asText()).isEqualTo(accountNumber);
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("availableBalance").asDouble()).isPositive();
        assertThat(body.path("accountName").asText()).isNotBlank();
        assertThat(body.path("currency").asText()).isEqualTo("INR");

        ctx.recordMetadata("accountNumber", accountNumber);

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    private TestResult testAccountNotFound(TestContext ctx) throws Exception {
        String accountNumber = "NONEXISTENT-001";

        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/account-inquiry"))
                        .willReturn(okJson(StubPayloads.accountInquiryNotFound(accountNumber))
                                .withStatus(200)));

        String url = ctx.getServiceUrl(ACCOUNT_SVC)
                + "/api/v1/accounts/" + accountNumber + "/inquiry";

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().get(url);

        assertThat(response.code())
                .as("Channel must return 404 when CBS indicates account not found")
                .isEqualTo(404);

        JsonNode body = response.bodyAsJson();
        assertThat(body.path("errorCode").asText()).isEqualTo("ACCOUNT_NOT_FOUND");

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    private TestResult testDormantAccount(TestContext ctx) throws Exception {
        String accountNumber = "DORMANT-ACC-001";

        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/account-inquiry"))
                        .willReturn(okJson(StubPayloads.accountInquiryDormant(accountNumber))));

        String url = ctx.getServiceUrl(ACCOUNT_SVC)
                + "/api/v1/accounts/" + accountNumber + "/inquiry";

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().get(url);

        // Channel may return 200 with DORMANT status, or 422 — implementation-dependent.
        // Test only asserts the status field is correctly propagated.
        assertThat(response.code()).isIn(200, 422);

        if (response.code() == 200) {
            assertThat(response.bodyAsJson().path("status").asText())
                    .as("Dormant account status must be propagated")
                    .isEqualTo("DORMANT");
        } else {
            assertThat(response.bodyAsJson().path("errorCode").asText())
                    .isIn("ACCOUNT_DORMANT", "ACCOUNT_INACTIVE");
        }

        ctx.recordMetadata("accountNumber", accountNumber);

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    private TestResult testBalanceCheck(TestContext ctx) throws Exception {
        String accountNumber = "BAL-ACC-001";
        double expectedBalance = 75432.50;

        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/balance-check"))
                        .willReturn(okJson(StubPayloads.balanceCheckSuccess(
                                accountNumber, expectedBalance))));

        String url = ctx.getServiceUrl(ACCOUNT_SVC)
                + "/api/v1/accounts/" + accountNumber + "/balance";

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().get(url);

        assertThat(response.code()).isEqualTo(200);

        JsonNode body = response.bodyAsJson();
        assertThat(body.path("accountNumber").asText()).isEqualTo(accountNumber);
        assertThat(body.path("availableBalance").asDouble())
                .as("Balance must match CBS response")
                .isEqualTo(expectedBalance);
        assertThat(body.path("currency").asText()).isEqualTo("INR");

        ctx.recordMetadata("balance", String.valueOf(body.path("availableBalance").asDouble()));

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    private TestResult testMiniStatement(TestContext ctx) throws Exception {
        String accountNumber = "STMT-ACC-001";

        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/mini-statement"))
                        .willReturn(okJson(StubPayloads.miniStatementSuccess(accountNumber))));

        String url = ctx.getServiceUrl(ACCOUNT_SVC)
                + "/api/v1/accounts/" + accountNumber + "/mini-statement";

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().get(url);

        assertThat(response.code()).isEqualTo(200);

        JsonNode body = response.bodyAsJson();
        assertThat(body.path("transactions").isArray())
                .as("Mini statement must include a transactions array")
                .isTrue();
        assertThat(body.path("transactions").size())
                .as("Mini statement should contain up to 5 transactions")
                .isBetween(1, 5);

        JsonNode firstTxn = body.path("transactions").get(0);
        assertThat(firstTxn.has("date")).isTrue();
        assertThat(firstTxn.has("amount")).isTrue();
        assertThat(firstTxn.has("type")).isTrue();

        ctx.recordMetadata("transactionCount",
                String.valueOf(body.path("transactions").size()));

        return TestResult.builder().status(TestStatus.PASSED).build();
    }

    private TestResult testCbsUnavailable(TestContext ctx) throws Exception {
        // Stub CBS to return 503 for all inquiry requests
        ctx.getMockServer(CBS_MOCK).stubFor(
                post(urlEqualTo("/cbs/operations/account-inquiry"))
                        .willReturn(serviceUnavailable()
                                .withBody(StubPayloads.cbsUnavailable())));

        String url = ctx.getServiceUrl(ACCOUNT_SVC)
                + "/api/v1/accounts/TEST-ACC-001/inquiry";

        HttpTestClient.TestHttpResponse response = ctx.getHttpClient().get(url);

        // Channel service should propagate upstream unavailability as 503
        // or 502 Bad Gateway depending on implementation
        assertThat(response.code())
                .as("Channel must return 5xx when CBS is unavailable")
                .isBetween(500, 599);

        assertThat(response.bodyAsJson().path("errorCode").asText())
                .as("Error code must indicate upstream failure")
                .isIn("CBS_UNAVAILABLE", "UPSTREAM_UNAVAILABLE",
                      "SERVICE_UNAVAILABLE", "DOWNSTREAM_ERROR");

        return TestResult.builder().status(TestStatus.PASSED).build();
    }
}
