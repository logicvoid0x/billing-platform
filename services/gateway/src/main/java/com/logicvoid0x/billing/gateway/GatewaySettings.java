package com.logicvoid0x.billing.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway settings ({@code gateway.*}).
 *
 * @param enabled wire the request path; off until the catalog source is decided (Q7)
 * @param hostId this gateway host
 * @param homeRegion region recorded on settle records
 * @param shardTarget gRPC target of the regional budget shard
 * @param upstreamUrl base URL of the inference host
 * @param kafkaBootstrapServers Kafka for budget.settle and gateway.log
 * @param perMinute per-API-key requests per minute
 * @param chunkTokens output tokens per funded chunk
 */
@ConfigurationProperties("gateway")
public record GatewaySettings(
    boolean enabled,
    String hostId,
    String homeRegion,
    String shardTarget,
    String upstreamUrl,
    String kafkaBootstrapServers,
    long perMinute,
    long chunkTokens) {}
