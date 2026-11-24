package org.openphc.cce.admin.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.admin.exception.SyncFailedException;

/** Talks to a throwaway local HTTP server standing in for gateway-service. */
class HttpGatewaySyncClientTest {

  private HttpServer server;
  private final AtomicInteger calls = new AtomicInteger();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private HttpGatewaySyncClient clientAnswering(int status, String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/permissions/sync-to-keycloak",
        exchange -> {
          calls.incrementAndGet();
          assertThat(exchange.getRequestMethod()).isEqualTo("POST");
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return new HttpGatewaySyncClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/", 5);
  }

  @Test
  void a200IsAFullSuccess() throws Exception {
    GatewaySyncResult result =
        clientAnswering(200, "{\"success\":true,\"successCount\":8,\"failureCount\":0,\"deletedCount\":1,\"message\":\"ok\"}").sync();

    assertThat(result.fullySuccessful()).isTrue();
    assertThat(result.successCount()).isEqualTo(8);
    assertThat(result.deletedCount()).isEqualTo(1);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void a206CarriesTheFailedRoles() throws Exception {
    GatewaySyncResult result =
        clientAnswering(
                206,
                "{\"success\":false,\"successCount\":6,\"failureCount\":2,\"deletedCount\":0,\"message\":\"partial\",\"failedRoles\":[\"A_ROLE\",\"B_ROLE\"]}")
            .sync();

    assertThat(result.fullySuccessful()).isFalse();
    assertThat(result.failedRoles()).containsExactly("A_ROLE", "B_ROLE");
  }

  @Test
  void a500IsASyncFailure() throws Exception {
    HttpGatewaySyncClient client = clientAnswering(500, "{\"success\":false,\"message\":\"boom\"}");

    assertThatThrownBy(client::sync)
        .isInstanceOf(SyncFailedException.class)
        .hasMessageContaining("HTTP 500");
  }

  @Test
  void aNonJsonBodyOn200IsASyncFailureNotACrash() throws Exception {
    HttpGatewaySyncClient client = clientAnswering(200, "<html>not json</html>");

    assertThatThrownBy(client::sync).isInstanceOf(SyncFailedException.class).hasMessageContaining("Unreadable");
  }

  @Test
  void anUnreachableGatewayIsASyncFailure() {
    // Port 1 is never listening.
    HttpGatewaySyncClient client = new HttpGatewaySyncClient(new ObjectMapper(), "http://127.0.0.1:1", 2);

    assertThatThrownBy(client::sync)
        .isInstanceOf(SyncFailedException.class)
        .hasMessageContaining("Could not reach gateway-service")
        .hasMessageNotContaining("null");
  }
}
