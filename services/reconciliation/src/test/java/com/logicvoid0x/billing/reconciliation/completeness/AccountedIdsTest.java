package com.logicvoid0x.billing.reconciliation.completeness;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.logicvoid0x.billing.contracts.v1.RequestUsageEvent;
import com.logicvoid0x.billing.contracts.v1.UsageCorrection;
import com.logicvoid0x.billing.contracts.v1.UsageDeadLetter;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountedIdsTest {
  @Test
  void deadLettersAndCorrectionsYieldTheirRequestIds() {
    byte[] dlq =
        UsageDeadLetter.newBuilder()
            .setPayload(
                RequestUsageEvent.newBuilder().setRequestId("req_dlq").build().toByteString())
            .setReason("unknown_sku: model-new")
            .build()
            .toByteArray();
    byte[] garbled =
        UsageDeadLetter.newBuilder()
            .setPayload(ByteString.copyFrom(new byte[] {(byte) 0xff, 1}))
            .build()
            .toByteArray();
    assertThat(AccountedIds.fromDeadLetters(List.of(dlq, garbled))).containsExactly("req_dlq");

    byte[] late =
        UsageCorrection.newBuilder().setRequestId("req_late").setQuantity(5).build().toByteArray();
    assertThat(AccountedIds.fromCorrections(List.of(late, new byte[] {(byte) 0xff})))
        .containsExactly("req_late");
  }
}
