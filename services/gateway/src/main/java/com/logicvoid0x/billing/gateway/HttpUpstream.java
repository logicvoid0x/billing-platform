package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.domain.MeterCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The inference simulator over HTTP: {@code POST /v1/requests} streams Server-Sent Events ({@code
 * chunk} per piece of output, then one {@code result}). A stop asked by the sink is sent as {@code
 * POST /v1/requests/{id}/stop}; the host stops at its next chunk boundary and still reports what it
 * delivered.
 */
public final class HttpUpstream implements Upstream {
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final HttpClient http;
  private final URI base;
  private final Duration timeout;

  /** Creates the client for the simulator at {@code base}. */
  public HttpUpstream(HttpClient http, URI base, Duration timeout) {
    this.http = Objects.requireNonNull(http, "http");
    this.base = Objects.requireNonNull(base, "base");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public Result stream(String requestId, GatewayRequest req, ChunkSink sink) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("requestId", requestId);
    body.put("orgId", req.orgId());
    body.put("workspaceId", req.workspaceId());
    body.put("apiKeyId", req.apiKeyId());
    body.put("model", req.model());
    body.put("serviceTier", req.serviceTier());
    body.put("inputTokens", req.inputTokens());
    body.put("cacheReadTokens", req.cacheReadTokens());
    body.put("cacheWriteTokens", req.cacheWriteTokens());
    body.put("maxOutputTokens", req.maxOutputTokens());
    body.put("seed", req.seed());
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve("/v1/requests"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
    try {
      HttpResponse<InputStream> res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (res.statusCode() != 200) {
        throw new IllegalStateException("inference host answered HTTP " + res.statusCode());
      }
      return read(requestId, res.body(), sink);
    } catch (IOException e) {
      throw new UncheckedIOException("streaming from the inference host failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while streaming", e);
    }
  }

  private Result read(String requestId, InputStream in, ChunkSink sink) throws IOException {
    boolean stopSent = false;
    String event = null;
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.startsWith("event:")) {
          event = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          JsonNode data = JSON.readTree(line.substring("data:".length()).trim());
          if ("chunk".equals(event)) {
            Optional<String> stop =
                sink.onChunk(data.path("tokens").asLong(), data.path("text").asString(""));
            if (stop.isPresent() && !stopSent) {
              stopSent = true;
              sendStop(requestId, stop.get());
            }
          } else if ("result".equals(event)) {
            return result(data);
          }
        }
      }
    }
    throw new IllegalStateException("the inference host closed the stream without a result");
  }

  private static Result result(JsonNode data) {
    Map<MeterCode, Long> counts = new HashMap<>();
    JsonNode c = data.path("counts");
    for (String code : c.propertyNames()) {
      counts.put(new MeterCode(code), c.path(code).asLong());
    }
    String stop =
        data.path("stop_reason").isNull() ? null : data.path("stop_reason").asString(null);
    String outcome = data.path("outcome").asString("COMPLETED").toLowerCase(java.util.Locale.ROOT);
    return new Result(counts, outcome, Optional.ofNullable(stop).filter(s -> !s.isBlank()));
  }

  private void sendStop(String requestId, String reason) {
    HttpRequest stop =
        HttpRequest.newBuilder(base.resolve("/v1/requests/" + requestId + "/stop"))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(Map.of("reason", reason))))
            .build();
    // Asynchronous: the stream keeps being read while the stop travels.
    var unused = http.sendAsync(stop, HttpResponse.BodyHandlers.discarding());
  }
}
