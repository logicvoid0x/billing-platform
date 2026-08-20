package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.domain.MeterCode;
import java.util.Map;
import java.util.Optional;

/** The inference host the gateway proxies to (the simulator in this repo). */
public interface Upstream {
  /** Receives each streamed chunk; a returned reason asks the host to stop at the next chunk. */
  @FunctionalInterface
  interface ChunkSink {
    /** Called per chunk with its output tokens; returns a stop reason to end the stream. */
    Optional<String> onChunk(long outputTokens, String text);
  }

  /**
   * What the host reported at the end of the stream.
   *
   * @param counts tokens by meter that the host delivered
   * @param outcome "completed" or "stopped"
   * @param stopReason why it stopped, if it did
   */
  record Result(Map<MeterCode, Long> counts, String outcome, Optional<String> stopReason) {
    /** Copies the counts. */
    public Result {
      counts = Map.copyOf(counts);
    }
  }

  /** Streams {@code request} under {@code requestId}, pushing each chunk to {@code sink}. */
  Result stream(String requestId, GatewayRequest request, ChunkSink sink);
}
