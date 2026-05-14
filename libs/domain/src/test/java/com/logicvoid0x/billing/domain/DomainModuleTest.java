package com.logicvoid0x.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainModuleTest {
  @Test
  void moduleIsWired() {
    assertThat(DomainModule.name()).isEqualTo("domain");
  }
}
