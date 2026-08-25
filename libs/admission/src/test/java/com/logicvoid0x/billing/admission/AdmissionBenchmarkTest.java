package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Phase 10 "Done when": in-process admission well under 1 µs. Runs {@link AdmissionBenchmark} with
 * JMH (short iterations, in this JVM) and checks the average.
 */
class AdmissionBenchmarkTest {
  @Test
  void theBenchmarkMeasuresTheInProcessLeasePath() {
    AdmissionBenchmark b = new AdmissionBenchmark();
    b.setUp();
    Decision d = b.admitFromLease();
    assertThat(d.admitted()).isTrue();
    assertThat(d.path()).isEqualTo(Decision.Path.LEASE);
  }

  @Test
  void leaseAdmissionIsWellUnderOneMicrosecond() throws Exception {
    Options options =
        new OptionsBuilder()
            .include(AdmissionBenchmark.class.getName())
            .forks(0) // in the test JVM: Gradle's test classpath is not visible to a forked JVM
            .warmupIterations(3)
            .warmupTime(TimeValue.milliseconds(300))
            .measurementIterations(5)
            .measurementTime(TimeValue.milliseconds(300))
            .shouldFailOnError(true)
            .build();
    Collection<RunResult> results = new Runner(options).run();
    double nanos = results.iterator().next().getPrimaryResult().getScore();
    System.out.printf("AdmissionBenchmark.admitFromLease: %.1f ns/op%n", nanos);
    // "Well under": a quarter of the 1 µs budget, leaving room for slower CI machines.
    assertThat(nanos).isLessThan(250.0);
  }
}
