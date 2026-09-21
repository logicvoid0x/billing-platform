package com.logicvoid0x.billing.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every signal in the design's "Signals" table has an alert rule in infra/prometheus/alerts.yml
 * (labelled {@code signal: "<name>"}), and no rule names a signal the design does not have.
 */
class SloCoverageTest {
  private static final Path ROOT = Path.of("..", "..");

  /** Signal names: the first cell of each row after the "| Signal | ..." header. */
  static Set<String> designSignals() throws IOException {
    Set<String> signals = new LinkedHashSet<>();
    boolean inTable = false;
    for (String line : Files.readAllLines(ROOT.resolve("docs/design.md"), StandardCharsets.UTF_8)) {
      String t = line.trim();
      if (t.startsWith("| Signal ")) {
        inTable = true;
        continue;
      }
      if (inTable) {
        if (!t.startsWith("|")) {
          break;
        }
        if (t.startsWith("|---")) {
          continue;
        }
        String first = t.substring(1, t.indexOf('|', 1)).trim().replace("`", "");
        signals.add(first);
      }
    }
    return signals;
  }

  static Set<String> alertSignals() throws IOException {
    String yaml =
        Files.readString(ROOT.resolve("infra/prometheus/alerts.yml"), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("signal: \"([^\"]+)\"").matcher(yaml);
    Set<String> out = new LinkedHashSet<>();
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  @Test
  void everyDesignSignalHasAnAlertRule() throws IOException {
    Set<String> design = designSignals();
    assertThat(design).as("signals parsed from docs/design.md").hasSizeGreaterThanOrEqualTo(16);
    assertThat(alertSignals()).containsExactlyInAnyOrderElementsOf(design);
  }
}
