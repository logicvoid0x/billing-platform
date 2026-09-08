package com.logicvoid0x.billing.reconciliation.completeness;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logicvoid0x.billing.contracts.v1.RequestUsageEvent;
import com.logicvoid0x.billing.contracts.v1.UsageCorrection;
import com.logicvoid0x.billing.contracts.v1.UsageDeadLetter;
import java.util.HashSet;
import java.util.Set;

/**
 * Request IDs that are accounted for outside the archive: dead letters (unbilled, tracked) and
 * pending corrections (usage later than the allowed lateness). A dead letter whose payload cannot
 * even be parsed has no request ID; it stays on the DLQ-age alert, not in the anti-join.
 */
public final class AccountedIds {
  private AccountedIds() {}

  /** Request IDs from usage.dlq values. */
  public static Set<String> fromDeadLetters(Iterable<byte[]> values) {
    Set<String> ids = new HashSet<>();
    for (byte[] v : values) {
      try {
        String id =
            RequestUsageEvent.parseFrom(UsageDeadLetter.parseFrom(v).getPayload()).getRequestId();
        if (!id.isBlank()) {
          ids.add(id);
        }
      } catch (InvalidProtocolBufferException e) {
        // malformed: no request ID to account for
      }
    }
    return ids;
  }

  /** Request IDs from usage.corrections values. */
  public static Set<String> fromCorrections(Iterable<byte[]> values) {
    Set<String> ids = new HashSet<>();
    for (byte[] v : values) {
      try {
        String id = UsageCorrection.parseFrom(v).getRequestId();
        if (!id.isBlank()) {
          ids.add(id);
        }
      } catch (InvalidProtocolBufferException e) {
        // not a correction
      }
    }
    return ids;
  }
}
