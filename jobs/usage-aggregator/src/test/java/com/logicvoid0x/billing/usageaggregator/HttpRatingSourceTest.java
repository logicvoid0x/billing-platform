package com.logicvoid0x.billing.usageaggregator;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.catalog.CatalogSnapshotCodec;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.rating.golden.GoldenCases.GoldenCase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The job's catalog source survives Flink's serialization and rates from the snapshot API. */
class HttpRatingSourceTest {
  @Test
  void aShippedSourceRatesTheGoldenCasesFromTheCatalogService() throws Exception {
    HttpServer catalog =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    catalog.createContext(
        "/v1/catalog/version", x -> respond(x, "5".getBytes(StandardCharsets.UTF_8)));
    catalog.createContext(
        "/v1/catalog/snapshot",
        x -> {
          Instant hour = Instant.parse(x.getRequestURI().getQuery().replace("hour=", ""));
          respond(
              x,
              CatalogSnapshotCodec.encode(GoldenCases.catalog(), hour).toBuilder()
                  .setVersion("catalog-v5")
                  .build()
                  .toByteArray());
        });
    catalog.start();
    try {
      String url =
          "http://" + catalog.getAddress().getHostString() + ":" + catalog.getAddress().getPort();
      RatingSource shipped = roundTrip(new HttpRatingSource(url, "rating-1"));
      shipped.open();
      for (GoldenCase c : GoldenCases.cases()) {
        assertThat(c.chargedCents(shipped.at(GoldenCases.HOUR)))
            .as(c.name())
            .isEqualTo(c.expectedCents());
      }
      assertThat(shipped.at(GoldenCases.HOUR).version()).isEqualTo("rating-1/catalog-v5");
    } finally {
      catalog.stop(0);
    }
  }

  private static RatingSource roundTrip(RatingSource source) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(source);
    }
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (RatingSource) in.readObject();
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
