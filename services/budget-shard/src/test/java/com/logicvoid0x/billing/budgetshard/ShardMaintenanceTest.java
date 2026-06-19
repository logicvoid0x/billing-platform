package com.logicvoid0x.billing.budgetshard;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import com.logicvoid0x.billing.domain.budget.Rollup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ShardMaintenanceTest {
  private static final long T0 = 1_793_000_000_000L;
  private final AtomicLong now = new AtomicLong(T0);
  private final Clock clock =
      new Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return Instant.ofEpochMilli(now.get());
        }
      };

  @Test
  void ticksThenRollsUpTheClosedWindowOnce() {
    InMemoryBudgetStore store = new InMemoryBudgetStore(1, clock);
    store.open("org_1:balance", "org_1", 10_000);
    List<List<Rollup>> sent = new ArrayList<>();
    ShardMaintenance task =
        new ShardMaintenance(
            new ShardMaintenance.Store() {
              @Override
              public void tick() {
                store.tick();
              }

              @Override
              public List<Rollup> rollup(String region, long windowEndMillis) {
                return store.rollup(region, windowEndMillis);
              }
            },
            "us-east",
            clock,
            sent::add);

    store.reserve("req_1", 0, Map.of("org_1:balance", 300L));
    store.settle("req_1", 1, Map.of("org_1:balance", 250L));
    now.set(T0 + 5_200);
    task.run();
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0))
        .containsExactly(new Rollup("org_1", "org_1:balance", "us-east", 250, 0, T0 + 5_000));

    task.run(); // same closed window: replayed rollups, the ledger dedupes by key
    assertThat(sent).hasSize(2);
    assertThat(sent.get(1)).isEqualTo(sent.get(0));

    now.set(T0 + 10_100);
    task.run(); // nothing settled since: nothing sent
    assertThat(sent).hasSize(2);
  }

  @Test
  void windowEndsAreAlignedToFiveSeconds() {
    assertThat(ShardMaintenance.closedWindowEnd(T0 + 4_999)).isEqualTo(T0);
    assertThat(ShardMaintenance.closedWindowEnd(T0 + 5_000)).isEqualTo(T0 + 5_000);
  }
}
