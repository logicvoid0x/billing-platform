package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.admission.BudgetRef;
import com.logicvoid0x.billing.admission.BudgetResolver;
import com.logicvoid0x.billing.admission.ModeCache;
import com.logicvoid0x.billing.budgetshard.grpc.BudgetShardService;
import com.logicvoid0x.billing.contracts.v1.BudgetShardGrpc;
import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import com.logicvoid0x.billing.domain.budget.InMemoryBudgetStore;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The gateway against the real shard gRPC service (in process). */
class GrpcBudgetStoreTest {
  private static final String BAL = "org_acme:balance";
  private final Clock clock = Clock.fixed(GoldenCases.HOUR.plusSeconds(60), ZoneOffset.UTC);
  private InMemoryBudgetStore shard;
  private Server server;
  private ManagedChannel channel;
  private GrpcBudgetStore store;

  @BeforeEach
  void start() throws Exception {
    shard = new InMemoryBudgetStore(1, clock);
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(new BudgetShardService(shard))
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    store = new GrpcBudgetStore(BudgetShardGrpc.newBlockingStub(channel), Duration.ofSeconds(2));
  }

  @AfterEach
  void stop() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void reserveExtendAndSettleRoundTrip() {
    shard.open(BAL, "org_acme", 1_000);
    assertThat(store.reserve("req_1", 0, Map.of(BAL, 300L)).admitted()).isTrue();
    assertThat(store.extend("req_1", 1, Map.of(BAL, 200L)).admitted()).isTrue();
    ReserveResult refused = store.reserve("req_2", 0, Map.of(BAL, 600L));
    assertThat(refused.admitted()).isFalse();
    assertThat(refused.reason()).isEqualTo(ReserveResult.Reason.INSUFFICIENT);
    assertThat(refused.refusedBudgetId()).isEqualTo(BAL);
    assertThat(store.settle("req_1", 2, Map.of(BAL, 420L)).released()).containsEntry(BAL, 80L);
    assertThat(shard.balance(BAL).spent()).isEqualTo(420);
    assertThat(store.modes("org_acme").modeByBudget()).containsKey(BAL);
  }

  @Test
  void aShardOutageSurfacesAsAnException() {
    server.shutdownNow();
    assertThatThrownBy(() -> store.reserve("req_x", 0, Map.of(BAL, 1L)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void theGatewayAdmitsStreamsAndSettlesOverGrpc() {
    shard.open(BAL, "org_acme", 1_000_000);
    Gateway gateway =
        new Gateway(
            "gw-1",
            new AdmissionController("gw-1", store, new ModeCache(), new BudgetResolver(), clock),
            store,
            t -> new CatalogRatingLibrary(GoldenCases.catalog(), "gw-test"),
            new GatewayTest.FakeUpstream(),
            new GatewayTest.RecordingEvents(),
            new MinuteLimiter(10, clock),
            clock,
            2_048);
    Gateway.Response r =
        gateway.handle(
            new GatewayRequest(
                "org_acme",
                "ws_1",
                "key_1",
                "model-x",
                "standard",
                0,
                0,
                0,
                500_000,
                List.of(new BudgetRef(BAL, BudgetKind.BALANCE, FailPolicy.closed())),
                true,
                "us-east",
                1),
            t -> {});
    assertThat(r.outcome()).isEqualTo("completed");
    assertThat(r.settledCents()).isEqualTo(600); // 500k output tokens at Acme's $12/M
    assertThat(shard.balance(BAL).spent()).isEqualTo(600);
    assertThat(shard.balance(BAL).held()).isZero();
  }
}
