package com.logicvoid0x.billing.usageconnect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.RequestUsageEvent;
import java.util.Date;
import java.util.Map;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.junit.jupiter.api.Test;

class UsageRawConverterTest {
  private final UsageRawConverter converter = new UsageRawConverter();

  @Test
  @SuppressWarnings("JavaUtilDate") // the Connect Timestamp logical type is java.util.Date
  void decodesEveryFieldWithMeterCodesAsKeys() {
    byte[] bytes =
        RequestUsageEvent.newBuilder()
            .setRequestId("req_1")
            .setOrgId("org_acme")
            .setWorkspaceId("ws_1")
            .setApiKeyId("key_1")
            .setModel("model-x")
            .setServiceTier("standard")
            .setHostId("host-a")
            .setEventTime(Timestamp.newBuilder().setSeconds(1_793_527_200L).setNanos(123_456_789))
            .putQuantities("output_tokens", 40_000_000L)
            .putQuantities("input_tokens", 1_200L)
            .build()
            .toByteArray();
    converter.configure(Map.of(), false);
    SchemaAndValue v = converter.toConnectData("usage.raw", bytes);

    assertThat(v.schema()).isEqualTo(UsageRawConverter.SCHEMA);
    Struct row = (Struct) v.value();
    row.validate();
    assertThat(row.getString("request_id")).isEqualTo("req_1");
    assertThat(row.getString("org_id")).isEqualTo("org_acme");
    assertThat(row.get("event_time")).isEqualTo(new Date(1_793_527_200_123L));
    assertThat(row.getMap("quantities"))
        .containsExactlyEntriesOf(Map.of("input_tokens", 1_200L, "output_tokens", 40_000_000L));
  }

  @Test
  void malformedBytesAreADataErrorAndKeysAreNotSupported() {
    assertThatThrownBy(() -> converter.toConnectData("usage.raw", new byte[] {(byte) 0xff, 1}))
        .isInstanceOf(DataException.class);
    assertThat(converter.toConnectData("usage.raw", null)).isEqualTo(SchemaAndValue.NULL);
    assertThatThrownBy(() -> converter.configure(Map.of(), true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
