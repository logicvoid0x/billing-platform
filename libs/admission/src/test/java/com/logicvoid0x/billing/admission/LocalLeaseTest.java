package com.logicvoid0x.billing.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LocalLeaseTest {
  private static final long T0 = 1_793_000_000_000L;

  @Test
  void spendsUpToTheGrantedAmountOnly() {
    LocalLease lease = new LocalLease("org_1:balance");
    lease.install(100, 3, T0 + 2_000);
    assertThat(lease.tryConsume(60, T0)).isTrue();
    assertThat(lease.tryConsume(41, T0)).isFalse();
    assertThat(lease.tryConsume(40, T0)).isTrue();
    assertThat(lease.remaining()).isZero();
    assertThat(lease.epoch()).isEqualTo(3);
  }

  @Test
  void anExpiredLeaseSpendsNothing() {
    LocalLease lease = new LocalLease("b");
    assertThat(lease.tryConsume(1, T0)).as("never installed").isFalse();
    lease.install(100, 1, T0 + 2_000);
    assertThat(lease.tryConsume(1, T0 + 1_999)).isTrue();
    assertThat(lease.tryConsume(1, T0 + 2_000)).isFalse();
  }

  @Test
  void refundsGoBackToALiveLeaseOnly() {
    LocalLease lease = new LocalLease("b");
    lease.install(100, 1, T0 + 2_000);
    lease.tryConsume(50, T0);
    assertThat(lease.refund(20, T0)).isTrue();
    assertThat(lease.remaining()).isEqualTo(70);
    assertThat(lease.refund(5, T0 + 3_000)).as("expired").isFalse();
    assertThatThrownBy(() -> lease.tryConsume(-1, T0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNewGrantReplacesTheOldOneAndReportsTheUnused() {
    LocalLease lease = new LocalLease("b");
    lease.install(100, 1, T0 + 2_000);
    lease.tryConsume(30, T0);
    assertThat(lease.install(200, 2, T0 + 4_000)).as("unused part of the old lease").isEqualTo(70);
    assertThat(lease.remaining()).isEqualTo(200);
    assertThat(lease.epoch()).isEqualTo(2);
  }

  @Test
  void concurrentSpendNeverExceedsTheLease() throws InterruptedException {
    LocalLease lease = new LocalLease("b");
    lease.install(10_000, 1, Long.MAX_VALUE);
    AtomicLong spent = new AtomicLong();
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch done = new CountDownLatch(50_000);
    for (int i = 0; i < 50_000; i++) {
      long amount = 1L + (i % 3);
      pool.execute(() -> {
        if (lease.tryConsume(amount, T0)) {
          spent.addAndGet(amount);
        }
        done.countDown();
      });
    }
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    pool.shutdown();
    assertThat(spent.get()).isEqualTo(10_000 - lease.remaining()).isLessThanOrEqualTo(10_000);
    assertThat(lease.remaining()).isBetween(0L, 2L);
  }
}
