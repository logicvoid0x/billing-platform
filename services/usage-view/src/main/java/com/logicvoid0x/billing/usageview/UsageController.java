package com.logicvoid0x.billing.usageview;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/usage?org=&from=&to=}: usage by workspace, API key, model and hour for hours in
 * {@code [from, to)} (hour-aligned down), at most 31 days. Open hours are included and refresh
 * within about 40 s (20 s early firing plus a 20 s checkpoint).
 */
@RestController
public class UsageController {
  /** Longest range one call may read. */
  public static final Duration MAX_RANGE = Duration.ofDays(31);

  private final UsageByKey usage;

  /** Creates the controller. */
  public UsageController(UsageByKey usage) {
    this.usage = usage;
  }

  /** Usage rows of one org. */
  @GetMapping("/v1/usage")
  public ResponseEntity<List<UsageByKey.Row>> usage(
      @RequestParam String org, @RequestParam Instant from, @RequestParam Instant to)
      throws SQLException {
    Instant start = from.truncatedTo(ChronoUnit.HOURS);
    Instant end = to.truncatedTo(ChronoUnit.HOURS);
    if (org.isBlank()
        || !end.isAfter(start)
        || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(usage.rows(org, start, end));
  }
}
