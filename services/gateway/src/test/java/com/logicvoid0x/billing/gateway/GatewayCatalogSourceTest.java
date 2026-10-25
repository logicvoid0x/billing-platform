package com.logicvoid0x.billing.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.catalog.CatalogSnapshotCodec;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.rating.golden.GoldenCases.GoldenCase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** With gateway.enabled the rating library comes from the catalog's snapshot API (Q7). */
class GatewayCatalogSourceTest {
  @Test
  void theGatewayRatesFromTheCatalogService() throws IOException {
    HttpServer catalog =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    catalog.createContext(
        "/v1/catalog/version", x -> respond(x, "3".getBytes(StandardCharsets.UTF_8)));
    catalog.createContext(
        "/v1/catalog/snapshot",
        x -> {
          Instant hour = Instant.parse(x.getRequestURI().getQuery().replace("hour=", ""));
          respond(
              x,
              CatalogSnapshotCodec.encode(GoldenCases.catalog(), hour).toBuilder()
                  .setVersion("catalog-v3")
                  .build()
                  .toByteArray());
        });
    catalog.start();
    try {
      GatewaySettings settings =
          new GatewaySettings(
              true,
              "gw-test",
              "local",
              "localhost:1",
              "http://localhost:1",
              "localhost:1",
              600,
              2048,
              "http://"
                  + catalog.getAddress().getHostString()
                  + ":"
                  + catalog.getAddress().getPort());
      Function<Instant, RatingLibrary> ratings =
          new GatewayConfiguration().gatewayRatings(settings);
      RatingLibrary library = ratings.apply(GoldenCases.HOUR);
      for (GoldenCase c : GoldenCases.cases()) {
        assertThat(c.chargedCents(library)).as(c.name()).isEqualTo(c.expectedCents());
      }
    } finally {
      catalog.stop(0);
    }
  }

  private static void respond(HttpExchange x, byte[] body) throws IOException {
    x.sendResponseHeaders(200, body.length);
    try (OutputStream out = x.getResponseBody()) {
      out.write(body);
    }
    x.close();
  }
}
