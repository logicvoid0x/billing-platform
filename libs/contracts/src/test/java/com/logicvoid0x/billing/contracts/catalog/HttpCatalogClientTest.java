package com.logicvoid0x.billing.contracts.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.rating.golden.GoldenCases.GoldenCase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a stand-in catalog service on a real HTTP socket. */
class HttpCatalogClientTest {
  private static final Instant HOUR = GoldenCases.HOUR;

  private HttpServer server;
  private final AtomicLong version = new AtomicLong(7);
  private final AtomicInteger snapshotCalls = new AtomicInteger();
  private final AtomicInteger status = new AtomicInteger(200);
  private final List<String> hoursAsked = new ArrayList<>();
  private final AtomicReference<Instant> now = new AtomicReference<>(HOUR);

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/v1/catalog/version", this::version);
    server.createContext("/v1/catalog/snapshot", this::snapshot);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void version(HttpExchange x) throws IOException {
    respond(x, 200, Long.toString(version.get()).getBytes(StandardCharsets.UTF_8));
  }

  private void snapshot(HttpExchange x) throws IOException {
    snapshotCalls.incrementAndGet();
    String hour = x.getRequestURI().getQuery().replace("hour=", "");
    synchronized (hoursAsked) {
      hoursAsked.add(hour);
    }
    if (status.get() != 200) {
      respond(x, status.get(), new byte[0]);
      return;
    }
    RatingCatalog golden = GoldenCases.catalog();
    RatingCatalog versioned =
        new RatingCatalog(
            "catalog-v" + version.get(),
            golden.skus(),
            golden.bandRules(),
            golden.listPrices(),
            golden.contractPrices(),
            golden.weights());
    respond(x, 200, CatalogSnapshotCodec.encode(versioned, Instant.parse(hour)).toByteArray());
  }

  private static void respond(HttpExchange x, int code, byte[] body) throws IOException {
    x.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = x.getResponseBody()) {
        out.write(body);
      }
    }
    x.close();
  }

  private HttpCatalogClient client() {
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    return new HttpCatalogClient(
        base, Duration.ofSeconds(2), Duration.ofSeconds(30), "rating-test", 16, clock);
  }

  @Test
  void ratesEveryGoldenCaseIdenticallyToTheInProcessCatalog() {
    RatingLibrary remote = client().at(HOUR.plusSeconds(1234));
    for (GoldenCase c : GoldenCases.cases()) {
      assertThat(c.chargedCents(remote)).as(c.name()).isEqualTo(c.expectedCents());
    }
  }

  @Test
  void asksForTheHourContainingTheTimeAndCachesIt() {
    HttpCatalogClient client = client();
    client.at(HOUR.plusSeconds(10));
    client.at(HOUR.plusSeconds(3599));
    client.at(HOUR.plusSeconds(3600));
    assertThat(hoursAsked).containsExactly(HOUR.toString(), HOUR.plusSeconds(3600).toString());
    assertThat(snapshotCalls).hasValue(2);
  }

  @Test
  void aNewCatalogVersionDropsCachedHoursAfterTheCheckInterval() {
    HttpCatalogClient client = client();
    assertThat(client.at(HOUR).version()).contains("catalog-v7");

    version.set(8);
    assertThat(client.at(HOUR).version()).as("within the interval").contains("catalog-v7");

    now.set(HOUR.plusSeconds(31));
    assertThat(client.at(HOUR).version()).contains("catalog-v8");
    assertThat(snapshotCalls).hasValue(2);
  }

  @Test
  void aFailedSnapshotIsReportedAndNotCached() {
    HttpCatalogClient client = client();
    status.set(503);
    assertThatThrownBy(() -> client.at(HOUR))
        .isInstanceOf(CatalogUnavailableException.class)
        .hasMessageContaining("503");
    status.set(200);
    assertThat(client.at(HOUR).version()).contains("catalog-v7");
  }

  @Test
  void anUnreachableCatalogIsReported() {
    HttpCatalogClient client = client();
    server.stop(0);
    assertThatThrownBy(() -> client.at(HOUR)).isInstanceOf(CatalogUnavailableException.class);
  }
}
