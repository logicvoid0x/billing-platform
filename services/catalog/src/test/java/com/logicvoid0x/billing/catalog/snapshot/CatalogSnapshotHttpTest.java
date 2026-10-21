package com.logicvoid0x.billing.catalog.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.catalog.store.CatalogStore;
import com.logicvoid0x.billing.catalogclient.HttpCatalogClient;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import com.logicvoid0x.billing.rating.golden.GoldenCases.GoldenCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The snapshot API on the running service against real Postgres, read through the client that other
 * services use (Q7).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogSnapshotHttpTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

  /** A new price for sku_out takes effect here; the golden cases are rated before it. */
  private static final Instant SWITCH = Instant.parse("2027-01-01T00:00:00Z");

  @LocalServerPort int port;
  @Autowired CatalogStore store;

  @BeforeEach
  void seed() {
    store.addAll(GoldenCases.catalog()); // idempotent
  }

  private HttpCatalogClient client(Duration versionCheckEvery) {
    return new HttpCatalogClient(
        URI.create("http://localhost:" + port),
        Duration.ofSeconds(5),
        versionCheckEvery,
        "rating-test",
        64,
        Clock.systemUTC());
  }

  @Test
  void aClientBuiltCatalogRatesEveryGoldenCaseIdentically() {
    RatingLibrary remote = client(Duration.ofSeconds(30)).at(GoldenCases.HOUR);
    for (GoldenCase c : GoldenCases.cases()) {
      assertThat(c.chargedCents(remote)).as(c.name()).isEqualTo(c.expectedCents());
    }
    assertThat(remote.version()).isEqualTo("rating-test/catalog-v" + store.version());
  }

  @Test
  void anEffectiveDatedPriceShowsAtItsBoundaryAndMovesTheVersion() {
    HttpCatalogClient client = client(Duration.ZERO);
    Instant before = SWITCH.minusSeconds(3600);
    long listBefore = client.at(before).chargeCents("org_other", "sku_out", 0, 1_000_000, before);
    assertThat(listBefore).isEqualTo(1500);
    String versionBefore = client.at(before).version();

    // The open-ended golden price can't be edited, so the new price is on a new SKU that starts
    // at SWITCH; the hour before it must not see it, the hour from it must.
    store.addSku(
        new com.logicvoid0x.billing.rating.Sku(
            "sku_2027",
            "model-x/standard/output_tokens/base/2027",
            com.logicvoid0x.billing.domain.MeterCode.OUTPUT_TOKENS,
            "model-x-2027",
            "standard",
            "base",
            EffectivePeriod.from(SWITCH)));
    store.addListPrice(
        new ListPrice(
            "sku_2027", Pricing.flat(new Rate(1800, 1_000_000)), EffectivePeriod.from(SWITCH)),
        1);

    RatingLibrary after = client.at(SWITCH);
    assertThat(after.version()).isNotEqualTo(versionBefore);
    assertThat(after.chargeCents("org_other", "sku_2027", 0, 1_000_000, SWITCH)).isEqualTo(1800);
    assertThat(client.at(before).version()).isEqualTo(after.version());
  }

  @Test
  void theVersionEndpointReturnsTheStoreVersion() throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/catalog/version"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(Long.parseLong(r.body().trim())).isEqualTo(store.version());
  }
}
