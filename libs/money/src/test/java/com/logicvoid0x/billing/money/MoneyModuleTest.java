package com.logicvoid0x.billing.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyModuleTest {
  @Test
  void moduleIsWired() {
    assertThat(MoneyModule.name()).isEqualTo("money");
  }
}
