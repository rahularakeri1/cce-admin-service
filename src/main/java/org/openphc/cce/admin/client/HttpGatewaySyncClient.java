package org.openphc.cce.admin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.openphc.cce.admin.exception.SyncFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** gateway-service answers 200 (all synced), 206 (some roles failed) or 500 (error). */
@Component
public class HttpGatewaySyncClient implements GatewaySyncClient {

  private final HttpClient http;
  private final ObjectMapper objectMapper;
  private final URI endpoint;
  private final Duration timeout;

  public HttpGatewaySyncClient(
      ObjectMapper objectMapper,
      @Value("${gateway.url:http://localhost:8090}") String gatewayUrl,
      @Value("${gateway.sync-timeout-seconds:20}") long timeoutSeconds) {
    this.objectMapper = objectMapper;
    this.endpoint = URI.create(gatewayUrl.replaceAll("/+$", "") + "/api/permissions/sync-to-keycloak");
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(5, timeoutSeconds))).build();
  }

  @Override
  public GatewaySyncResult sync() {
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SyncFailedException("Interrupted while calling gateway-service", e);
    } catch (IOException e) {
      String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      throw new SyncFailedException("Could not reach gateway-service at " + endpoint + " (" + reason + ")", e);
    }

    int status = response.statusCode();
    if (status != 200 && status != 206) {
      throw new SyncFailedException("gateway-service answered HTTP " + status + ": " + abbreviate(response.body()));
    }
    return parse(response.body());
  }

  private GatewaySyncResult parse(String body) {
    try {
      JsonNode json = objectMapper.readTree(body);
      List<String> failed = new ArrayList<>();
      JsonNode failedNode = json.get("failedRoles");
      if (failedNode != null && failedNode.isArray()) {
        failedNode.forEach(n -> failed.add(n.asText()));
      }
      return new GatewaySyncResult(
          json.path("successCount").asInt(),
          json.path("failureCount").asInt(),
          json.path("deletedCount").asInt(),
          failed,
          json.path("message").asText(""));
    } catch (IOException e) {
      throw new SyncFailedException("Unreadable response from gateway-service: " + abbreviate(body), e);
    }
  }

  private static String abbreviate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200) + "…";
  }
}
