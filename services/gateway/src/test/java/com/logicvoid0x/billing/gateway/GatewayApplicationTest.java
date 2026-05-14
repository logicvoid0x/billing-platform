package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class GatewayApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(GatewayApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
  }
}
