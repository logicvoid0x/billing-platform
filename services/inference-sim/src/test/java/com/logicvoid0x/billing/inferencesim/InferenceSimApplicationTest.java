package com.logicvoid0x.billing.inferencesim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class InferenceSimApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(InferenceSimApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
  }
}
