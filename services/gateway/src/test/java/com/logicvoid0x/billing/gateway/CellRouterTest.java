package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CellRouterTest {
  private static List<String> hosts(int n) {
    List<String> h = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      h.add("gw-" + i);
    }
    return h;
  }

  @Test
  void anOrgGetsADeterministicCellOfDistinctHosts() {
    CellRouter a = new CellRouter(hosts(64), 16, 128);
    CellRouter b = new CellRouter(hosts(64), 16, 128);
    List<String> cell = a.cell("org_acme");
    assertThat(cell).hasSize(16).doesNotHaveDuplicates();
    assertThat(b.cell("org_acme")).as("same on every gateway").isEqualTo(cell);
    assertThat(cell).contains(a.host("org_acme", "req_1"));
  }

  @Test
  void cellsSpreadOrgsEvenlyOverHosts() {
    CellRouter r = new CellRouter(hosts(64), 8, 128);
    Map<String, Integer> slots = new HashMap<>();
    for (int i = 0; i < 4_000; i++) {
      for (String h : r.cell("org_" + i)) {
        slots.merge(h, 1, Integer::sum);
      }
    }
    // 4,000 orgs x 8 slots over 64 hosts = 500 each on average.
    assertThat(slots).hasSize(64);
    assertThat(slots.values()).allSatisfy(n -> assertThat(n).isBetween(350, 650));
  }

  @Test
  void addingAHostMovesOnlyAboutOneNthOfTheSlots() {
    CellRouter before = new CellRouter(hosts(32), 8, 128);
    List<String> grown = hosts(32);
    grown.add("gw-new");
    CellRouter after = new CellRouter(grown, 8, 128);
    int moved = 0;
    int total = 0;
    for (int i = 0; i < 4_000; i++) {
      Set<String> was = new HashSet<>(before.cell("org_" + i));
      for (String h : after.cell("org_" + i)) {
        total++;
        if (!was.contains(h)) {
          moved++;
        }
      }
    }
    // Ideal: 1/33 of the slots go to the new host, about 3%.
    assertThat((double) moved / total).isBetween(0.01, 0.06);
  }

  @Test
  void requestsSpreadAcrossTheCellAndSmallRegionsCapTheCell() {
    CellRouter r = new CellRouter(hosts(40), 8, 64);
    Set<String> used = new HashSet<>();
    for (int i = 0; i < 400; i++) {
      used.add(r.host("org_busy", "req_" + i));
    }
    assertThat(used).containsExactlyInAnyOrderElementsOf(r.cell("org_busy"));
    assertThat(new CellRouter(hosts(5), 8, 64).cell("org_1")).hasSize(5);
    assertThatThrownBy(() -> new CellRouter(hosts(40), 4, 64))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CellRouter(hosts(40), 33, 64))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
