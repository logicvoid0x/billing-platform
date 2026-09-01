package com.logicvoid0x.billing.reconciliation.completeness;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logicvoid0x.billing.contracts.v1.GatewayLogEntry;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.reconciliation.completeness.CompletenessCheck.GatewayRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code gateway.log} values into the completeness check's input for one event hour. The
 * gateway log does not carry the service tier, so the re-emitted usage is tagged {@code standard}
 * (assumption; a tier field on GatewayLogEntry would remove it).
 */
public final class GatewayLogReader {
  /** Tier recorded on re-emitted usage while the gateway log has no tier field. */
  public static final String DEFAULT_TIER = "standard";

  private GatewayLogReader() {}

  /** The records of {@code hour} among {@code values}; undecodable values are skipped. */
  public static List<GatewayRecord> forHour(Iterable<byte[]> values, Instant hour) {
    Instant start = hour.truncatedTo(ChronoUnit.HOURS);
    Instant end = start.plus(1, ChronoUnit.HOURS);
    List<GatewayRecord> out = new ArrayList<>();
    for (byte[] v : values) {
      GatewayLogEntry e;
      try {
        e = GatewayLogEntry.parseFrom(v);
      } catch (InvalidProtocolBufferException ex) {
        continue;
      }
      Instant at =
          Instant.ofEpochSecond(e.getEventTime().getSeconds(), e.getEventTime().getNanos());
      if (at.isBefore(start) || !at.isBefore(end)) {
        continue;
      }
      Map<MeterCode, Long> q = new HashMap<>();
      e.getQuantitiesMap().forEach((code, n) -> q.put(new MeterCode(code), n));
      out.add(
          new GatewayRecord(
              e.getRequestId(),
              e.getOrgId(),
              e.getWorkspaceId(),
              e.getApiKeyId(),
              e.getModel(),
              DEFAULT_TIER,
              at,
              q));
    }
    return out;
  }
}
