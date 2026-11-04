package com.logicvoid0x.billing.usageview;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.contracts.catalog.CatalogSnapshotCodec;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.usageaggregator.HourlyUsage;
import com.logicvoid0x.billing.usageaggregator.sink.HourlyBatch;
import com.logicvoid0x.billing.usageaggregator.sink.Jdbc;
import com.logicvoid0x.billing.usageaggregator.sink.MeteringMigrations;
import com.logicvoid0x.billing.usageaggregator.sink.UsageHourlySink;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The usage API matches usage_hourly exactly. Usage is written by the aggregator's own checkpointed
 * sink; the catalog is a stand-in serving the golden catalog.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UsageByKeyHttpTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant H11 = Instant.parse("2026-11-01T11:00:00Z");
  private static HttpServer catalog;

  @BeforeAll
  static void startCatalog() throws IOException {
    catalog = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    catalog.createContext(
        "/v1/catalog/version", x -> respond(x, "1".getBytes(StandardCharsets.UTF_8)));
    catalog.createContext(
        "/v1/catalog/snapshot",
        x -> {
          Instant hour = Instant.parse(x.getRequestURI().getQuery().replace("hour=", ""));
          respond(
              x,
              CatalogSnapshotCodec.encode(GoldenCases.catalog(), hour).toBuilder()
                  .setVersion("catalog-v1")
                  .build()
                  .toByteArray());
        });
    catalog.start();
  }

  @AfterAll
  static void stopCatalog() {
    catalog.stop(0);
  }

  private static void respond(HttpExchange x, byte[] body) throws IOException {
    x.sendResponseHeaders(200, body.length);
    try (OutputStream out = x.getResponseBody()) {
      out.write(body);
    }
    x.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add(
        "usage-view.catalog-url",
        () ->
            "http://"
                + catalog.getAddress().getHostString()
                + ":"
                + catalog.getAddress().getPort());
  }

  @LocalServerPort int port;

  private static Jdbc jdbc() {
    return new Jdbc(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
  }

  private JsonNode get(String query) throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/usage?" + query))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JsonMapper.builder().build().readTree(r.body());
  }

  @Test
  void theApiMatchesUsageHourlyExactly() throws Exception {
    Jdbc jdbc = jdbc();
    MeteringMigrations.migrate(jdbc);
    UsageHourlySink.apply(
        jdbc,
        new HourlyBatch(
            "test:j1:0:1",
            List.of(
                new HourlyUsage("org_acme", "ws_a", "key_1", "sku_out", H10, 1_000, 2),
                new HourlyUsage("org_acme", "ws_a", "key_2", "sku_out", H10, 500, 1),
                new HourlyUsage("org_acme", "ws_b", "key_3", "sku_tiered", H10, 70, 1),
                new HourlyUsage("org_acme", "ws_b", "key_3", "sku_out", H11, 9, 1),
                new HourlyUsage("org_acme", "ws_b", "key_3", "sku_gone", H11, 4, 1),
                new HourlyUsage("org_other", "ws_x", "key_9", "sku_out", H10, 77, 1))));
    UsageHourlySink.apply(
        jdbc,
        new HourlyBatch(
            "test:j1:0:2",
            List.of(new HourlyUsage("org_acme", "ws_a", "key_1", "sku_out", H10, 25, 1))));

    JsonNode rows = get("org=org_acme&from=2026-11-01T10:00:00Z&to=2026-11-01T12:00:00Z");

    assertThat(rows.size()).isEqualTo(5);
    JsonNode first = rows.get(0);
    assertThat(first.get("workspaceId").asString()).isEqualTo("ws_a");
    assertThat(first.get("apiKeyId").asString()).isEqualTo("key_1");
    assertThat(first.get("model").asString()).isEqualTo("model-x");
    assertThat(first.get("quantity").asLong()).isEqualTo(1_025);
    assertThat(first.get("lines").asLong()).isEqualTo(3);
    assertThat(rows.get(2).get("model").asString()).isEqualTo("model-t");
    assertThat(rows.get(3).get("model").asString())
        .as("SKU unknown to the catalog")
        .isEqualTo(UsageByKey.UNKNOWN_MODEL);

    // Summed over workspace and key, the API equals usage_hourly row for row.
    Map<String, Long> fromApi = new TreeMap<>();
    for (JsonNode r : rows) {
      fromApi.merge(
          r.get("skuId").asString() + "|" + Instant.parse(r.get("hour").asString()),
          r.get("quantity").asLong(),
          Long::sum);
    }
    Map<String, Long> usageHourly = new TreeMap<>();
    try (Connection c = jdbc.connect();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT sku_id, event_hour, quantity FROM metering.usage_hourly"
                    + " WHERE org_id = 'org_acme'")) {
      while (rs.next()) {
        usageHourly.put(rs.getString(1) + "|" + rs.getTimestamp(2).toInstant(), rs.getLong(3));
      }
    }
    assertThat(fromApi).isEqualTo(usageHourly);
  }

  @Test
  void badRangesAreRejected() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    for (String q :
        List.of(
            "org=org_acme&from=2026-11-01T12:00:00Z&to=2026-11-01T10:00:00Z",
            "org=org_acme&from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z")) {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/usage?" + q))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).as(q).isEqualTo(400);
    }
  }
}
