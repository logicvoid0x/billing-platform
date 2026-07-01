package com.logicvoid0x.billing.catalog.launch;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.catalog.store.CatalogStore;
import com.logicvoid0x.billing.domain.MeterCode;
import com.logicvoid0x.billing.money.Cents.Rate;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.ListPrice;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.Sku;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The launch-gate endpoint on the running service, against real Postgres. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LaunchGateHttpTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

  private static final Instant V1 = Instant.parse("2026-01-01T00:00:00Z");
  private static final String AT = "2026-12-01T09:00:00Z";

  @LocalServerPort int port;
  @Autowired CatalogStore store;
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> gate(String query) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/launch-gate?" + query))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void blocksAModelUntilEveryMeterHasAPricedSku() throws Exception {
    store.addOffering(new ModelOffering("model-gate", "standard", MeterCode.INPUT_TOKENS));
    store.addOffering(new ModelOffering("model-gate", "standard", MeterCode.OUTPUT_TOKENS));
    addPricedSku("gate_in", MeterCode.INPUT_TOKENS);

    HttpResponse<String> blocked = gate("model=model-gate&at=" + AT);
    assertThat(blocked.statusCode()).isEqualTo(409);
    assertThat(blocked.body())
        .contains("\"passed\":false")
        .contains("\"meter\":\"output_tokens\"")
        .contains("\"reason\":\"NO_SKU\"")
        .contains("\"catalogVersion\":\"catalog-v");

    addPricedSku("gate_out", MeterCode.OUTPUT_TOKENS);
    HttpResponse<String> open = gate("model=model-gate&at=" + AT);
    assertThat(open.statusCode()).isEqualTo(200);
    assertThat(open.body()).contains("\"passed\":true").contains("\"gaps\":[]");
  }

  @Test
  void anUnregisteredModelIsBlocked() throws Exception {
    HttpResponse<String> r = gate("model=model-unknown");
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(r.body()).contains("NOT_REGISTERED");
  }

  private void addPricedSku(String id, MeterCode meter) {
    store.addSku(
        new Sku(
            id, id + "/key", meter, "model-gate", "standard", "base", EffectivePeriod.from(V1)));
    store.addListPrice(
        new ListPrice(id, Pricing.flat(new Rate(300, 1_000_000)), EffectivePeriod.from(V1)), 1);
  }
}
