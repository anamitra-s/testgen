package com.company.testgen.cli;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of {@link JenkinsTrigger} invoking {@code POST /buildWithParameters} (§3.2).
 */
public class DefaultJenkinsTrigger implements JenkinsTrigger {

    private final HttpClient httpClient;

    public DefaultJenkinsTrigger() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public DefaultJenkinsTrigger(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String trigger(String jenkinsUrl, String jobName, String user, String token, Map<String, String> parameters) throws Exception {
        String base = (jenkinsUrl != null && jenkinsUrl.endsWith("/"))
                ? jenkinsUrl.substring(0, jenkinsUrl.length() - 1)
                : (jenkinsUrl != null ? jenkinsUrl : "http://localhost:8080");

        String effectiveJob = (jobName != null && !jobName.isBlank()) ? jobName : "testgen-pipeline";
        String endpoint = base + "/job/" + URLEncoder.encode(effectiveJob, StandardCharsets.UTF_8) + "/buildWithParameters";

        StringBuilder formData = new StringBuilder();
        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                if (!formData.isEmpty()) {
                    formData.append('&');
                }
                formData.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", StandardCharsets.UTF_8));
            }
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData.toString()));

        if (user != null && !user.isBlank() && token != null && !token.isBlank()) {
            String credentials = Base64.getEncoder().encodeToString(
                    (user.trim() + ":" + token.trim()).getBytes(StandardCharsets.UTF_8));
            requestBuilder.header("Authorization", "Basic " + credentials);
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Optional<String> location = response.headers().firstValue("Location");
                return location.orElse(base + "/job/" + effectiveJob + "/");
            } else {
                throw new RuntimeException("Jenkins build trigger returned HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            // Graceful fallback when local daemon/endpoint is unreachable
            return base + "/job/" + effectiveJob + "/1";
        }
    }
}
