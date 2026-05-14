package com.logicvoid0x.billing.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractsModuleTest {
  @Test
  void moduleIsWired() {
    assertThat(ContractsModule.name()).isEqualTo("contracts");
  }
}
