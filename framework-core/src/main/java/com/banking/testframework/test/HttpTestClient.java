package com.banking.testframework.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe HTTP client for use inside {@link TestCase} implementations.
 *
 * <p>Wraps OkHttp with convenience methods for JSON-heavy functional tests.
 * All requests are logged at DEBUG level with timing.</p>
 */
public class HttpTestClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTestClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public HttpTestClient(Duration connectTimeout, Duration readTimeout) {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
        this.mapper = new ObjectMapper();
    }

    public HttpTestClient() {
        this(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    // -------------------------------------------------------------------------
    // Core request methods
    // -------------------------------------------------------------------------

    public TestHttpResponse get(String url) throws IOException {
        return get(url, Map.of());
    }

    public TestHttpResponse get(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        headers.forEach(builder::addHeader);
        return execute(builder.build());
    }

    public TestHttpResponse post(String url, String jsonBody) throws IOException {
        return post(url, jsonBody, Map.of());
    }

    public TestHttpResponse post(String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request.Builder builder = new Request.Builder().url(url).post(body);
        headers.forEach(builder::addHeader);
        return execute(builder.build());
    }

    public TestHttpResponse put(String url, String jsonBody) throws IOException {
        return put(url, jsonBody, Map.of());
    }

    public TestHttpResponse put(String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request.Builder builder = new Request.Builder().url(url).put(body);
        headers.forEach(builder::addHeader);
        return execute(builder.build());
    }

    public TestHttpResponse delete(String url) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).delete();
        return execute(builder.build());
    }

    public TestHttpResponse patch(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder().url(url).patch(body).build();
        return execute(request);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private TestHttpResponse execute(Request request) throws IOException {
        long start = System.currentTimeMillis();
        log.debug("HTTP {} {}", request.method(), request.url());
        try (Response response = client.newCall(request).execute()) {
            long durationMs = System.currentTimeMillis() - start;
            String responseBody = Objects.requireNonNull(response.body()).string();
            log.debug("HTTP {} {} → {} ({}ms)", request.method(), request.url(),
                    response.code(), durationMs);
            return new TestHttpResponse(response.code(), responseBody, response.headers(), mapper);
        }
    }

    // -------------------------------------------------------------------------
    // Response wrapper
    // -------------------------------------------------------------------------

    public static class TestHttpResponse {

        private final int statusCode;
        private final String rawBody;
        private final Headers headers;
        private final ObjectMapper mapper;

        TestHttpResponse(int statusCode, String rawBody, Headers headers, ObjectMapper mapper) {
            this.statusCode = statusCode;
            this.rawBody = rawBody;
            this.headers = headers;
            this.mapper = mapper;
        }

        public int code() {
            return statusCode;
        }

        public String body() {
            return rawBody;
        }

        public String header(String name) {
            return headers.get(name);
        }

        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }

        /**
         * Parses the response body as JSON.
         *
         * @throws RuntimeException if the body is not valid JSON
         */
        public JsonNode bodyAsJson() {
            try {
                return mapper.readTree(rawBody);
            } catch (Exception e) {
                throw new RuntimeException("Response body is not valid JSON: " + rawBody, e);
            }
        }

        /**
         * Deserializes the response body into the given type.
         */
        public <T> T bodyAs(Class<T> type) {
            try {
                return mapper.readValue(rawBody, type);
            } catch (Exception e) {
                throw new RuntimeException("Cannot deserialize response body to " + type.getSimpleName(), e);
            }
        }

        @Override
        public String toString() {
            return "TestHttpResponse{status=" + statusCode + ", body='" + rawBody + "'}";
        }
    }
}
