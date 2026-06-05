package com.banking.testframework.reporting;

import lombok.Builder;
import lombok.Getter;

/**
 * Configuration for the reporting pipeline.
 *
 * <pre>{@code
 * ReportConfig.builder()
 *     .consoleEnabled(true)
 *     .s3Enabled(true)
 *     .s3Bucket("my-test-results")
 *     .s3Prefix("banking-platform/integration/")
 *     .s3Region("ap-south-1")
 *     // Optional: override endpoint for MinIO or LocalStack
 *     .s3EndpointOverride("http://localhost:9000")
 *     .captureContainerLogsOnFailure(true)
 *     .containerLogLines(200)
 *     .build();
 * }</pre>
 */
@Getter
@Builder
public class ReportConfig {

    /** Whether to write the report to stdout. Default: true. */
    @Builder.Default
    private final boolean consoleEnabled = true;

    /** Whether to upload the report to S3. Default: false. */
    @Builder.Default
    private final boolean s3Enabled = false;

    /** S3 bucket name (required when s3Enabled = true). */
    private final String s3Bucket;

    /**
     * Prefix applied to all S3 keys.
     * Example: "banking-platform/integration/" → uploads to
     * {@code s3://bucket/banking-platform/integration/run-{uuid}/report.html}
     */
    @Builder.Default
    private final String s3Prefix = "";

    /** AWS region for the S3 client. If null, falls back to AWS SDK default. */
    private final String s3Region;

    /**
     * Override the S3 endpoint URL. Use this for MinIO or LocalStack.
     * Example: "http://localhost:9000"
     */
    private final String s3EndpointOverride;

    /**
     * Whether to capture container logs from all running components when a test
     * fails. Adds significant context for debugging CI failures. Default: true.
     */
    @Builder.Default
    private final boolean captureContainerLogsOnFailure = true;

    /**
     * Number of trailing log lines to capture per container on failure.
     * Default: 150.
     */
    @Builder.Default
    private final int containerLogLines = 150;

    /** Convenience: returns a default config with only console output. */
    public static ReportConfig consoleOnly() {
        return ReportConfig.builder().build();
    }

    /** Convenience: returns a config with console + S3 output. */
    public static ReportConfig withS3(String bucket, String prefix) {
        return ReportConfig.builder()
                .consoleEnabled(true)
                .s3Enabled(true)
                .s3Bucket(bucket)
                .s3Prefix(prefix)
                .build();
    }
}
