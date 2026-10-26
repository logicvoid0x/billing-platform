package com.logicvoid0x.billing.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.BalanceLedgerGrpc;
import com.logicvoid0x.billing.contracts.v1.BudgetProto;
import com.logicvoid0x.billing.contracts.v1.BudgetShardGrpc;
import com.logicvoid0x.billing.contracts.v1.LedgerProto;
import com.logicvoid0x.billing.contracts.v1.RequestUsageEvent;
import com.logicvoid0x.billing.contracts.v1.ReserveRequest;
import com.logicvoid0x.billing.contracts.v1.SettleRecordEvent;
import com.logicvoid0x.billing.contracts.v1.ShardProto;
import com.logicvoid0x.billing.contracts.v1.UsageProto;
import io.grpc.MethodDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ContractsTest {
  private static final List<FileDescriptor> FILES =
      List.of(
          UsageProto.getDescriptor(),
          BudgetProto.getDescriptor(),
          ShardProto.getDescriptor(),
          LedgerProto.getDescriptor());

  private static List<FieldDescriptor> allFields() {
    List<FieldDescriptor> fields = new ArrayList<>();
    for (FileDescriptor f : FILES) {
      for (Descriptor m : f.getMessageTypes()) {
        collect(m, fields);
      }
    }
    return fields;
  }

  private static void collect(Descriptor m, List<FieldDescriptor> into) {
    into.addAll(m.getFields());
    m.getNestedTypes().forEach(n -> collect(n, into));
  }

  @Test
  void noFloatingPointAnywhere() {
    List<String> floating =
        allFields().stream()
            .filter(f ->
                f.getType() == FieldDescriptor.Type.DOUBLE
                    || f.getType() == FieldDescriptor.Type.FLOAT)
            .map(FieldDescriptor::getFullName)
            .collect(Collectors.toList());
    assertThat(floating).isEmpty();
  }

  @Test
  void everyCentsFieldIsInt64() {
    List<FieldDescriptor> cents =
        allFields().stream().filter(f -> f.getName().endsWith("_cents")).toList();
    assertThat(cents).isNotEmpty();
    assertThat(cents)
        .allSatisfy(
            f -> assertThat(f.getType()).as(f.getFullName()).isEqualTo(FieldDescriptor.Type.INT64));
  }

  @Test
  void amountMapsAreInt64() {
    for (String name :
        List.of(
            "billing.v1.SettleRecordEvent.held_by_budget",
            "billing.v1.SettleRecordEvent.settled_by_budget",
            "billing.v1.ReserveRequest.holds",
            "billing.v1.SettleRequest.actual",
            "billing.v1.RequestUsageEvent.quantities")) {
      FieldDescriptor map = field(name);
      assertThat(map.isMapField()).as(name).isTrue();
      FieldDescriptor value = map.getMessageType().findFieldByName("value");
      assertThat(value.getType()).as(name).isEqualTo(FieldDescriptor.Type.INT64);
    }
  }

  @Test
  void usageEventRoundTrips() throws Exception {
    RequestUsageEvent e =
        RequestUsageEvent.newBuilder()
            .setRequestId("req_1")
            .setOrgId("org_42")
            .setWorkspaceId("ws_7")
            .setApiKeyId("key_3")
            .setModel("model-x")
            .setServiceTier("standard")
            .setHostId("host-a")
            .setEventTime(Timestamp.newBuilder().setSeconds(1_793_000_000L))
            .putQuantities("output_tokens", 40_000_000L)
            .build();
    assertThat(RequestUsageEvent.parseFrom(e.toByteArray())).isEqualTo(e);
  }

  @Test
  void settleRecordRoundTrips() throws Exception {
    SettleRecordEvent s =
        SettleRecordEvent.newBuilder()
            .setRequestId("req_1")
            .setOrgId("org_42")
            .setHomeRegion("us-east")
            .putHeldByBudget("org_42:balance", 300L)
            .putSettledByBudget("org_42:balance", 150L)
            .setRatingLibraryVersion("rating-7")
            .setSequence(2)
            .build();
    assertThat(SettleRecordEvent.parseFrom(s.toByteArray())).isEqualTo(s);
    assertThat(
            ReserveRequest.parseFrom(
                ReserveRequest.newBuilder().putHolds("b", Long.MAX_VALUE).build().toByteArray()))
        .extracting(r -> r.getHoldsOrThrow("b"))
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void servicesExposeTheBudgetStoreAndLedgerOperations() {
    assertThat(methodNames(BudgetShardGrpc.getServiceDescriptor().getMethods()))
        .containsExactlyInAnyOrder(
            "billing.v1.BudgetShard/Reserve",
            "billing.v1.BudgetShard/Extend",
            "billing.v1.BudgetShard/Settle",
            "billing.v1.BudgetShard/GrantLease",
            "billing.v1.BudgetShard/ReportLease",
            "billing.v1.BudgetShard/Modes");
    assertThat(methodNames(BalanceLedgerGrpc.getServiceDescriptor().getMethods()))
        .containsExactlyInAnyOrder(
            "billing.v1.BalanceLedger/RequestEscrow",
            "billing.v1.BalanceLedger/ReturnEscrow",
            "billing.v1.BalanceLedger/PostUsageRollup",
            "billing.v1.BalanceLedger/GetBalance");
  }

  private static List<String> methodNames(java.util.Collection<MethodDescriptor<?, ?>> methods) {
    return methods.stream().map(MethodDescriptor::getFullMethodName).toList();
  }

  private static FieldDescriptor field(String fullName) {
    return allFields().stream()
        .filter(f -> f.getFullName().equals(fullName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no field " + fullName));
  }
}
