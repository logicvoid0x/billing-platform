package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.admission.BudgetResolver;
import com.logicvoid0x.billing.admission.ModeCache;
import com.logicvoid0x.billing.contracts.catalog.HttpCatalogClient;
import com.logicvoid0x.billing.contracts.v1.BudgetShardGrpc;
import com.logicvoid0x.billing.rating.RatingLibrary;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the gateway when {@code gateway.enabled=true}: gRPC to the regional shard, HTTP to the
 * inference host, Kafka for settle records and the gateway log, and the catalog's snapshot API for
 * the rating library (Q7).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewaySettings.class)
@ConditionalOnProperty(name = "gateway.enabled", havingValue = "true")
public class GatewayConfiguration {
  /** Rating libraries by time, from the catalog service; cached per hour, re-checked every 30 s. */
  @Bean
  Function<Instant, RatingLibrary> gatewayRatings(GatewaySettings s) {
    HttpCatalogClient catalog =
        new HttpCatalogClient(
            URI.create(s.catalogUrl()),
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            "rating-1",
            48,
            Clock.systemUTC());
    return catalog::at;
  }

  /** Channel to the regional budget shard. */
  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel shardChannel(GatewaySettings s) {
    return NettyChannelBuilder.forTarget(s.shardTarget()).usePlaintext().build();
  }

  /** The shard as a BudgetStore. */
  @Bean
  GrpcBudgetStore shardStore(ManagedChannel channel) {
    return new GrpcBudgetStore(BudgetShardGrpc.newBlockingStub(channel), Duration.ofMillis(250));
  }

  /** Settle records and gateway log. */
  @Bean(destroyMethod = "close")
  KafkaGatewayEvents gatewayEvents(GatewaySettings s) {
    return KafkaGatewayEvents.create(s.kafkaBootstrapServers(), s.hostId());
  }

  /** Admission for this host; on shutdown its leases are reported back to the shard (Q5). */
  @Bean(destroyMethod = "returnLeases")
  AdmissionController admission(GatewaySettings s, GrpcBudgetStore store) {
    return new AdmissionController(
        s.hostId(), store, new ModeCache(), new BudgetResolver(), Clock.systemUTC());
  }

  /** The request path. */
  @Bean
  Gateway gateway(
      GatewaySettings s,
      GrpcBudgetStore store,
      AdmissionController admission,
      Function<Instant, RatingLibrary> ratings,
      KafkaGatewayEvents events) {
    Clock clock = Clock.systemUTC();
    return new Gateway(
        s.hostId(),
        admission,
        store,
        ratings,
        new HttpUpstream(
            HttpClient.newHttpClient(), URI.create(s.upstreamUrl()), Duration.ofMinutes(10)),
        events,
        new MinuteLimiter(s.perMinute(), clock),
        clock,
        s.chunkTokens());
  }
}
