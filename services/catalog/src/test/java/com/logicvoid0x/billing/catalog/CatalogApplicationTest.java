package com.logicvoid0x.billing.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class CatalogApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(CatalogApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
  }
}
