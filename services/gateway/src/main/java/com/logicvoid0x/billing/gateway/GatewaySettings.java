package com.logicvoid0x.billing.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway settings ({@code gateway.*}).
 *
 * @param enabled wire the request path (needs the shard, Kafka, the inference host and the catalog)
 * @param hostId this gateway host
 * @param homeRegion region recorded on settle records
 * @param shardTarget gRPC target of the regional budget shard
 * @param upstreamUrl base URL of the inference host
 * @param kafkaBootstrapServers Kafka for budget.settle and gateway.log
 * @param perMinute per-API-key requests per minute
 * @param chunkTokens output tokens per funded chunk
 * @param catalogUrl base URL of the catalog service (snapshot API, Q7)
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
    long chunkTokens,
    String catalogUrl) {}
