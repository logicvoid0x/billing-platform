package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.admission.BudgetRef;
import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code POST /v1/messages}: the customer-facing entry. Streams the output as Server-Sent Events
 * ({@code chunk}), then one {@code result} with the request ID, outcome and settled cents.
 * Authentication and the budget registry are stubs here: the body names the org, workspace and key,
 * and the org's balance budget {@code <org>:balance} applies, fail-closed.
 */
@RestController
@ConditionalOnBean(Gateway.class)
public class GatewayController implements AutoCloseable {
  /** Request body. */
  public record MessageRequest(
      String orgId,
      String workspaceId,
      String apiKeyId,
      String model,
      String serviceTier,
      long inputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      long maxOutputTokens,
      boolean extraUsage,
      long seed) {}

  private final Gateway gateway;
  private final String homeRegion;
  private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

  /** Creates the controller. */
  public GatewayController(Gateway gateway, GatewaySettings settings) {
    this.gateway = gateway;
    this.homeRegion = settings.homeRegion();
  }

  /** Admits, streams and settles one request. */
  @PostMapping(path = "/v1/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter messages(@RequestBody MessageRequest m) {
    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());
    GatewayRequest request =
        new GatewayRequest(
            m.orgId(),
            m.workspaceId(),
            m.apiKeyId(),
            m.model(),
            m.serviceTier(),
            m.inputTokens(),
            m.cacheReadTokens(),
            m.cacheWriteTokens(),
            m.maxOutputTokens(),
            List.of(new BudgetRef(m.orgId() + ":balance", BudgetKind.BALANCE, FailPolicy.closed())),
            m.extraUsage(),
            homeRegion,
            m.seed());
    streams.execute(() -> {
      try {
        Gateway.Response r = gateway.handle(request, text -> send(emitter, "chunk", text));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_id", r.requestId());
        result.put("outcome", r.outcome());
        result.put("reason", r.decision().reason());
        result.put("settled_cents", r.settledCents());
        send(emitter, "result", result);
        emitter.complete();
      } catch (RuntimeException e) {
        emitter.completeWithError(e);
      }
    });
    return emitter;
  }

  private static void send(SseEmitter emitter, String name, Object data) {
    try {
      emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      throw new IllegalStateException("client went away", e);
    }
  }

  @Override
  public void close() {
    streams.shutdownNow();
  }
}
