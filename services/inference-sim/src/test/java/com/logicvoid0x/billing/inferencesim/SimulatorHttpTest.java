package com.logicvoid0x.billing.inferencesim;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"inference-sim.chunk-delay=20ms", "usage.emitter.enabled=false"})
class SimulatorHttpTest {
  @LocalServerPort int port;
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpRequest start(String requestId, long maxOutput) {
    String body =
        """
        {"requestId":"%s","orgId":"org_42","workspaceId":"ws_7","apiKeyId":"key_3",
         "model":"model-x","serviceTier":"standard","inputTokens":100,"cacheReadTokens":0,
         "cacheWriteTokens":0,"maxOutputTokens":%d,"seed":5}
        """
            .formatted(requestId, maxOutput);
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/requests"))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(Duration.ofSeconds(30))
        .build();
  }

  @Test
  void streamsChunkEventsThenAResultWithCountsByMeter() throws Exception {
    HttpResponse<String> res =
        http.send(start("req_http_1", 2_000), HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);
    List<String> events = res.body().lines().filter(l -> l.startsWith("event:")).toList();
    assertThat(events).contains("event:chunk").last().isEqualTo("event:result");
    assertThat(res.body()).contains("\"outcome\":\"COMPLETED\"").contains("\"output_tokens\"");
  }

  @Test
  void stopEndpointStopsAnActiveStream() throws Exception {
    CompletableFuture<HttpResponse<String>> pending =
        http.sendAsync(start("req_http_stop", 1_000_000), HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> stop = null;
    for (int i = 0; i < 100 && (stop == null || stop.statusCode() != 202); i++) {
      TimeUnit.MILLISECONDS.sleep(50);
      stop =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/v1/requests/req_http_stop/stop"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"budget_exhausted\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    }
    assertThat(stop).isNotNull();
    assertThat(stop.statusCode()).isEqualTo(202);
    HttpResponse<String> res = pending.get(30, TimeUnit.SECONDS);
    assertThat(res.body())
        .contains("\"outcome\":\"STOPPED\"")
        .contains("\"stop_reason\":\"budget_exhausted\"");
  }

  @Test
  void stoppingAnUnknownRequestIsNotFound() throws Exception {
    HttpResponse<String> res =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v1/requests/nope/stop"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"x\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(404);
  }
}
