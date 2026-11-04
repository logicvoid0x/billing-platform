package com.logicvoid0x.billing.balanceledger;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/top-ups} (design Q10): posts a {@code TOP_UP} entry, idempotent on its business
 * key ({@code stripe:<payment_intent_id>}, {@code grant:<id>}, ...). Payments calls it from the
 * Stripe webhook and auto-recharge; it never reads this service's schema.
 *
 * <ul>
 *   <li>201 {@code {"result":"posted"}}: posted now.
 *   <li>200 {@code {"result":"duplicate"}}: the identical top-up was already posted (a retry).
 *   <li>409: the key was used for a different top-up; nothing changed.
 *   <li>400: missing fields or an amount that is not a positive number of cents.
 * </ul>
 */
@RestController
public class TopUpController {
  /**
   * One top-up.
   *
   * @param key business key, never a processing-time ID
   * @param orgId the org credited
   * @param amountCents cents, > 0
   * @param occurredAt when the payment happened
   */
  public record TopUpRequest(String key, String orgId, Long amountCents, Instant occurredAt) {}

  private final BalanceLedger ledger;

  /** Creates the controller. */
  public TopUpController(BalanceLedger ledger) {
    this.ledger = ledger;
  }

  /** Posts one top-up. */
  @PostMapping("/v1/top-ups")
  public ResponseEntity<Map<String, String>> topUp(@RequestBody TopUpRequest r)
      throws SQLException {
    if (blank(r.key()) || blank(r.orgId()) || r.amountCents() == null || r.occurredAt() == null) {
      return error(HttpStatus.BAD_REQUEST, "key, orgId, amountCents and occurredAt are required");
    }
    if (r.amountCents() <= 0) {
      return error(HttpStatus.BAD_REQUEST, "amountCents must be > 0");
    }
    BalanceLedger.Entry entry =
        new BalanceLedger.Entry(
            r.key(), EntryType.TOP_UP, r.orgId(), null, r.amountCents(), r.occurredAt());
    try {
      return ledger.post(entry)
          ? ResponseEntity.status(HttpStatus.CREATED).body(Map.of("result", "posted"))
          : ResponseEntity.ok(Map.of("result", "duplicate"));
    } catch (BalanceLedger.ConflictingEntryException e) {
      return error(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
