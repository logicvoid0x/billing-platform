package com.logicvoid0x.billing.inferencesim.emitter;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.logicvoid0x.billing.contracts.v1.RequestUsageEvent;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link RequestUsage} to and from the {@code usage.raw} wire format ({@code
 * billing.v1.RequestUsageEvent}). Meters travel as their permanent codes, never enum names.
 */
public final class UsageEventCodec {
  private UsageEventCodec() {}

  /** The protobuf message for {@code usage}. */
  public static RequestUsageEvent toProto(RequestUsage usage) {
    RequestUsageEvent.Builder b =
        RequestUsageEvent.newBuilder()
            .setRequestId(usage.requestId())
            .setOrgId(usage.orgId())
            .setWorkspaceId(usage.workspaceId())
            .setApiKeyId(usage.apiKeyId())
            .setModel(usage.model())
            .setServiceTier(usage.serviceTier())
            .setHostId(usage.hostId())
            .setEventTime(timestamp(usage.eventTime()));
    usage.quantities().forEach((meter, quantity) -> b.putQuantities(meter.code(), quantity));
    return b.build();
  }

  /** The serialized event. */
  public static byte[] encode(RequestUsage usage) {
    return toProto(usage).toByteArray();
  }

  /** Parses and validates a serialized event. */
  public static RequestUsage decode(byte[] bytes) {
    try {
      return fromProto(RequestUsageEvent.parseFrom(bytes));
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("not a RequestUsageEvent", e);
    }
  }

  /** The domain event for a protobuf message; throws if it is invalid. */
  public static RequestUsage fromProto(RequestUsageEvent e) {
    Map<MeterCode, Long> quantities = new LinkedHashMap<>();
    e.getQuantitiesMap().forEach((code, quantity) -> quantities.put(new MeterCode(code), quantity));
    return new RequestUsage(
        e.getRequestId(),
        e.getOrgId(),
        e.getWorkspaceId(),
        e.getApiKeyId(),
        e.getModel(),
        e.getServiceTier(),
        e.getHostId(),
        Instant.ofEpochSecond(e.getEventTime().getSeconds(), e.getEventTime().getNanos()),
        quantities);
  }

  private static Timestamp timestamp(Instant t) {
    return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
  }
}
