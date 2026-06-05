package com.banking.testframework.reporting;

import com.banking.testframework.test.TestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Uploads test results to an S3-compatible object store after a suite run.
 *
 * <p>Uploads the following artefacts under {@code s3://bucket/prefix/run-{runId}/}:</p>
 * <ul>
 *   <li>{@code report.json}  — machine-readable full results</li>
 *   <li>{@code report.html}  — human-readable HTML visualization</li>
 *   <li>{@code logs/{alias}.log} — container log snippets captured on failure</li>
 * </ul>
 *
 * <p>Supports any S3-compatible endpoint (AWS S3, MinIO, LocalStack) via the
 * {@link ReportConfig#getS3EndpointOverride()} setting.</p>
 */
public class S3Reporter implements TestReporter {

    private static final Logger log = LoggerFactory.getLogger(S3Reporter.class);

    private final ReportConfig config;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    public S3Reporter(ReportConfig config) {
        this.config = config;
        this.s3Client = buildS3Client(config);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String getName() {
        return "S3Reporter";
    }

    @Override
    public void report(SuiteRunReport report) {
        String prefix = buildPrefix(report);
        log.info("Uploading test report to s3://{}/{}", config.getS3Bucket(), prefix);

        try {
            uploadJson(report, prefix);
            uploadHtml(report, prefix);
            uploadContainerLogs(report, prefix);
            log.info("Report uploaded successfully → s3://{}/{}report.html",
                    config.getS3Bucket(), prefix);
        } catch (Exception e) {
            log.error("Failed to upload test report to S3", e);
        }
    }

    // -------------------------------------------------------------------------
    // Upload methods
    // -------------------------------------------------------------------------

    private void uploadJson(SuiteRunReport report, String prefix) throws Exception {
        String json = objectMapper.writeValueAsString(buildJsonPayload(report));
        put(prefix + "report.json", json, "application/json");
    }

    private void uploadHtml(SuiteRunReport report, String prefix) throws Exception {
        String html = buildHtmlReport(report);
        put(prefix + "report.html", html, "text/html");
    }

    private void uploadContainerLogs(SuiteRunReport report, String prefix) {
        // Collect all unique container log snapshots from failed tests
        for (TestResult result : report.getResults()) {
            result.getContainerLogsOnFailure().forEach((alias, logs) -> {
                try {
                    put(prefix + "logs/" + alias + ".log", logs, "text/plain");
                } catch (Exception e) {
                    log.warn("Failed to upload logs for container '{}': {}", alias, e.getMessage());
                }
            });
        }
    }

    private void put(String key, String content, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(config.getS3Bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------
    // Report builders
    // -------------------------------------------------------------------------

    private Map<String, Object> buildJsonPayload(SuiteRunReport report) {
        return Map.of(
                "runId", report.getRunId(),
                "suiteName", report.getSuiteName(),
                "startTime", report.getStartTime().toString(),
                "durationMs", report.getTotalDuration().toMillis(),
                "totalTests", report.totalCount(),
                "passed", report.passedCount(),
                "failed", report.failedCount(),
                "errors", report.errorCount(),
                "skipped", report.skippedCount(),
                "results", report.getResults().stream().map(r -> Map.of(
                        "id", r.getTestId(),
                        "name", r.getTestName(),
                        "status", r.getStatus().name(),
                        "durationMs", r.getDuration().toMillis(),
                        "failureMessage", r.getFailureMessage() != null ? r.getFailureMessage() : "",
                        "metadata", r.getMetadata()
                )).toList()
        );
    }

    private String buildHtmlReport(SuiteRunReport report) {
        String started = report.getStartTime().atZone(java.time.ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"));

        StringBuilder rows = new StringBuilder();
        for (TestResult r : report.getResults()) {
            String statusClass = switch (r.getStatus()) {
                case PASSED  -> "passed";
                case FAILED  -> "failed";
                case ERROR   -> "error";
                case SKIPPED -> "skipped";
            };
            String statusIcon = switch (r.getStatus()) {
                case PASSED  -> "✓";
                case FAILED  -> "✗";
                case ERROR   -> "!";
                case SKIPPED -> "⊘";
            };
            String failureCell = r.getFailureMessage() != null
                    ? "<span class='failure-msg'>" + htmlEscape(r.getFailureMessage()) + "</span>"
                    : "";
            rows.append(String.format("""
                <tr class="%s">
                  <td><span class="badge %s">%s</span></td>
                  <td class="test-id">%s</td>
                  <td>%s</td>
                  <td class="duration">%dms</td>
                  <td>%s</td>
                </tr>
                """,
                    statusClass, statusClass, statusIcon,
                    htmlEscape(r.getTestId()),
                    htmlEscape(r.getTestName()),
                    r.getDuration().toMillis(),
                    failureCell));
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Test Report — %s</title>
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                           background: #f5f5f5; color: #333; padding: 24px; }
                    .card { background: white; border-radius: 8px; padding: 24px;
                            box-shadow: 0 1px 3px rgba(0,0,0,.1); margin-bottom: 20px; }
                    h1 { font-size: 22px; font-weight: 600; color: #111; }
                    .meta { color: #666; font-size: 13px; margin-top: 6px; }
                    .stats { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 16px; }
                    .stat { background: #f8f9fa; border-radius: 6px; padding: 12px 18px;
                            text-align: center; min-width: 80px; }
                    .stat .num { font-size: 26px; font-weight: 700; }
                    .stat .label { font-size: 11px; color: #888; text-transform: uppercase; margin-top: 2px; }
                    .passed .num { color: #16a34a; } .failed .num, .error .num { color: #dc2626; }
                    .skipped .num { color: #d97706; }
                    table { width: 100%%; border-collapse: collapse; font-size: 14px; }
                    th { text-align: left; padding: 10px 12px; border-bottom: 2px solid #e5e7eb;
                         font-weight: 600; font-size: 12px; color: #666; text-transform: uppercase; }
                    td { padding: 10px 12px; border-bottom: 1px solid #f3f4f6; vertical-align: top; }
                    tr:hover td { background: #fafafa; }
                    .badge { display: inline-flex; align-items: center; justify-content: center;
                             width: 22px; height: 22px; border-radius: 50%; font-weight: 700;
                             font-size: 13px; }
                    tr.passed .badge { background: #dcfce7; color: #166534; }
                    tr.failed .badge, tr.error .badge { background: #fee2e2; color: #991b1b; }
                    tr.skipped .badge { background: #fef3c7; color: #92400e; }
                    .test-id { font-family: monospace; color: #666; font-size: 12px; }
                    .duration { color: #888; font-size: 12px; }
                    .failure-msg { color: #dc2626; font-size: 12px; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Integration Test Report — %s</h1>
                    <p class="meta">Run ID: %s | Started: %s | Duration: %.2fs</p>
                    <div class="stats">
                      <div class="stat passed"><div class="num">%d</div><div class="label">Passed</div></div>
                      <div class="stat failed"><div class="num">%d</div><div class="label">Failed</div></div>
                      <div class="stat error"><div class="num">%d</div><div class="label">Errors</div></div>
                      <div class="stat skipped"><div class="num">%d</div><div class="label">Skipped</div></div>
                    </div>
                  </div>
                  <div class="card">
                    <table>
                      <thead>
                        <tr>
                          <th style="width:40px"></th>
                          <th style="width:100px">ID</th>
                          <th>Test name</th>
                          <th style="width:80px">Duration</th>
                          <th>Detail</th>
                        </tr>
                      </thead>
                      <tbody>%s</tbody>
                    </table>
                  </div>
                </body>
                </html>
                """.formatted(
                report.getSuiteName(),
                report.getSuiteName(),
                report.getRunId(), started,
                report.getTotalDuration().toMillis() / 1000.0,
                report.passedCount(), report.failedCount(),
                report.errorCount(), report.skippedCount(),
                rows.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildPrefix(SuiteRunReport report) {
        String base = config.getS3Prefix() != null ? config.getS3Prefix() : "";
        if (!base.isEmpty() && !base.endsWith("/")) base += "/";
        return base + "run-" + report.getRunId() + "/";
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static S3Client buildS3Client(ReportConfig config) {
        var builder = S3Client.builder()
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (config.getS3Region() != null) {
            builder.region(Region.of(config.getS3Region()));
        }
        if (config.getS3EndpointOverride() != null) {
            builder.endpointOverride(URI.create(config.getS3EndpointOverride()));
        }
        return builder.build();
    }
}
