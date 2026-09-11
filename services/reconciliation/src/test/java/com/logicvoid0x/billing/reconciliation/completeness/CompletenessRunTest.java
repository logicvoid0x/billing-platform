package com.logicvoid0x.billing.reconciliation.completeness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.GatewayLogEntry;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletenessRunTest {
  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-01T13:00:00Z");

  private static byte[] log(String id, Instant at) {
    return GatewayLogEntry.newBuilder()
        .setRequestId(id)
        .setOrgId("org_1")
        .setWorkspaceId("ws_1")
        .setApiKeyId("key_1")
        .setModel("model-x")
        .setEventTime(Timestamp.newBuilder().setSeconds(at.getEpochSecond()))
        .putQuantities("output_tokens", 10)
        .build()
        .toByteArray();
  }

  @Test
  void aDroppedRequestIsReemittedAndTheNextRunOfTheHourHasZeroDrift() throws Exception {
    Map<String, List<byte[]>> topics =
        Map.of(
            "gateway.log",
            List.of(log("req_ok", H10.plusSeconds(60)), log("req_lost", H10.plusSeconds(120))),
            "usage.dlq",
            List.of(),
            "usage.corrections",
            List.of());
    Set<String> archive = new HashSet<>(Set.of("req_ok"));
    List<RequestUsage> sent = new ArrayList<>();
    CompletenessRun run =
        new CompletenessRun(
            (topic, from, until) -> topics.get(topic),
            hour -> Set.copyOf(archive),
            missing -> {
              sent.addAll(missing);
              missing.forEach(u -> archive.add(u.requestId())); // the re-emit reaches the archive
            });

    assertThat(run.run(H10, NOW).drift()).isEqualTo(1);
    assertThat(sent).extracting(RequestUsage::requestId).containsExactly("req_lost");
    assertThat(run.run(H10, NOW).drift()).as("second run of the hour").isZero();
    assertThat(sent).hasSize(1);
  }

  @Test
  void unsealedHoursAreRefused() {
    CompletenessRun run = new CompletenessRun((t, f, u) -> List.of(), h -> Set.of(), m -> {});
    assertThatThrownBy(() -> run.run(H10, H10.plusSeconds(7_000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not sealed");
  }
}
