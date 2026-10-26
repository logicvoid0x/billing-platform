package com.logicvoid0x.billing.budgetshard.grpc;

import com.logicvoid0x.billing.contracts.v1.BudgetShardGrpc;
import com.logicvoid0x.billing.contracts.v1.LeaseReportRequest;
import com.logicvoid0x.billing.contracts.v1.LeaseReportResponse;
import com.logicvoid0x.billing.contracts.v1.LeaseRequest;
import com.logicvoid0x.billing.contracts.v1.ModesRequest;
import com.logicvoid0x.billing.contracts.v1.ReserveRequest;
import com.logicvoid0x.billing.contracts.v1.ReserveResponse;
import com.logicvoid0x.billing.contracts.v1.SettleRequest;
import com.logicvoid0x.billing.contracts.v1.SettleResponse;
import com.logicvoid0x.billing.domain.Mode;
import com.logicvoid0x.billing.domain.budget.BudgetState;
import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.domain.budget.LeaseGrant;
import com.logicvoid0x.billing.domain.budget.LeaseReport;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * gRPC face of the regional budget shard ({@code billing.v1.BudgetShard}) over a {@link
 * BudgetStore}: Ratis in production, in-memory in tests. Idempotency lives in the store (keyed by
 * request ID and sequence), so a retried RPC returns the first answer.
 */
public final class BudgetShardService extends BudgetShardGrpc.BudgetShardImplBase {
  /** Gateway lease time-to-live from the design (about 2 s of host burn, 2 s TTL). */
  public static final long LEASE_TTL_MILLIS = BudgetState.LEASE_TTL_MILLIS;

  private final BudgetStore store;

  /** Serves {@code store}. */
  public BudgetShardService(BudgetStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public void reserve(ReserveRequest req, StreamObserver<ReserveResponse> out) {
    respond(
        out,
        () -> toProto(store.reserve(req.getRequestId(), req.getSequence(), req.getHoldsMap())));
  }

  @Override
  public void extend(ReserveRequest req, StreamObserver<ReserveResponse> out) {
    respond(
        out, () -> toProto(store.extend(req.getRequestId(), req.getSequence(), req.getHoldsMap())));
  }

  @Override
  public void settle(SettleRequest req, StreamObserver<SettleResponse> out) {
    respond(
        out,
        () -> {
          SettleResult r = store.settle(req.getRequestId(), req.getSequence(), req.getActualMap());
          return SettleResponse.newBuilder()
              .setSettled(r.status() == SettleResult.Status.SETTLED)
              .putAllReleased(r.released())
              .build();
        });
  }

  @Override
  public void grantLease(
      LeaseRequest req, StreamObserver<com.logicvoid0x.billing.contracts.v1.LeaseGrant> out) {
    respond(
        out,
        () -> {
          LeaseGrant g =
              store.grantLease(
                  req.getHostId(), req.getBudgetId(), req.getRequested(), req.getEpoch());
          return com.logicvoid0x.billing.contracts.v1.LeaseGrant.newBuilder()
              .setGranted(g.granted())
              .setEpoch(g.epoch())
              .setTtlMillis(g.staleEpoch() ? 0 : LEASE_TTL_MILLIS)
              .setMode(g.granted() > 0 ? Mode.LEASE.code() : Mode.DIRECT.code())
              .setLeaseId(g.leaseId())
              .build();
        });
  }

  @Override
  public void reportLease(LeaseReportRequest req, StreamObserver<LeaseReportResponse> out) {
    respond(
        out,
        () -> {
          LeaseReport r =
              store.reportLease(
                  req.getHostId(),
                  req.getBudgetId(),
                  req.getLeaseId(),
                  req.getSpent(),
                  req.getUnused());
          return LeaseReportResponse.newBuilder()
              .setStatus(r.status().code())
              .setReleased(r.released())
              .build();
        });
  }

  @Override
  public void modes(
      ModesRequest req, StreamObserver<com.logicvoid0x.billing.contracts.v1.ModeSnapshot> out) {
    respond(
        out,
        () -> {
          ModeSnapshot m = store.modes(req.getOrgId());
          com.logicvoid0x.billing.contracts.v1.ModeSnapshot.Builder b =
              com.logicvoid0x.billing.contracts.v1.ModeSnapshot.newBuilder()
                  .setOrgId(m.orgId())
                  .setEpoch(m.epoch());
          m.modeByBudget().forEach((budget, mode) -> b.putModeByBudget(budget, mode.code()));
          return b.build();
        });
  }

  static ReserveResponse toProto(ReserveResult r) {
    return ReserveResponse.newBuilder()
        .setAdmitted(r.admitted())
        .setReason(r.admitted() ? "" : r.reason().code())
        .setRefusedBudgetId(r.refusedBudgetId())
        .build();
  }

  /** Runs {@code call}; store rejections become gRPC status errors, never a half answer. */
  private static <T> void respond(StreamObserver<T> out, Supplier<T> call) {
    T value;
    try {
      value = call.get();
    } catch (IllegalArgumentException e) {
      out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
      return;
    } catch (IllegalStateException e) {
      out.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
      return;
    }
    out.onNext(value);
    out.onCompleted();
  }
}
