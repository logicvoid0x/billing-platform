package com.logicvoid0x.billing.balanceledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class BalanceLedgerApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(BalanceLedgerApplication.class.isAnnotationPresent(SpringBootApplication.class))
        .isTrue();
  }
}
