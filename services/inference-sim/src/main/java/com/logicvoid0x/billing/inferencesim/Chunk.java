package com.logicvoid0x.billing.inferencesim;

/**
 * One streamed chunk of output.
 *
 * @param index position in the stream, from 0
 * @param tokens output tokens in this chunk, {@code > 0}
 * @param text simulated text
 */
public record Chunk(int index, int tokens, String text) {}
