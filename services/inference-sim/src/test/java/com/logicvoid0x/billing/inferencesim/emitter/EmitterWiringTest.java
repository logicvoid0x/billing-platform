package com.logicvoid0x.billing.inferencesim.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The default wiring: Kafka emitter plus spool, and the spool-depth gauge on the Prometheus
 * endpoint. The broker address is unreachable on purpose; nothing is sent in this test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "usage.emitter.bootstrap-servers=127.0.0.1:1",
      "usage.spool.dir=build/tmp/emitter-wiring-spool",
      "usage.spool.drain-every=1h"
    })
class EmitterWiringTest {
  @LocalServerPort int port;
  @Autowired UsageSink sink;

  @Test
  void theSpoolingSinkIsTheUsageSinkAndItsDepthIsAMetric() throws Exception {
    assertThat(sink).isInstanceOf(SpoolingUsageSink.class);
    HttpResponse<String> metrics =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/prometheus"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body()).contains("usage_spool_depth");
  }
}
