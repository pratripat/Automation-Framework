package com.banking.testframework.test;

/**
 * Represents a single executable functional test.
 *
 * <p>Implementations contain the actual test logic: setting up WireMock stubs,
 * firing HTTP calls against deployed services, and asserting on responses.
 * The framework handles everything outside this boundary — deployment, health
 * checks, teardown, and result collection.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * TestCase fundsTransferTest = ctx -> {
 *     // 1. Set up CBS mock stub
 *     ctx.getMockServer("core-banking-mock")
 *        .stubFor(post(urlEqualTo("/cbs/funds-transfer"))
 *            .willReturn(okJson(loadResource("stubs/ft-success.json"))));
 *
 *     // 2. Fire the request through the channel service
 *     String url = ctx.getServiceUrl("channel-service") + "/api/v1/transfer";
 *     Response response = ctx.getHttpClient().post(url, transferPayload());
 *
 *     // 3. Assert
 *     assertThat(response.code()).isEqualTo(200);
 *     assertThat(response.bodyAsJson().get("status").asText()).isEqualTo("SUCCESS");
 *
 *     return TestResult.builder()
 *         .status(TestStatus.PASSED)
 *         .metadata(Map.of("transferId", response.bodyAsJson().get("txnId").asText()))
 *         .build();
 * };
 * }</pre>
 */
@FunctionalInterface
public interface TestCase {

    /**
     * Executes the test logic.
     *
     * @param ctx  runtime context providing service URLs, mock servers, HTTP client, and logger
     * @return     a fully populated {@link TestResult}; the framework will fill in
     *             {@code testId}, {@code testName}, {@code suiteName}, {@code startTime},
     *             and {@code duration} automatically — the implementation only needs to
     *             set {@code status}, {@code failureMessage}, and optional {@code metadata}.
     * @throws Exception any unexpected exception — the framework catches this and
     *                   records an ERROR status rather than letting it crash the runner
     */
    TestResult execute(TestContext ctx) throws Exception;
}
