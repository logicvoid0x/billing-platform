package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.admission.AdmissionController;
import com.logicvoid0x.billing.admission.BudgetResolver;
import com.logicvoid0x.billing.admission.ModeCache;
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
 * inference host, Kafka for settle records and the gateway log. The rating library has no deployed
 * source yet (open question Q7), so enabling fails fast until one is provided.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewaySettings.class)
@ConditionalOnProperty(name = "gateway.enabled", havingValue = "true")
public class GatewayConfiguration {
  /** Rating libraries by time; there is no deployed catalog source yet. */
  @Bean
  Function<Instant, RatingLibrary> gatewayRatings() {
    throw new IllegalStateException(
        "gateway.enabled needs a catalog source for the rating library (open question Q7)");
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

  /** The request path. */
  @Bean
  Gateway gateway(
      GatewaySettings s,
      GrpcBudgetStore store,
      Function<Instant, RatingLibrary> ratings,
      KafkaGatewayEvents events) {
    Clock clock = Clock.systemUTC();
    return new Gateway(
        s.hostId(),
        new AdmissionController(s.hostId(), store, new ModeCache(), new BudgetResolver(), clock),
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
