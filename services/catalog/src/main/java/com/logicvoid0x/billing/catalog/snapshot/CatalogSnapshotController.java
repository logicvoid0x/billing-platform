package com.logicvoid0x.billing.catalog.snapshot;

import com.logicvoid0x.billing.catalog.store.CatalogStore;
import com.logicvoid0x.billing.contracts.catalog.CatalogSnapshotCodec;
import com.logicvoid0x.billing.contracts.catalog.HttpCatalogClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog reads for services outside the catalog (open question Q7), which must not read the {@code
 * catalog} schema directly:
 *
 * <ul>
 *   <li>{@code GET /v1/catalog/snapshot?hour=} - every row in effect during the hour that contains
 *       {@code hour}, with the catalog version, as a {@code billing.v1.CatalogSnapshot} protobuf.
 *   <li>{@code GET /v1/catalog/version} - the current catalog version number, as text. Clients poll
 *       it to drop cached hours after a catalog write.
 * </ul>
 */
@RestController
public class CatalogSnapshotController {
  private final CatalogStore store;

  /** Creates the controller. */
  public CatalogSnapshotController(CatalogStore store) {
    this.store = store;
  }

  /** The snapshot of one hour. */
  @GetMapping(path = "/v1/catalog/snapshot", produces = HttpCatalogClient.PROTOBUF)
  public ResponseEntity<byte[]> snapshot(@RequestParam Instant hour) {
    Instant start = hour.truncatedTo(ChronoUnit.HOURS);
    byte[] body = CatalogSnapshotCodec.encode(store.snapshot(start), start).toByteArray();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(HttpCatalogClient.PROTOBUF))
        .body(body);
  }

  /** The current catalog version number. */
  @GetMapping(path = "/v1/catalog/version", produces = MediaType.TEXT_PLAIN_VALUE)
  public String version() {
    return Long.toString(store.version());
  }
}
