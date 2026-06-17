package com.logicvoid0x.billing.budgetshard.ratis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** One real Ratis server over gRPC on localhost: commands go through the Raft log. */
class RatisShardTest {
  private static final String BALANCE = "org_42:balance";

  @TempDir Path dir;

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  private record Node(RaftServer server, RaftGroup group, RaftProperties props) {
    RatisBudgetStore client() {
      return new RatisBudgetStore(
          RaftClient.newBuilder().setRaftGroup(group).setProperties(props).build());
    }
  }

  private Node start(RaftGroup group, RaftPeer peer, RaftStorage.StartupOption option)
      throws IOException {
    RaftProperties props = new RaftProperties();
    RaftServerConfigKeys.setStorageDir(props, List.of(dir.resolve("storage").toFile()));
    String address = peer.getAddress();
    GrpcConfigKeys.Server.setPort(
        props, Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
    RaftServerConfigKeys.Rpc.setTimeoutMin(
        props, TimeDuration.valueOf(200, java.util.concurrent.TimeUnit.MILLISECONDS));
    RaftServerConfigKeys.Rpc.setTimeoutMax(
        props, TimeDuration.valueOf(400, java.util.concurrent.TimeUnit.MILLISECONDS));
    RaftServer server =
        RaftServer.newBuilder()
            .setGroup(group)
            .setServerId(peer.getId())
            .setProperties(props)
            .setStateMachine(new BudgetStateMachine(1))
            .setOption(option)
            .build();
    server.start();
    return new Node(server, group, props);
  }

  @Test
  void commandsApplyThroughTheLogAndSurviveSnapshotAndRestart() throws Exception {
    RaftPeer peer = RaftPeer.newBuilder().setId("s0").setAddress("localhost:" + freePort()).build();
    RaftGroup group = RaftGroup.valueOf(RaftGroupId.valueOf(UUID.randomUUID()), peer);

    Node node = start(group, peer, RaftStorage.StartupOption.FORMAT);
    try (RatisBudgetStore store = node.client()) {
      store.advanceEpoch(1);
      store.open(BALANCE, "org_42", 1_000);
      assertThat(store.reserve("req_1", 0, Map.of(BALANCE, 300L)).admitted()).isTrue();
      assertThat(store.reserve("req_2", 0, Map.of(BALANCE, 800L)).admitted()).isFalse();
      assertThat(store.settle("req_1", 1, Map.of(BALANCE, 120L)).released())
          .containsEntry(BALANCE, 180L);
      assertThat(store.grantLease("host-a", BALANCE, 100, 1).granted()).isEqualTo(100);
      assertThat(store.balance(BALANCE).available()).isEqualTo(1_000 - 120 - 100);
      // 120 cents settled in the last minute = 2 cents/s; 780 available = 390 s of runway.
      assertThat(store.modes("org_42").modeByBudget()).containsEntry(BALANCE, Mode.LEASE);
      assertThatThrownBy(() -> store.reserve("req_1", 0, Map.of(BALANCE, 1L)))
          .as("a reused key with another payload is rejected deterministically")
          .isInstanceOf(IllegalArgumentException.class);

      // Snapshot, then keep writing after it so restart replays snapshot + log tail.
      long snapshotIndex =
          node.server().getDivision(group.getGroupId()).getStateMachine().takeSnapshot();
      assertThat(snapshotIndex).isPositive();
      assertThat(store.reserve("req_3", 0, Map.of(BALANCE, 50L)).admitted()).isTrue();
    } finally {
      node.server().close();
    }

    Node restarted = start(group, peer, RaftStorage.StartupOption.RECOVER);
    try (RatisBudgetStore store = restarted.client()) {
      assertThat(store.balance(BALANCE).spent()).isEqualTo(120);
      assertThat(store.balance(BALANCE).held()).isEqualTo(50);
      assertThat(store.balance(BALANCE).leased()).isEqualTo(100);
      // Replays after restart are recognized and change nothing.
      assertThat(store.settle("req_1", 1, Map.of(BALANCE, 120L)).status())
          .isEqualTo(SettleResult.Status.SETTLED);
      assertThat(store.reserve("req_3", 0, Map.of(BALANCE, 50L)).admitted()).isTrue();
      assertThat(store.balance(BALANCE).held()).isEqualTo(50);
      assertThat(store.grantLease("host-a", BALANCE, 10, 0).staleEpoch()).isTrue();
    } finally {
      restarted.server().close();
    }
  }
}
