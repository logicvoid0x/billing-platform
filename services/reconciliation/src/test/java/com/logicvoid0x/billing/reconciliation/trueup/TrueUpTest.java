package com.logicvoid0x.billing.reconciliation.trueup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrueUpTest {
  private static final Instant H = Instant.parse("2026-11-01T10:00:00Z");

  /** Rated cents per version (each version is an adjustment of the hour), and recognized usage. */
  private final Map<Integer, Long> ratedByVersion = new HashMap<>();

  private long rollups;
  private final Map<String, Long> trueUps = new HashMap<>();

  private final TrueUp trueUp =
      new TrueUp(
          (org, hour, v) ->
              ratedByVersion.entrySet().stream()
                  .filter(e -> e.getKey() <= v)
                  .mapToLong(Map.Entry::getValue)
                  .sum(),
          (org, hour) -> rollups + trueUps.values().stream().mapToLong(Long::longValue).sum(),
          (key, org, gap, hour) -> trueUps.putIfAbsent(key, gap) == null);

  @Test
  void theGapIsPostedOncePerVersionAndARerateAddsOneMoreTrueUp() {
    ratedByVersion.put(1, 48_000L);
    rollups = 47_950; // the real-time path recognized slightly less
    assertThat(trueUp.trueUp("org_acme", H.plusSeconds(1_800), 1))
        .contains(new TrueUp.Posted("org_acme:" + H + ":1", 50));
    assertThat(trueUp.trueUp("org_acme", H, 1)).as("posted once per version").isEmpty();

    ratedByVersion.put(2, -9_600L); // a backdated contract: v2 adjusts the hour down
    assertThat(trueUp.trueUp("org_acme", H, 2))
        .contains(new TrueUp.Posted("org_acme:" + H + ":2", -9_600));
    assertThat(rollups + trueUps.values().stream().mapToLong(Long::longValue).sum())
        .as("recognized usage now equals the rated total")
        .isEqualTo(48_000 - 9_600);
  }

  @Test
  void noGapPostsNothing() {
    ratedByVersion.put(1, 1_000L);
    rollups = 1_000;
    assertThat(trueUp.trueUp("org_zero", H, 1)).isEmpty();
    assertThat(trueUps).isEmpty();
  }
}
