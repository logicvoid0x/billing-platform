package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.MeterCode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HttpUpstream against a stub that speaks the simulator's SSE format and honours stops. */
class HttpUpstreamTest {
  private HttpServer server;
  private final List<String> stops = new CopyOnWriteArrayList<>();
  private final AtomicBoolean stopped = new AtomicBoolean();

  @BeforeEach
  void start() throws Exception {
    server =
        HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/requests",
        ex -> {
          if (ex.getRequestURI().getPath().endsWith("/stop")) {
            stops.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            stopped.set(true);
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
          }
          ex.getResponseHeaders().add("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          try (OutputStream out = ex.getResponseBody()) {
            long sent = 0;
            for (int i = 0; i < 20 && !stopped.get(); i++) {
              sent += 100;
              write(out, "chunk", "{\"index\":" + i + ",\"tokens\":100,\"text\":\"t" + i + "\"}");
              sleep();
            }
            String reason = stopped.get() ? "\"budget_exhausted\"" : "null";
            write(
                out,
                "result",
                "{\"request_id\":\"req_h\",\"outcome\":\""
                    + (stopped.get() ? "STOPPED" : "COMPLETED")
                    + "\",\"stop_reason\":"
                    + reason
                    + ",\"output_tokens\":"
                    + sent
                    + ",\"counts\":{\"input_tokens\":10,\"output_tokens\":"
                    + sent
                    + "}}");
          }
        });
    server.setExecutor(
        java.util.concurrent.Executors.newCachedThreadPool()); // stop while streaming
    server.start();
  }

  private static void write(OutputStream out, String event, String data)
      throws java.io.IOException {
    out.write(("event:" + event + "\ndata:" + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void sleep() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private HttpUpstream upstream() {
    return new HttpUpstream(
        HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        Duration.ofSeconds(10));
  }

  private static GatewayRequest request() {
    return new GatewayRequest(
        "org_acme",
        "ws_1",
        "key_1",
        "model-x",
        "standard",
        10,
        0,
        0,
        2_000,
        List.of(),
        false,
        "us-east",
        1);
  }

  @Test
  void streamsChunksAndReturnsTheHostsCounts() {
    List<String> texts = new CopyOnWriteArrayList<>();
    Upstream.Result r =
        upstream().stream(
            "req_h",
            request(),
            (tokens, text) -> {
              texts.add(text);
              return Optional.empty();
            });
    assertThat(texts).hasSize(20).startsWith("t0");
    assertThat(r.outcome()).isEqualTo("completed");
    assertThat(r.counts())
        .containsEntry(MeterCode.OUTPUT_TOKENS, 2_000L)
        .containsEntry(MeterCode.INPUT_TOKENS, 10L);
    assertThat(stops).isEmpty();
  }

  @Test
  void aStopFromTheSinkIsSentOnceAndTheHostReportsWhatItDelivered() {
    Upstream.Result r =
        upstream().stream(
            "req_h",
            request(),
            (tokens, text) ->
                text.equals("t2") || text.equals("t3")
                    ? Optional.of("budget_exhausted")
                    : Optional.empty());
    assertThat(stops).hasSize(1).first().asString().contains("budget_exhausted");
    assertThat(r.stopReason()).contains("budget_exhausted");
    assertThat(r.counts().get(MeterCode.OUTPUT_TOKENS)).isLessThan(2_000L);
  }
}
