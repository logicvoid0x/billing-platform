package com.logicvoid0x.billing.rating;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RatingModuleTest {
  @Test
  void moduleIsWired() {
    assertThat(RatingModule.name()).isEqualTo("rating");
  }
}
