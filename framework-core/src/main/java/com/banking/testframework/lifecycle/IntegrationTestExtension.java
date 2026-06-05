package com.banking.testframework.lifecycle;

import com.banking.testframework.reporting.ReportConfig;
import com.banking.testframework.reporting.SuiteRunReport;
import com.banking.testframework.test.TestSuiteDefinition;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.*;

/**
 * JUnit 5 extension that wires the framework lifecycle into a standard Maven
 * Surefire / Failsafe test run.
 *
 * <p>Usage — annotate a JUnit 5 test class:</p>
 * <pre>{@code
 * @ExtendWith(IntegrationTestExtension.class)
 * @SuiteDefinitionClass(FundsTransferSuite.class)
 * @ReportToS3(bucket = "my-bucket", prefix = "banking/integration/")
 * class FundsTransferIT {
 *
 *     @Test
 *     void suitePassesAllTests(SuiteRunReport report) {
 *         assertThat(report.isAllPassed()).isTrue();
 *     }
 *
 *     @Test
 *     void fundsTransferSucceeds(SuiteRunReport report) {
 *         TestResult ft001 = report.getResults().stream()
 *             .filter(r -> r.getTestId().equals("FT-001"))
 *             .findFirst().orElseThrow();
 *         assertThat(ft001.getStatus()).isEqualTo(TestStatus.PASSED);
 *     }
 * }
 * }</pre>
 *
 * <p>The extension runs the full suite once per test class (in
 * {@code beforeAll}) and makes the {@link SuiteRunReport} available via
 * parameter injection.</p>
 */
public class IntegrationTestExtension
        implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestExtension.class);
    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(IntegrationTestExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();

        SuiteDefinitionClass defAnnotation = testClass.getAnnotation(SuiteDefinitionClass.class);
        if (defAnnotation == null) {
            throw new IllegalStateException(
                    "@SuiteDefinitionClass annotation is required on " + testClass.getName());
        }

        TestSuiteDefinition suiteDefinition =
                defAnnotation.value().getDeclaredConstructor().newInstance();

        ReportConfig reportConfig = buildReportConfig(testClass);

        log.info("IntegrationTestExtension: running suite '{}'", suiteDefinition.getSuiteName());

        SuiteRunReport report = TestOrchestrator.builder()
                .suiteDefinition(suiteDefinition)
                .reportConfig(reportConfig)
                .build()
                .run();

        context.getStore(NS).put("report", report);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        // Cleanup is handled by the orchestrator's CleanupRegistry.
        // Nothing extra needed here.
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType().equals(SuiteRunReport.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        return extensionContext.getStore(NS).get("report", SuiteRunReport.class);
    }

    private ReportConfig buildReportConfig(Class<?> testClass) {
        ReportToS3 s3 = testClass.getAnnotation(ReportToS3.class);
        if (s3 != null) {
            return ReportConfig.builder()
                    .consoleEnabled(true)
                    .s3Enabled(true)
                    .s3Bucket(s3.bucket())
                    .s3Prefix(s3.prefix())
                    .s3Region(s3.region().isBlank() ? null : s3.region())
                    .s3EndpointOverride(s3.endpointOverride().isBlank() ? null : s3.endpointOverride())
                    .captureContainerLogsOnFailure(true)
                    .build();
        }
        return ReportConfig.consoleOnly();
    }

    // -------------------------------------------------------------------------
    // Annotations
    // -------------------------------------------------------------------------

    /**
     * Points the extension to the {@link TestSuiteDefinition} implementation class.
     * The class must have a public no-arg constructor.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface SuiteDefinitionClass {
        Class<? extends TestSuiteDefinition> value();
    }

    /**
     * Configures S3 reporting for the test class.
     * If omitted, only console output is produced.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ReportToS3 {
        String bucket();
        String prefix() default "";
        String region() default "";
        /** Override for MinIO / LocalStack. Leave blank for real AWS. */
        String endpointOverride() default "";
    }
}
