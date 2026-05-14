package com.logicvoid0x.billing.usageaggregator;

/** Entry point of the usage-aggregator Flink job; the pipeline is built in its phase. */
public final class UsageAggregatorJob {
  private UsageAggregatorJob() {}

  /** Job name used when submitting to the cluster. */
  public static String jobName() {
    return "usage-aggregator";
  }

  /** Submits the job. */
  public static void main(String[] args) {
    System.out.println(jobName());
  }
}
