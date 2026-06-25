# Automation Framework — Functional Test Framework

A generic, production-grade functional testing framework built on
[Testcontainers](https://testcontainers.com) for Spring Boot microservice platforms
deployed on Tomcat. Manages the full Docker lifecycle so test authors focus purely
on writing assertions, not infrastructure.

> **Latest release:** v1.0.12 · [GitHub Packages](https://github.com/pratripat/Automation-Framework/packages)

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│          Test implementation (your test project)          │
│    Provides: TestSuiteDefinition, TestCase lambdas,       │
│             CBS stubs, assertions                         │
└────────────────────────┬─────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────┐
│               TestOrchestrator (lifecycle engine)         │
│  INIT → NETWORK → DEPLOY → HEALTH_CHECK → TEST → TEARDOWN│
└──────┬─────────────────────────────────────┬─────────────┘
       │                                     │
┌──────▼──────────┐                ┌─────────▼───────────┐
│ ContainerRegistry│                │  TestResultCollector │
│ NetworkManager   │                │  ConsoleReporter     │
│ CleanupRegistry  │                │  S3Reporter          │
└──────┬──────────┘                └─────────────────────┘
       │
┌──────▼─────────────────────────────────────────────────┐
│  DeployableComponents                                   │
│  TomcatServiceComponent │ MockDownstreamComponent       │
│  GenericInfraComponent  │ (WireMock / BankingMockBuilder)│
└────────────────────────────────────────────────────────┘
```

---

## Quick Start

### 1. Add GitHub Packages as a repository

Add this to your project's `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub Packages</name>
        <url>https://maven.pkg.github.com/pratripat/Automation-Framework</url>
    </repository>
</repositories>
```

Add this to your `~/.m2/settings.xml` (create it if it doesn't exist):

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
        </server>
    </servers>
</settings>
```

> The token needs `read:packages` scope. Generate one at GitHub → Settings → Developer Settings → Personal Access Tokens.

### 2. Add the dependency

```xml
<dependency>
    <groupId>com.banking</groupId>
    <artifactId>framework-core</artifactId>
    <version>1.0.12</version>
    <scope>test</scope>
</dependency>
```

### 3. Implement a test suite

```java
public class MyServiceSuite extends AbstractTestSuiteDefinition {

    public MyServiceSuite() {
        // Declare components
        addComponent(BankingMockBuilder.forCBS("cbs-mock")
            .withGlobalLatency(30, MILLISECONDS)
            .exposeUrlAs("CBS_BASE_URL")
            .build());

        addComponent(TomcatServiceComponent.builder("my-service", "banking/my-service:latest")
            .port(8080)
            .dependsOn("cbs-mock")
            .build());

        // Register tests
        addTest("TC-001", "Successful operation", ctx -> {
            ctx.getMockServer("cbs-mock").stubFor(
                post(urlEqualTo("/cbs/operations/do-something"))
                    .willReturn(okJson("{\"status\":\"SUCCESS\",\"responseCode\":\"00\"}")));

            var response = ctx.getHttpClient().post(
                ctx.getServiceUrl("my-service") + "/api/v1/operation",
                "{\"field\":\"value\"}");

            assertThat(response.code()).isEqualTo(200);
            return TestResult.builder().status(TestStatus.PASSED).build();
        });
    }

    @Override public String getSuiteName() { return "my-service-suite"; }
}
```

### 4. Create the JUnit 5 runner

```java
@ExtendWith(IntegrationTestExtension.class)
@IntegrationTestExtension.SuiteDefinitionClass(MyServiceSuite.class)
class MyServiceIT {

    @Test
    void allTestsPass(SuiteRunReport report) {
        assertThat(report.isAllPassed()).isTrue();
    }

    @Test
    void tc001Passes(SuiteRunReport report) {
        TestResult r = report.getResults().stream()
            .filter(t -> t.getTestId().equals("TC-001")).findFirst().orElseThrow();
        assertThat(r.getStatus()).isEqualTo(TestStatus.PASSED);
    }
}
```

### 5. Run

```bash
mvn verify -pl my-test-module
```

---

## Component Reference

### TomcatServiceComponent
Deploys a Spring Boot / Tomcat microservice. Waits for `/actuator/health` before
reporting ready.

```java
TomcatServiceComponent.builder("channel-service", "banking/channel:latest")
    .port(8080)
    .healthCheckPath("/actuator/health")
    .startupTimeout(Duration.ofMinutes(3))
    .env("SPRING_PROFILES_ACTIVE", "integration")
    .dependsOn("postgres", "cbs-mock")
    .build();
```

### MockDownstreamComponent (via BankingMockBuilder)
WireMock-backed CBS mock with domain-specific helpers. Automatically resolves
the Docker-host-reachable address so service containers can reach the in-process
WireMock server.

```java
BankingMockBuilder.forCBS("flexcube-mock")
    .withGlobalLatency(50, MILLISECONDS)
    .withFaultRate(0.02)   // 2% random 503s
    .exposeUrlAs("CBS_BASE_URL")
    .build();
```

### GenericInfraComponent
Any infrastructure container (Postgres, Kafka, Redis, etc.).

```java
GenericInfraComponent.builder("postgres", "postgres:15-alpine")
    .port(5432)
    .env("POSTGRES_DB", "banking_test")
    .env("POSTGRES_USER", "test")
    .env("POSTGRES_PASSWORD", "test")
    .waitStrategy(Wait.forLogMessage(".*ready to accept connections.*", 2))
    .exposeEnv("SPRING_DATASOURCE_URL",
               c -> "jdbc:postgresql://postgres:5432/banking_test")
    .build();
```

---

## Reporting

### Console (default)
Always-on. Produces a color-coded summary table and full failure details with stack
traces and container log snapshots.

### S3 / Compatible Object Store

```java
ReportConfig.builder()
    .consoleEnabled(true)
    .s3Enabled(true)
    .s3Bucket("my-test-results")
    .s3Prefix("banking-platform/integration/")
    .s3Region("ap-south-1")
    .s3EndpointOverride("http://localhost:9000") // For MinIO or LocalStack
    .build();
```

Uploads to `s3://bucket/prefix/run-{uuid}/`:
- `report.json` — machine-readable results
- `report.html` — human-readable HTML with pass/fail table
- `logs/{alias}.log` — container log snapshots from failing tests

---

## TestContext API

```java
TestCase myTest = ctx -> {
    String url = ctx.getServiceUrl("channel-service");

    WireMockServer mock = ctx.getMockServer("cbs-mock");
    mock.stubFor(post(urlEqualTo("/cbs/ops/transfer")).willReturn(okJson("...")));

    var response = ctx.getHttpClient().post(url + "/api/v1/transfer", body);

    ctx.recordMetadata("txnRef", response.bodyAsJson().path("txnReferenceNumber").asText());

    assertThat(response.code()).isEqualTo(200);

    return TestResult.builder().status(TestStatus.PASSED).build();
};
```

---

## Lifecycle Hooks

```java
public class MySuite extends AbstractTestSuiteDefinition {

    @Override
    public void beforeAll(TestContext ctx) throws Exception {
        // Runs once after all components are healthy, before any tests
    }

    @Override
    public void beforeEach(TestContext ctx, TestCaseDefinition testCase) throws Exception {
        // Reset WireMock stubs between tests
        ctx.getMockServer("cbs-mock").resetAll();
    }

    @Override
    public void afterEach(TestContext ctx, TestResult result) throws Exception {}

    @Override
    public void afterAll(TestContext ctx, List<TestResult> results) throws Exception {}
}
```

---

## Test Dependencies

```java
addTest(TestCaseDefinition.builder()
    .id("FT-003")
    .name("Idempotency check")
    .dependsOn(List.of("FT-001"))  // Auto-skipped if FT-001 didn't pass
    .testCase(this::testIdempotency)
    .build());
```

---

## Running Multiple Suites (Programmatic)

```java
int exitCode = SuiteRunner.builder()
    .addSuite(new FundsTransferSuite())
    .addSuite(new AccountInquirySuite())
    .reportConfig(ReportConfig.withS3("my-bucket", "banking/integration/"))
    .failFast(false)
    .build()
    .run();

System.exit(exitCode);
```

---

## Playwright / JS Integration

```java
addTest("E2E-001", "Playwright login flow", ctx -> {
    List<TestResult> results = NodeTestRunner.builder()
        .command("npx", "playwright", "test", "--reporter=junit")
        .junitOutputPath("playwright-report/junit.xml")
        .envPrefix("TEST_")
        .build()
        .run(ctx, Path.of("web-console-tests"));

    boolean allPassed = results.stream().allMatch(TestResult::isPassed);
    return TestResult.builder()
        .status(allPassed ? TestStatus.PASSED : TestStatus.FAILED)
        .build();
});
```

---

## Project Structure

```
functional-test-framework/
├── framework-core/                    ← library (add as test dependency)
│   └── src/main/java/com/banking/testframework/
│       ├── lifecycle/
│       │   ├── TestOrchestrator.java  ← main entry point
│       │   ├── IntegrationTestExtension.java
│       │   └── SuiteRunner.java
│       ├── container/
│       │   ├── DeployableComponent.java
│       │   ├── TomcatServiceComponent.java
│       │   ├── GenericInfraComponent.java
│       │   ├── ContainerRegistry.java
│       │   ├── NetworkManager.java
│       │   └── CleanupRegistry.java
│       ├── mock/
│       │   ├── MockDownstreamComponent.java
│       │   └── BankingMockBuilder.java
│       ├── test/
│       │   ├── TestCase.java
│       │   ├── TestContext.java
│       │   ├── TestResult.java
│       │   ├── TestCaseDefinition.java
│       │   ├── TestSuiteDefinition.java
│       │   ├── AbstractTestSuiteDefinition.java
│       │   └── HttpTestClient.java
│       ├── reporting/
│       │   ├── ConsoleReporter.java
│       │   ├── S3Reporter.java
│       │   ├── SuiteRunReport.java
│       │   └── ReportConfig.java
│       └── plugin/
│           ├── ExternalTestRunner.java
│           └── NodeTestRunner.java
│
├── framework-bom/                     ← Bill of Materials
│
└── examples/                          ← reference implementation
    └── src/test/java/com/banking/tests/
        ├── suites/
        │   ├── FundsTransferSuite.java
        │   └── AccountInquirySuite.java
        ├── stubs/
        │   └── StubPayloads.java
        ├── FundsTransferIT.java
        └── AccountInquiryIT.java
```

---

## Prerequisites

- Java 17+
- Docker (Desktop or Engine) running locally or in CI
- Maven 3.8+

---

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `NO_COLOR` | Disable ANSI colors in console output | (unset = colors on) |
| `CI` | Forces ANSI colors even without a TTY | (unset) |
| `AWS_REGION` | AWS region for S3 reporter | SDK default |
| `AWS_ACCESS_KEY_ID` | AWS credentials | SDK default chain |
| `AWS_SECRET_ACCESS_KEY` | AWS credentials | SDK default chain |

---

## Used By

- [CBS-Functional-Tests](https://github.com/pratripat/CBS-Functional-Tests) — Functional test suites for a four-service banking platform demonstrating real-world usage of this framework.