package com.logicvoid0x.billing.gateway;

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
import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.domain.budget.LeaseGrant;
import com.logicvoid0x.billing.domain.budget.LeaseReport;
import com.logicvoid0x.billing.domain.budget.ModeSnapshot;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import com.logicvoid0x.billing.domain.budget.SettleResult;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The regional budget shard over gRPC (services:budget-shard {@code BudgetShardService}). Every
 * call has a deadline. A failed call surfaces as a runtime exception, which admission and the
 * stream guard treat as "shard unavailable" (fail policy, stop reason).
 */
public final class GrpcBudgetStore implements BudgetStore {
  private final BudgetShardGrpc.BudgetShardBlockingStub stub;
  private final Duration deadline;

  /** Creates the client over a stub. */
  public GrpcBudgetStore(BudgetShardGrpc.BudgetShardBlockingStub stub, Duration deadline) {
    this.stub = Objects.requireNonNull(stub, "stub");
    this.deadline = Objects.requireNonNull(deadline, "deadline");
  }

  private BudgetShardGrpc.BudgetShardBlockingStub call() {
    return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public ReserveResult reserve(String requestId, int sequence, Map<String, Long> holds) {
    return fromProto(call().reserve(reserveRequest(requestId, sequence, holds)));
  }

  @Override
  public ReserveResult extend(String requestId, int sequence, Map<String, Long> holds) {
    return fromProto(call().extend(reserveRequest(requestId, sequence, holds)));
  }

  @Override
  public SettleResult settle(String requestId, int sequence, Map<String, Long> actual) {
    SettleResponse r =
        call()
            .settle(
                SettleRequest.newBuilder()
                    .setRequestId(requestId)
                    .setSequence(sequence)
                    .putAllActual(actual)
                    .build());
    // The wire form carries only settled-or-not; not settled means a replay of an earlier settle.
    return new SettleResult(
        r.getSettled() ? SettleResult.Status.SETTLED : SettleResult.Status.ALREADY_SETTLED,
        r.getReleasedMap());
  }

  @Override
  public LeaseGrant grantLease(String hostId, String budgetId, long requested, long epoch) {
    com.logicvoid0x.billing.contracts.v1.LeaseGrant g =
        call()
            .grantLease(
                LeaseRequest.newBuilder()
                    .setHostId(hostId)
                    .setBudgetId(budgetId)
                    .setRequested(requested)
                    .setEpoch(epoch)
                    .build());
    return new LeaseGrant(g.getGranted(), g.getEpoch(), g.getTtlMillis() == 0, g.getLeaseId());
  }

  @Override
  public LeaseReport reportLease(
      String hostId, String budgetId, long leaseId, long spent, long unused) {
    LeaseReportResponse r =
        call()
            .reportLease(
                LeaseReportRequest.newBuilder()
                    .setHostId(hostId)
                    .setBudgetId(budgetId)
                    .setLeaseId(leaseId)
                    .setSpent(spent)
                    .setUnused(unused)
                    .build());
    return new LeaseReport(LeaseReport.Status.fromCode(r.getStatus()), r.getReleased());
  }

  @Override
  public ModeSnapshot modes(String orgId) {
    com.logicvoid0x.billing.contracts.v1.ModeSnapshot m =
        call().modes(ModesRequest.newBuilder().setOrgId(orgId).build());
    Map<String, Mode> modes = new HashMap<>();
    m.getModeByBudgetMap().forEach((budget, code) -> modes.put(budget, Mode.fromCode(code)));
    return new ModeSnapshot(m.getOrgId(), modes, m.getEpoch());
  }

  private static ReserveRequest reserveRequest(
      String requestId, int sequence, Map<String, Long> holds) {
    return ReserveRequest.newBuilder()
        .setRequestId(requestId)
        .setSequence(sequence)
        .putAllHolds(holds)
        .build();
  }

  static ReserveResult fromProto(ReserveResponse r) {
    return r.getAdmitted()
        ? new ReserveResult(true, ReserveResult.Reason.NONE, "")
        : new ReserveResult(
            false, ReserveResult.Reason.fromCode(r.getReason()), r.getRefusedBudgetId());
  }
}
