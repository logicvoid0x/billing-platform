package com.logicvoid0x.billing.reconciliation.completeness;

import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.domain.RequestUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The completeness loop's core (design: "Narrow by org, anti-join on requestId, re-emit from the
 * gateway's record with the original ID"). For one sealed hour, every request the gateway logged
 * with usage must appear on the metering side: in the raw archive, or as dead-lettered or pending
 * corrections, which count as accounted for and are tracked as unbilled. A logged request with none
 * of these is missing, and is re-emitted from the gateway's record under its original request ID,
 * so stream dedupe absorbs it if the original turns up after all.
 */
public final class CompletenessCheck {
  /**
   * A gateway-log entry, as needed here.
   *
   * @param quantities what the customer was shown, by meter
   */
  public record GatewayRecord(
      String requestId,
      String orgId,
      String workspaceId,
      String apiKeyId,
      String model,
      String serviceTier,
      Instant eventTime,
      Map<MeterCode, Long> quantities) {
    /** Copies the quantities. */
    public GatewayRecord {
      quantities = Map.copyOf(quantities);
    }

    boolean hasUsage() {
      return quantities.values().stream().anyMatch(q -> q > 0);
    }
  }

  /**
   * The outcome for one hour.
   *
   * @param checked gateway records with usage
   * @param reemitted usage to send again with the original request IDs
   */
  public record Result(int checked, List<RequestUsage> reemitted) {
    /** Copies the list. */
    public Result {
      reemitted = List.copyOf(reemitted);
    }

    /** Requests missing on the metering side (the completeness drift). */
    public int drift() {
      return reemitted.size();
    }
  }

  /** The host ID recorded on re-emitted usage. */
  public static final String REEMIT_HOST = "reconciliation-reemit";

  private CompletenessCheck() {}

  /**
   * Checks one hour.
   *
   * @param gateway the hour's gateway-log records
   * @param archived request IDs in the raw archive for the hour
   * @param accountedElsewhere request IDs dead-lettered or pending as corrections
   */
  public static Result check(
      Collection<GatewayRecord> gateway, Set<String> archived, Set<String> accountedElsewhere) {
    Objects.requireNonNull(archived, "archived");
    Objects.requireNonNull(accountedElsewhere, "accountedElsewhere");
    Set<String> seen = new HashSet<>();
    List<RequestUsage> missing = new ArrayList<>();
    int checked = 0;
    for (GatewayRecord g : gateway) {
      if (!g.hasUsage() || !seen.add(g.requestId())) {
        continue; // refused, empty, or a duplicate gateway-log delivery
      }
      checked++;
      if (archived.contains(g.requestId()) || accountedElsewhere.contains(g.requestId())) {
        continue;
      }
      missing.add(
          new RequestUsage(
              g.requestId(), // the original ID: dedupe on requestId:meterCode stays exact
              g.orgId(),
              g.workspaceId(),
              g.apiKeyId(),
              g.model(),
              g.serviceTier(),
              REEMIT_HOST,
              g.eventTime(),
              g.quantities()));
    }
    return new Result(checked, missing);
  }
}
