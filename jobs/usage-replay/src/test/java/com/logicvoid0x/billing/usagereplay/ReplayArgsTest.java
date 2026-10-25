package com.logicvoid0x.billing.usagereplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Argument handling of the deployed replay job. */
class ReplayArgsTest {
  @Test
  void theLocalArchivePropertiesNameTheRestCatalog() throws Exception {
    Path local = Path.of("../../infra/replay/iceberg-local.properties");
    Map<String, String> p = UsageReplayJob.icebergProperties(local.toString());
    assertThat(p)
        .containsEntry("uri", "http://iceberg-rest:8181")
        .containsEntry("warehouse", "s3://archive/warehouse");
  }

  @Test
  void aPropertiesFileWithoutAUriIsRejected(@TempDir Path dir) throws Exception {
    Path f = Files.writeString(dir.resolve("bad.properties"), "warehouse=s3://x\n");
    assertThatThrownBy(() -> UsageReplayJob.icebergProperties(f.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("uri");
  }

  @Test
  void tooFewArgumentsPrintUsage() {
    assertThatThrownBy(() -> UsageReplayJob.main(new String[] {"replay-1", "http://catalog"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("<catalog-url>");
  }
}
