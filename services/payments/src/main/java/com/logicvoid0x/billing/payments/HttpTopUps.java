package com.logicvoid0x.billing.payments;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posts top-ups to the balance-ledger service's {@code POST /v1/top-ups} (open question Q10).
 * Idempotent on the key at the ledger: 201 is "posted now", 200 is "already posted". Anything else
 * throws, so the caller fails and the payment is retried (Stripe retries webhooks for days).
 */
public final class HttpTopUps implements StripeWebhook.TopUps {
  /** The ledger refused or could not be reached; nothing is known to be posted. */
  public static final class TopUpFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    TopUpFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final URI endpoint;
  private final Duration timeout;
  private final HttpClient http;

  /**
   * Creates the sink.
   *
   * @param ledgerUrl base URL of the balance-ledger service
   * @param timeout per call
   */
  public HttpTopUps(URI ledgerUrl, Duration timeout) {
    this.endpoint = Objects.requireNonNull(ledgerUrl, "ledgerUrl").resolve("/v1/top-ups");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  @Override
  public boolean topUp(String key, String orgId, long cents, Instant at) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("key", key);
    body.put("orgId", orgId);
    body.put("amountCents", cents);
    body.put("occurredAt", at.toString());
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new TopUpFailedException("top-up " + key + ": balance ledger unreachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TopUpFailedException("top-up " + key + ": interrupted", e);
    }
    String result = "";
    if (response.statusCode() == 200 || response.statusCode() == 201) {
      JsonNode json = JSON.readTree(response.body());
      result = json.path("result").asString("");
    }
    if (response.statusCode() == 201 && result.equals("posted")) {
      return true;
    }
    if (response.statusCode() == 200 && result.equals("duplicate")) {
      return false;
    }
    throw new TopUpFailedException(
        "top-up "
            + key
            + ": balance ledger answered HTTP "
            + response.statusCode()
            + " "
            + response.body(),
        null);
  }
}
