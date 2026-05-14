package com.logicvoid0x.billing.usageaggregator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UsageAggregatorJobTest {
  @Test
  void hasJobName() {
    assertThat(UsageAggregatorJob.jobName()).isEqualTo("usage-aggregator");
  }
}
