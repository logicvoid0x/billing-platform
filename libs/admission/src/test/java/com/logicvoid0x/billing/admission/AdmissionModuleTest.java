package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdmissionModuleTest {
  @Test
  void moduleIsWired() {
    assertThat(AdmissionModule.name()).isEqualTo("admission");
  }
}
