package com.logicvoid0x.billing.inferencesim;

import com.logicvoid0x.billing.inferencesim.emitter.UsageSink;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP face of the simulator: {@code POST /v1/requests} streams {@code chunk} events then one
 * {@code result} event (server-sent events); {@code POST /v1/requests/{id}/stop} stops an active
 * stream at its next chunk boundary.
 */
@RestController
public class SimulatorController implements AutoCloseable {
  private final InferenceSimulator simulator;
  private final int chunkTokens;
  private final UsageSink usageSink;
  private final Map<String, StopSignal> active = new ConcurrentHashMap<>();
  private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

  /** Creates the controller from configuration. */
  public SimulatorController(
      @Value("${inference-sim.host-id:sim-host-1}") String hostId,
      @Value("${inference-sim.chunk-tokens:512}") int chunkTokens,
      @Value("${inference-sim.chunk-delay:0ms}") Duration chunkDelay,
      UsageSink usageSink) {
    this.simulator = new InferenceSimulator(hostId, Clock.systemUTC(), chunkDelay);
    this.chunkTokens = chunkTokens;
    this.usageSink = usageSink;
  }

  /** Starts a request and streams its output. */
  @PostMapping(path = "/v1/requests", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter start(@RequestBody SimulatedRequest request) {
    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());
    StopSignal stop = new StopSignal();
    if (active.putIfAbsent(request.requestId(), stop) != null) {
      throw new IllegalStateException("request already active: " + request.requestId());
    }
    streams.execute(() -> {
      try {
        InferenceResult result =
            simulator.run(
                request,
                chunkTokens,
                stop,
                chunk ->
                    send(
                        emitter,
                        stop,
                        "chunk",
                        Map.of(
                            "index",
                            chunk.index(),
                            "tokens",
                            chunk.tokens(),
                            "text",
                            chunk.text())));
        // Only the attempt that delivered output emits usage, under the original request ID.
        result.usageIfDelivered().ifPresent(usageSink::emit);
        send(emitter, stop, "result", result.toJson());
        emitter.complete();
      } catch (RuntimeException e) {
        emitter.completeWithError(e);
      } finally {
        active.remove(request.requestId(), stop);
      }
    });
    return emitter;
  }

  /** Stops an active request at its next chunk boundary. */
  @PostMapping(path = "/v1/requests/{requestId}/stop")
  public ResponseEntity<Void> stop(
      @PathVariable String requestId, @RequestBody Map<String, String> body) {
    StopSignal stop = active.get(requestId);
    if (stop == null) {
      return ResponseEntity.notFound().build();
    }
    stop.stop(body.get("reason"));
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  private static void send(SseEmitter emitter, StopSignal stop, String name, Object data) {
    try {
      emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    } catch (IOException e) {
      // The client went away: stop generating, as a real server would.
      stop.stop("client_disconnected");
    }
  }

  @Override
  public void close() {
    streams.shutdownNow();
  }
}
