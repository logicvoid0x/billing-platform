package com.logicvoid0x.billing.ratingengine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class RatingEngineApplicationTest {
  @Test
  void isSpringBootApplication() {
    assertThat(RatingEngineApplication.class.isAnnotationPresent(SpringBootApplication.class))
        .isTrue();
  }
}
