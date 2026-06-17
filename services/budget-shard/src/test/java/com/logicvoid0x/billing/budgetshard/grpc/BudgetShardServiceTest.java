package com.logicvoid0x.billing.budgetshard.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.contracts.v1.BudgetShardGrpc;
import com.logicvoid0x.billing.contracts.v1.LeaseGrant;
import com.logicvoid0x.billing.contracts.v1.LeaseRequest;
import com.logicvoid0x.billing.contracts.v1.ModeSnapshot;
import com.logicvoid0x.billing.contracts.v1.ModesRequest;
import com.logicvoid0x.billing.contracts.v1.ReserveRequest;
import com.logicvoid0x.billing.contracts.v1.ReserveResponse;
import com.logicvoid0x.billing.contracts.v1.SettleRequest;
import com.logicvoid0x.billing.contracts.v1.SettleResponse;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetShardServiceTest {
  private static final String BALANCE = "org_42:balance";
  private static final String LIMIT = "ws_7:limit:2026-11";
  private InMemoryBudgetStore store;
  private Server server;
  private ManagedChannel channel;
  private BudgetShardGrpc.BudgetShardBlockingStub shard;

  @BeforeEach
  void setUp() throws Exception {
    store = new InMemoryBudgetStore(1);
    store.advanceEpoch(1);
    store.open(BALANCE, "org_42", 1_000);
    store.open(LIMIT, "org_42", 300);
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(new BudgetShardService(store))
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    shard = BudgetShardGrpc.newBlockingStub(channel);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  private static ReserveRequest reserve(String id, int seq, long balance, long limit) {
    return ReserveRequest.newBuilder()
        .setRequestId(id)
        .setSequence(seq)
        .setOrgId("org_42")
        .putHolds(BALANCE, balance)
        .putHolds(LIMIT, limit)
        .build();
  }

  @Test
  void reserveIsAllOrNothingOverGrpc() {
    ReserveResponse refused = shard.reserve(reserve("req_1", 0, 100, 301));
    assertThat(refused.getAdmitted()).isFalse();
    assertThat(refused.getReason()).isEqualTo("insufficient");
    assertThat(refused.getRefusedBudgetId()).isEqualTo(LIMIT);
    assertThat(store.balance(BALANCE).held()).isZero();

    ReserveResponse ok = shard.reserve(reserve("req_2", 0, 100, 100));
    assertThat(ok.getAdmitted()).isTrue();
    assertThat(ok.getReason()).isEmpty();
  }

  @Test
  void retriedRpcsAreIdempotent() {
    shard.reserve(reserve("req_1", 0, 200, 100));
    shard.reserve(reserve("req_1", 0, 200, 100));
    assertThat(store.balance(BALANCE).held()).isEqualTo(200);

    SettleRequest settle =
        SettleRequest.newBuilder()
            .setRequestId("req_1")
            .setSequence(1)
            .putActual(BALANCE, 150)
            .putActual(LIMIT, 100)
            .build();
    SettleResponse first = shard.settle(settle);
    SettleResponse retry = shard.settle(settle);
    assertThat(retry).isEqualTo(first);
    assertThat(first.getSettled()).isTrue();
    assertThat(first.getReleasedMap()).containsEntry(BALANCE, 50L);
    assertThat(store.balance(BALANCE).spent()).isEqualTo(150);
  }

  @Test
  void extendAddsTheNextChunk() {
    shard.reserve(reserve("req_1", 0, 100, 100));
    assertThat(shard.extend(reserve("req_1", 1, 100, 100)).getAdmitted()).isTrue();
    assertThat(store.balance(LIMIT).held()).isEqualTo(200);
    assertThat(shard.extend(reserve("req_1", 2, 100, 101)).getAdmitted()).isFalse();
    assertThat(store.balance(LIMIT).held()).isEqualTo(200);
  }

  @Test
  void leasesCarryEpochAndTtlAndStaleEpochsGetNothing() {
    LeaseGrant g =
        shard.grantLease(
            LeaseRequest.newBuilder()
                .setHostId("host-a")
                .setBudgetId(BALANCE)
                .setRequested(250)
                .setEpoch(1)
                .build());
    assertThat(g.getGranted()).isEqualTo(250);
    assertThat(g.getEpoch()).isEqualTo(1);
    assertThat(g.getTtlMillis()).isEqualTo(BudgetShardService.LEASE_TTL_MILLIS);
    assertThat(g.getMode()).isEqualTo("lease");

    store.advanceEpoch(2);
    LeaseGrant stale =
        shard.grantLease(
            LeaseRequest.newBuilder()
                .setHostId("host-a")
                .setBudgetId(BALANCE)
                .setRequested(250)
                .setEpoch(1)
                .build());
    assertThat(stale.getGranted()).isZero();
    assertThat(stale.getEpoch()).isEqualTo(2);
    assertThat(stale.getTtlMillis()).isZero();
  }

  @Test
  void modesUseCodes() {
    shard.reserve(reserve("req_1", 0, 0, 300));
    ModeSnapshot m = shard.modes(ModesRequest.newBuilder().setOrgId("org_42").build());
    assertThat(m.getModeByBudgetMap())
        .containsEntry(LIMIT, "deny")
        .containsEntry(BALANCE, "direct");
  }

  @Test
  void rejectionsBecomeStatusErrors() {
    shard.reserve(reserve("req_1", 0, 10, 10));
    assertThatThrownBy(() -> shard.reserve(reserve("req_1", 0, 999, 10)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    assertThatThrownBy(() -> shard.reserve(reserve("req_1", 7, 1, 1)))
        .as("a second reservation for the same request")
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
  }
}
