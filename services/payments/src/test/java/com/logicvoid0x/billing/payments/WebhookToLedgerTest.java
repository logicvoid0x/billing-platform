package com.logicvoid0x.billing.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.balanceledger.AccountKind;
import com.logicvoid0x.billing.balanceledger.BalanceLedger;
import com.logicvoid0x.billing.balanceledger.BalanceLedgerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stripe webhook to balance ledger, end to end over HTTP (Q10): the payments service with {@code
 * payments.enabled} posts each top-up to the real balance-ledger service on Postgres. Stripe is a
 * stand-in: events are signed with the test webhook secret.
 */
@Testcontainers
class WebhookToLedgerTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final String SECRET = "whsec_test_end_to_end";
  private static ConfigurableApplicationContext ledgerApp;
  private static ConfigurableApplicationContext paymentsApp;
  private static BalanceLedger ledger;
  private static URI webhook;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void start() {
    // Command-line arguments outrank each service's application.yml (ports, local database).
    String[] db = {
      "--spring.datasource.url=" + PG.getJdbcUrl(),
      "--spring.datasource.username=" + PG.getUsername(),
      "--spring.datasource.password=" + PG.getPassword(),
      "--server.port=0"
    };
    ledgerApp = new SpringApplicationBuilder(BalanceLedgerApplication.class).run(db);
    ledger = ledgerApp.getBean(BalanceLedger.class);
    int ledgerPort = ((WebServerApplicationContext) ledgerApp).getWebServer().getPort();

    // The ledger's jars are on this test classpath, so payments also gets the test database.
    String[] payments = {
      "--payments.enabled=true",
      "--payments.stripe.webhook-secret=" + SECRET,
      "--payments.ledger-url=http://localhost:" + ledgerPort
    };
    paymentsApp =
        new SpringApplicationBuilder(PaymentsApplication.class)
            .run(
                java.util.stream.Stream.concat(Arrays.stream(db), Arrays.stream(payments))
                    .toArray(String[]::new));
    int paymentsPort = ((WebServerApplicationContext) paymentsApp).getWebServer().getPort();
    webhook = URI.create("http://localhost:" + paymentsPort + "/webhooks/stripe");
  }

  @AfterAll
  static void stop() {
    if (paymentsApp != null) {
      paymentsApp.close();
    }
    if (ledgerApp != null) {
      ledgerApp.close();
    }
  }

  private static String succeeded(String paymentIntent, String org, long cents) {
    return "{\"id\":\"evt_"
        + paymentIntent
        + "\",\"type\":\"payment_intent.succeeded\",\"created\":"
        + Instant.now().getEpochSecond()
        + ",\"data\":{\"object\":{\"id\":\""
        + paymentIntent
        + "\",\"amount_received\":"
        + cents
        + ",\"currency\":\"usd\",\"metadata\":{\"org_id\":\""
        + org
        + "\"}}}}";
  }

  private HttpResponse<String> deliver(String payload) throws Exception {
    String signature =
        new StripeSignature(SECRET, StripeSignature.DEFAULT_TOLERANCE)
            .header(payload, Instant.now().getEpochSecond());
    return http.send(
        HttpRequest.newBuilder(webhook)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Stripe-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static long credit(String org) throws Exception {
    return ledger.balance(AccountKind.CUSTOMER_CREDIT.accountId(org, null));
  }

  @Test
  void aSucceededPaymentCreditsTheOrgOnceEvenWhenStripeRetries() throws Exception {
    String payload = succeeded("pi_e2e_1", "org_e2e", 2_500);

    HttpResponse<String> first = deliver(payload);
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.body()).isEqualTo("TOPPED_UP");

    HttpResponse<String> retry = deliver(payload);
    assertThat(retry.statusCode()).isEqualTo(200);
    assertThat(retry.body()).isEqualTo("DUPLICATE");

    assertThat(credit("org_e2e")).isEqualTo(2_500);
  }

  @Test
  void aBadSignatureNeverReachesTheLedger() throws Exception {
    String payload = succeeded("pi_e2e_forged", "org_forged", 9_999);
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(webhook)
                .header("Stripe-Signature", "t=1,v1=" + "00".repeat(32))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(credit("org_forged")).isZero();
  }

  @Test
  void aLedgerOutageFailsTheDeliverySoStripeRetries() throws Exception {
    HttpTopUps down = new HttpTopUps(URI.create("http://localhost:1"), Duration.ofMillis(500));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> down.topUp("stripe:pi_down", "org_down", 100, Instant.now()))
        .isInstanceOf(HttpTopUps.TopUpFailedException.class);
  }
}
