package com.logicvoid0x.billing.usageview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class UsageViewApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(UsageViewApplication.class.isAnnotationPresent(SpringBootApplication.class))
        .isTrue();
  }
}
