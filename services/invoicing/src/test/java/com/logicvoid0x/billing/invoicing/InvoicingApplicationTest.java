package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class InvoicingApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(InvoicingApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
  }
}
