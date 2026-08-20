package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.SettleRecord;
import java.time.Instant;
import java.util.Map;

/**
 * Where the gateway publishes what it settled ({@code budget.settle}) and showed ({@code
 * gateway.log}).
 */
public interface GatewayEvents {
  /**
   * What the customer sees for one request (topic gateway.log, key request_id).
   *
   * @param outcome "completed", "budget_exhausted", "client_cancelled" or "error"
   */
  record LogEntry(
      String requestId,
      String orgId,
      String workspaceId,
      String apiKeyId,
      String model,
      Instant eventTime,
      Map<MeterCode, Long> quantities,
      long settledCents,
      String outcome) {
    /** Copies the quantities. */
    public LogEntry {
      quantities = Map.copyOf(quantities);
    }
  }

  /** Publishes a settle record (key request_id; idempotent on request_id:sequence). */
  void settled(SettleRecord record);

  /** Publishes a customer-visible usage record. */
  void logged(LogEntry entry);
}
