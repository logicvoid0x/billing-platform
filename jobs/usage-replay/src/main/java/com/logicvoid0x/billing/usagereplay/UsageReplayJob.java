package com.logicvoid0x.billing.usagereplay;

/** Entry point of the usage-replay Flink job; the pipeline is built in its phase. */
public final class UsageReplayJob {
  private UsageReplayJob() {}

  /** Job name used when submitting to the cluster. */
  public static String jobName() {
    return "usage-replay";
  }

  /** Submits the job. */
  public static void main(String[] args) {
    System.out.println(jobName());
  }
}
