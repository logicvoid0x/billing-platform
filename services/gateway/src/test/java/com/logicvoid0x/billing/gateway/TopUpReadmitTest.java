package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.admission.AdmissionRequest;
import com.logicvoid0x.billing.admission.BudgetRef;
import com.logicvoid0x.billing.admission.BudgetResolver;
import com.logicvoid0x.billing.admission.ModeCache;
import com.logicvoid0x.billing.contracts.v1.BudgetChange;
import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan Phase 11: a topped-up account at $0 is admitted again within 5 s, because the budget change
 * drops the gateway's cached DENY; a replayed change does nothing.
 */
class TopUpReadmitTest {
  private static final String BAL = "org_zero:balance";

  @Test
  void aTopUpDropsTheCachedDenySoTheNextRequestIsAdmitted() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    InMemoryBudgetStore shard = new InMemoryBudgetStore(5, clock);
    shard.open(BAL, "org_zero", 0);
    shard.tick();
    AdmissionController admission =
        new AdmissionController("gw-1", shard, new ModeCache(), new BudgetResolver(), clock);
    AdmissionRequest request =
        new AdmissionRequest(
            "req_1",
            "org_zero",
            List.of(new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.closed())),
            true);

    assertThat(admission.admit(request, Map.of(BAL, 10L)).admitted()).isFalse(); // DENY cached

    // The webhook posted a top-up; the home shard got the credit.
    shard.addCapacity(BAL, 10_000);
    shard.tick();
    assertThat(admission.admit(request, Map.of(BAL, 10L)).admitted())
        .as("still the cached DENY: the clock has not moved")
        .isFalse();

    BudgetChangeListener listener = new BudgetChangeListener(admission);
    byte[] change =
        BudgetChange.newBuilder()
            .setBudgetId(BAL)
            .setOrgId("org_zero")
            .setVersion(1_793_527_200_000L)
            .setCause("top_up")
            .build()
            .toByteArray();
    assertThat(listener.apply(change)).isTrue();
    assertThat(admission.admit(request, Map.of(BAL, 10L)).admitted())
        .as("admitted at once, well within 5 s")
        .isTrue();
    assertThat(listener.apply(change)).as("a replayed change is ignored").isFalse();
  }
}
