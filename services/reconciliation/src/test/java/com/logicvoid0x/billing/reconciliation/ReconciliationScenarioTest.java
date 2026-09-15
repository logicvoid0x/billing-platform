package com.logicvoid0x.billing.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.GatewayLogEntry;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.ContractPrice;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.reconciliation.completeness.CompletenessRun;
import com.logicvoid0x.billing.reconciliation.trueup.TrueUp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 "Done when", in process: a host spool loses events, a contract is backdated after the
 * hour was rated, and a gateway crashes mid-stream. The completeness loop, re-rating and the
 * true-up end with zero completeness drift and recognized usage equal to the rated total.
 *
 * <p>The pieces under test are the real ones (CompletenessRun, TrueUp, the rating library); the
 * stores are maps that stand in for the topics, the archive and both ledgers.
 */
class ReconciliationScenarioTest {
  private static final String ORG = "org_bd";
  private static final Instant HOUR = GoldenCases.HOUR;
  private static final Instant NOW = HOUR.plusSeconds(4 * 3_600);

  private static RatingLibrary library(boolean backdated) {
    RatingCatalog g = GoldenCases.catalog();
    List<ContractPrice> contracts = new ArrayList<>(g.contractPrices());
    if (backdated) {
      contracts.add(
          ContractPrice.discount(
              ORG,
              "sku_out",
              g.listPrices().get(0).pricing(),
              2000,
              EffectivePeriod.from(GoldenCases.V1)));
    }
    return new CatalogRatingLibrary(
        new RatingCatalog(
            backdated ? "golden-2" : "golden-1",
            g.skus(),
            g.bandRules(),
            g.listPrices(),
            contracts,
            g.weights()),
        "rating-1");
  }

  private static byte[] gatewayLog(String id, long output, Instant at) {
    return GatewayLogEntry.newBuilder()
        .setRequestId(id)
        .setOrgId(ORG)
        .setWorkspaceId("ws_1")
        .setApiKeyId("key_1")
        .setModel("model-x")
        .setEventTime(Timestamp.newBuilder().setSeconds(at.getEpochSecond()))
        .putQuantities("output_tokens", output)
        .setOutcome("completed")
        .build()
        .toByteArray();
  }

  @Test
  void droppedEventsABackdatedPriceAndACrashedGatewayEndWithZeroDrift() throws Exception {
    // Ten requests of 1M output tokens in the hour, as the gateway logged them.
    List<byte[]> gatewayLogTopic = new ArrayList<>();
    Map<String, Long> delivered = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      String id = "req_" + i;
      gatewayLogTopic.add(gatewayLog(id, 1_000_000, HOUR.plusSeconds(60L * i)));
      delivered.put(id, 1_000_000L);
    }
    // req_9's gateway crashed mid-stream after logging what the customer saw: its holds
    // auto-settled
    // at the held amount, so the real-time path recognized more than it delivered.
    // req_3 and req_7 were lost from a host spool: never archived, never metered.
    Set<String> archive = new HashSet<>(delivered.keySet());
    archive.removeAll(Set.of("req_3", "req_7"));

    // Real-time recognition (USAGE_ROLLUP) at list price, from what the shards settled.
    RatingLibrary before = library(false);
    long rollups = 0;
    for (String id : delivered.keySet()) {
      if (id.equals("req_3") || id.equals("req_7")) {
        continue; // settled on the gateway, but the rollup path is separate; they were recognized
      }
      rollups += id.equals("req_9") ? 2_000 : 1_500; // req_9 auto-settled at its hold
    }
    rollups += 2 * 1_500; // req_3 and req_7 settled at the gateway too

    // 1. Completeness: the two lost requests are re-emitted with their original IDs.
    List<RequestUsage> reemitted = new ArrayList<>();
    CompletenessRun run =
        new CompletenessRun(
            (topic, from, until) -> topic.equals("gateway.log") ? gatewayLogTopic : List.of(),
            hour -> Set.copyOf(archive),
            missing -> {
              reemitted.addAll(missing);
              missing.forEach(u -> archive.add(u.requestId()));
            });
    assertThat(run.run(HOUR, NOW).drift()).isEqualTo(2);
    assertThat(reemitted)
        .extracting(RequestUsage::requestId)
        .containsExactlyInAnyOrder("req_3", "req_7");
    assertThat(run.run(HOUR, NOW).drift()).as("completeness drift after the re-emit").isZero();

    // 2. Rating of the hour from the archive (all ten requests now), version 1 at list price.
    long hourTokens = archive.size() * 1_000_000L;
    Map<Integer, Long> ratedByVersion = new HashMap<>();
    ratedByVersion.put(1, before.chargeCents(ORG, "sku_out", 0, hourTokens, HOUR));

    // 3. True-up v1: the real-time path recognized 15,000 + 500 too much (req_9's hold).
    Map<String, Long> trueUps = new HashMap<>();
    long[] recognizedBase = {rollups};
    TrueUp trueUp =
        new TrueUp(
            (org, hour, v) ->
                ratedByVersion.entrySet().stream()
                    .filter(e -> e.getKey() <= v)
                    .mapToLong(Map.Entry::getValue)
                    .sum(),
            (org, hour) ->
                recognizedBase[0] + trueUps.values().stream().mapToLong(Long::longValue).sum(),
            (key, org, gap, hour) -> trueUps.putIfAbsent(key, gap) == null);
    trueUp.trueUp(ORG, HOUR, 1);

    // 4. A contract is backdated: re-rating posts v2 as the difference only.
    long rerated = library(true).chargeCents(ORG, "sku_out", 0, hourTokens, HOUR);
    ratedByVersion.put(2, rerated - ratedByVersion.get(1));
    trueUp.trueUp(ORG, HOUR, 2);
    trueUp.trueUp(ORG, HOUR, 2); // a retried run posts nothing

    long rated = ratedByVersion.values().stream().mapToLong(Long::longValue).sum();
    long recognized =
        recognizedBase[0] + trueUps.values().stream().mapToLong(Long::longValue).sum();
    assertThat(rated).as("10M tokens at Acme-style 20% off: $12/M").isEqualTo(12_000);
    assertThat(recognized).as("every balance equals its rated total").isEqualTo(rated);
    assertThat(trueUps).hasSize(2);
    assertThat(delivered.get("req_9")).isEqualTo(1_000_000);
  }
}
