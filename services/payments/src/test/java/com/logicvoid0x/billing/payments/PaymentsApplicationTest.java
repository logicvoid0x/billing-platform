package com.logicvoid0x.billing.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class PaymentsApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(PaymentsApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
  }
}
