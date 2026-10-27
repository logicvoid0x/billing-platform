package com.logicvoid0x.billing.balanceledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@code POST /v1/top-ups} on the running service against real Postgres (Q10). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TopUpHttpTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

  @LocalServerPort int port;
  @Autowired BalanceLedger ledger;
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> post(String json) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/top-ups"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String topUp(String key, String org, long cents) {
    return "{\"key\":\""
        + key
        + "\",\"orgId\":\""
        + org
        + "\",\"amountCents\":"
        + cents
        + ",\"occurredAt\":\"2026-11-02T09:00:00Z\"}";
  }

  @Test
  void theSameKeyTwicePostsOnce() throws Exception {
    HttpResponse<String> first = post(topUp("stripe:pi_1", "org_http", 5_000));
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(first.body()).contains("\"result\":\"posted\"");

    HttpResponse<String> replay = post(topUp("stripe:pi_1", "org_http", 5_000));
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.body()).contains("\"result\":\"duplicate\"");

    assertThat(ledger.balance(AccountKind.CUSTOMER_CREDIT.accountId("org_http", null)))
        .isEqualTo(5_000);
  }

  @Test
  void aKeyReusedWithAnotherAmountIsAConflict() throws Exception {
    assertThat(post(topUp("stripe:pi_2", "org_conflict", 1_000)).statusCode()).isEqualTo(201);
    HttpResponse<String> r = post(topUp("stripe:pi_2", "org_conflict", 2_000));
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(ledger.balance(AccountKind.CUSTOMER_CREDIT.accountId("org_conflict", null)))
        .isEqualTo(1_000);
  }

  @Test
  void invalidTopUpsAreRejected() throws Exception {
    assertThat(post(topUp("stripe:pi_3", "org_bad", 0)).statusCode()).isEqualTo(400);
    assertThat(post(topUp("stripe:pi_4", "org_bad", -5)).statusCode()).isEqualTo(400);
    assertThat(post(topUp(" ", "org_bad", 5)).statusCode()).isEqualTo(400);
    assertThat(post("{\"key\":\"stripe:pi_5\",\"orgId\":\"org_bad\"}").statusCode()).isEqualTo(400);
  }
}
