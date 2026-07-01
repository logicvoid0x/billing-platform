package com.logicvoid0x.billing.catalog.launch;

import com.logicvoid0x.billing.catalog.store.CatalogStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/launch-gate[?model=...][&at=...]}: 200 when every registered model (or the given
 * one) can be rated at {@code at} (default now), else 409 listing the gaps. Deploy pipelines call
 * it before routing production traffic to a model.
 */
@RestController
public class LaunchGateController {
  private final CatalogStore store;
  private final Clock clock;

  /** Creates the controller. */
  public LaunchGateController(CatalogStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Runs the gate. */
  @GetMapping("/v1/launch-gate")
  public ResponseEntity<LaunchGate.Result> check(
      @RequestParam(required = false) String model, @RequestParam(required = false) Instant at) {
    Instant when = at == null ? clock.instant() : at;
    LaunchGate.Result result =
        LaunchGate.check(store.snapshot(when), store.offerings(), when, Optional.ofNullable(model));
    return ResponseEntity.status(result.passed() ? HttpStatus.OK : HttpStatus.CONFLICT)
        .body(result);
  }
}
