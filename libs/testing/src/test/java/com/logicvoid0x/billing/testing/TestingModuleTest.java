package com.logicvoid0x.billing.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestingModuleTest {
  @Test
  void moduleIsWired() {
    assertThat(TestingModule.name()).isEqualTo("testing");
  }
}
