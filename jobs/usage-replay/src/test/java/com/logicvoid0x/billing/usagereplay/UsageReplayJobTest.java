package com.logicvoid0x.billing.usagereplay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UsageReplayJobTest {
  @Test
  void hasJobName() {
    assertThat(UsageReplayJob.jobName()).isEqualTo("usage-replay");
  }
}
