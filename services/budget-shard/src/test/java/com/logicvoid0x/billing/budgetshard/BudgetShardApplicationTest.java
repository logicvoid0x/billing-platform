package com.logicvoid0x.billing.budgetshard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class BudgetShardApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(BudgetShardApplication.class.isAnnotationPresent(SpringBootApplication.class))
        .isTrue();
  }
}
