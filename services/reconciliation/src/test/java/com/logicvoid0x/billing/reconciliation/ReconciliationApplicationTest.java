package com.logicvoid0x.billing.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class ReconciliationApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(ReconciliationApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
  }
}
