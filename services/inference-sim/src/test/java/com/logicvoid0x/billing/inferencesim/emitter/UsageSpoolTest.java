package com.logicvoid0x.billing.inferencesim.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageSpoolTest {
  @TempDir Path dir;

  private static byte[] b(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static List<String> payloads(List<UsageSpool.Entry> entries) {
    return entries.stream().map(e -> new String(e.payload(), StandardCharsets.UTF_8)).toList();
  }

  @Test
  void recordsComeBackInAppendOrderUntilCommitted() {
    try (UsageSpool spool = UsageSpool.open(dir)) {
      spool.append(b("a"));
      spool.append(b("b"));
      spool.append(b("c"));
      assertThat(spool.depth()).isEqualTo(3);

      List<UsageSpool.Entry> first = spool.peek(2);
      assertThat(payloads(first)).containsExactly("a", "b");
      assertThat(payloads(spool.peek(10)))
          .as("peek does not consume")
          .containsExactly("a", "b", "c");

      spool.commit(first.get(1).nextOffset(), 2);
      assertThat(spool.depth()).isEqualTo(1);
      assertThat(payloads(spool.peek(10))).containsExactly("c");
    }
  }

  @Test
  void stateSurvivesAReopen() {
    try (UsageSpool spool = UsageSpool.open(dir)) {
      spool.append(b("a"));
      spool.append(b("b"));
      spool.commit(spool.peek(1).get(0).nextOffset(), 1);
    }
    try (UsageSpool spool = UsageSpool.open(dir)) {
      assertThat(spool.depth()).isEqualTo(1);
      assertThat(payloads(spool.peek(10))).containsExactly("b");
      spool.append(b("c"));
      assertThat(payloads(spool.peek(10))).containsExactly("b", "c");
    }
  }

  @Test
  void aFullDrainEmptiesTheLog() throws IOException {
    try (UsageSpool spool = UsageSpool.open(dir)) {
      spool.append(b("a"));
      spool.commit(spool.peek(1).get(0).nextOffset(), 1);
      assertThat(spool.isEmpty()).isTrue();
      assertThat(Files.size(dir.resolve("spool.log"))).isZero();
      spool.append(b("d"));
      assertThat(payloads(spool.peek(10))).containsExactly("d");
    }
  }

  @Test
  void aTornTailFromACrashDuringAnAppendIsCutOff() throws IOException {
    try (UsageSpool spool = UsageSpool.open(dir)) {
      spool.append(b("a"));
      spool.append(b("b"));
    }
    Path log = dir.resolve("spool.log");
    long whole = Files.size(log);
    // Half a record: a length prefix and part of the payload, no checksum.
    Files.write(log, new byte[] {0, 0, 0, 9, 'x', 'y'}, StandardOpenOption.APPEND);

    try (UsageSpool spool = UsageSpool.open(dir)) {
      assertThat(spool.depth()).isEqualTo(2);
      assertThat(Files.size(log)).isEqualTo(whole);
      spool.append(b("c"));
      assertThat(payloads(spool.peek(10))).containsExactly("a", "b", "c");
    }
  }

  @Test
  void aCorruptRecordIsTreatedAsTheEndOfTheLog() throws IOException {
    try (UsageSpool spool = UsageSpool.open(dir)) {
      spool.append(b("a"));
      spool.append(b("b"));
    }
    Path log = dir.resolve("spool.log");
    byte[] bytes = Files.readAllBytes(log);
    bytes[bytes.length - 5] ^= 0x7f; // flip a payload byte of "b": its checksum no longer matches
    Files.write(log, bytes);
    try (UsageSpool spool = UsageSpool.open(dir)) {
      assertThat(payloads(spool.peek(10))).containsExactly("a");
    }
  }

  @Test
  void aCrashBetweenTruncateAndOffsetResetReadsAsDrained() throws IOException {
    try (UsageSpool spool = UsageSpool.open(dir)) {
      spool.append(b("a"));
    }
    // Simulate: log truncated after a full drain, offset file still at the old end.
    Files.writeString(dir.resolve("spool.offset"), "13");
    Files.write(dir.resolve("spool.log"), new byte[0]);
    try (UsageSpool spool = UsageSpool.open(dir)) {
      assertThat(spool.isEmpty()).isTrue();
      spool.append(b("z"));
      assertThat(payloads(spool.peek(10))).containsExactly("z");
    }
  }
}
