package com.logicvoid0x.billing.budgetshard.ratis;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.domain.budget.BudgetBalance;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Three-node shard under repeated leader kills during concurrent reserves and settles: no double
 * spend, no lost confirmed hold, and every replica converges to the same balances.
 */
class RatisFailoverTest {
  private static final String B = "org_1:balance";
  private static final long CAPACITY = 5_000;
  private static final long HOLD = 10;
  private static final int THREADS = 8;
  private static final int REQUESTS_PER_THREAD = 120;

  @TempDir Path dir;

  private RaftGroup group;
  private final List<RaftPeer> peers = new ArrayList<>();
  private final RaftServer[] servers = new RaftServer[3];

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  private RaftProperties props(int i) {
    RaftProperties p = new RaftProperties();
    RaftServerConfigKeys.setStorageDir(p, List.of(dir.resolve("s" + i).toFile()));
    String address = peers.get(i).getAddress();
    GrpcConfigKeys.Server.setPort(
        p, Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
    RaftServerConfigKeys.Rpc.setTimeoutMin(p, TimeDuration.valueOf(300, TimeUnit.MILLISECONDS));
    RaftServerConfigKeys.Rpc.setTimeoutMax(p, TimeDuration.valueOf(600, TimeUnit.MILLISECONDS));
    return p;
  }

  private void startServer(int i, RaftStorage.StartupOption option) throws IOException {
    servers[i] =
        RaftServer.newBuilder()
            .setGroup(group)
            .setServerId(peers.get(i).getId())
            .setProperties(props(i))
            .setStateMachine(new BudgetStateMachine(1))
            .setOption(option)
            .build();
    servers[i].start();
  }

  private BudgetStateMachine machine(int i) throws IOException {
    return (BudgetStateMachine) servers[i].getDivision(group.getGroupId()).getStateMachine();
  }

  private int leader() throws IOException {
    for (int i = 0; i < 3; i++) {
      if (servers[i] != null && servers[i].getDivision(group.getGroupId()).getInfo().isLeader()) {
        return i;
      }
    }
    return -1;
  }

  /** Retries one idempotent call: the same requestId and sequence, so a lost reply is safe. */
  private static <T> T retry(Supplier<T> call) {
    RuntimeException last = null;
    for (int attempt = 0; attempt < 50; attempt++) {
      try {
        return call.get();
      } catch (RuntimeException e) {
        last = e;
        try {
          TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
    throw last;
  }

  @Test
  void leaderKillsLoseNoConfirmedHoldAndNeverDoubleSpend() throws Exception {
    for (int i = 0; i < 3; i++) {
      peers.add(RaftPeer.newBuilder().setId("s" + i).setAddress("localhost:" + freePort()).build());
    }
    group = RaftGroup.valueOf(RaftGroupId.valueOf(UUID.randomUUID()), peers);
    for (int i = 0; i < 3; i++) {
      startServer(i, RaftStorage.StartupOption.FORMAT);
    }
    RaftProperties clientProps = new RaftProperties();
    try (RatisBudgetStore store =
        new RatisBudgetStore(
            RaftClient.newBuilder().setRaftGroup(group).setProperties(clientProps).build())) {
      retry(() -> {
        store.open(B, "org_1", CAPACITY);
        return null;
      });

      // requestId -> settled amount (or null while only held), as confirmed to the client.
      Map<String, Long> confirmedOpen = new ConcurrentHashMap<>();
      Map<String, Long> confirmedSettled = new ConcurrentHashMap<>();

      ExecutorService pool = Executors.newFixedThreadPool(THREADS);
      List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        int thread = t;
        workers.add(
            pool.submit(() -> {
              for (int n = 0; n < REQUESTS_PER_THREAD; n++) {
                String id = "req_" + thread + "_" + n;
                ReserveResult r = retry(() -> store.reserve(id, 0, Map.of(B, HOLD)));
                if (!r.admitted()) {
                  continue;
                }
                if (n % 2 == 0) {
                  long actual = n % 3; // 0, 1 or 2 of the 10 held
                  SettleResult s = retry(() -> store.settle(id, 1, Map.of(B, actual)));
                  assertThat(s.status()).isEqualTo(SettleResult.Status.SETTLED);
                  confirmedSettled.put(id, actual);
                } else {
                  confirmedOpen.put(id, HOLD);
                }
              }
              return null;
            }));
      }

      // Kill the leader three times while the workers run, restarting it from its own disk.
      for (int kill = 0; kill < 3; kill++) {
        TimeUnit.MILLISECONDS.sleep(700);
        int leader = -1;
        for (int wait = 0; wait < 50 && leader < 0; wait++) {
          leader = leader();
          if (leader < 0) {
            TimeUnit.MILLISECONDS.sleep(100);
          }
        }
        assertThat(leader).as("a leader is elected").isNotNegative();
        servers[leader].close();
        TimeUnit.MILLISECONDS.sleep(900);
        startServer(leader, RaftStorage.StartupOption.RECOVER);
      }
      for (Future<?> w : workers) {
        w.get(3, TimeUnit.MINUTES);
      }
      pool.shutdown();

      // Every replica converges on the same applied index and the same balance.
      BudgetBalance leaderView = retry(() -> store.balance(B));
      long index = -1;
      for (int wait = 0; wait < 100; wait++) {
        long a = machine(0).lastAppliedIndex();
        long b = machine(1).lastAppliedIndex();
        long c = machine(2).lastAppliedIndex();
        if (a == b && b == c) {
          index = a;
          break;
        }
        TimeUnit.MILLISECONDS.sleep(100);
      }
      assertThat(index).as("replicas caught up").isPositive();
      for (int i = 0; i < 3; i++) {
        assertThat(machine(i).localBalance(B)).as("replica " + i).isEqualTo(leaderView);
      }

      long openHeld = confirmedOpen.values().stream().mapToLong(Long::longValue).sum();
      long settledSpend = confirmedSettled.values().stream().mapToLong(Long::longValue).sum();
      assertThat(leaderView.held()).as("no confirmed hold lost, none invented").isEqualTo(openHeld);
      assertThat(leaderView.spent()).as("settles applied exactly once").isEqualTo(settledSpend);
      assertThat(leaderView.held() + leaderView.spent())
          .as("no double spend")
          .isLessThanOrEqualTo(CAPACITY);
      assertThat(leaderView.available()).isNotNegative();
      assertThat(confirmedOpen.size() + confirmedSettled.size())
          .as("the test actually admitted work across the kills")
          .isGreaterThan(100);
    } finally {
      for (RaftServer s : servers) {
        if (s != null) {
          s.close();
        }
      }
    }
  }
}
