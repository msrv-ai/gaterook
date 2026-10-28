package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SshLogGrepTest {
  @Test
  void sourceProgressDoesNotDependOnWhetherGrepFoundCandidates() throws Exception {
    var empty = SshLogGrep.decode(result("", 100, 150, 150, true, 200));
    var found = SshLogGrep.decode(result("20:ERROR\n", 100, 150, 150, true, 200));
    assertThat(empty.lines()).isEmpty();
    assertThat(found.lines()).hasSize(1);
    assertThat(found.lines().getFirst().offset()).isEqualTo(120);
    assertThat(found.lines().getFirst().endOffset()).isEqualTo(126);
    assertThat(found.start()).isEqualTo(empty.start());
    assertThat(found.end()).isEqualTo(empty.end());
    assertThat(found.lastCompleteEnd()).isEqualTo(empty.lastCompleteEnd());
    assertThat(found.firstCompleteStart()).isEqualTo(empty.firstCompleteStart());
    assertThat(found.overflow()).isFalse();
  }

  @Test
  void excludesCutFragmentsAndPreservesUtf8ByteOffsets() throws Exception {
    var window = SshLogGrep.decode(result("0:ment\n5:échec\n12:par\n", 100, 115, 112, false, 200));
    assertThat(window.lines()).hasSize(1);
    var line = window.lines().getFirst();
    assertThat(line.offset()).isEqualTo(105);
    assertThat(line.endOffset()).isEqualTo(112);
    assertThat(new String(line.textBytes(), StandardCharsets.UTF_8)).isEqualTo("échec");
    assertThat(window.lastCompleteEnd()).isEqualTo(112);
  }

  @Test
  void acceptsAnUnterminatedFinalRecordWithoutInventingASourceByte() throws Exception {
    var window = SshLogGrep.decode(result("0:last\n", 100, 104, 104, true, 104));
    assertThat(window.lines()).hasSize(1);
    assertThat(window.lines().getFirst().offset()).isEqualTo(100);
    assertThat(window.lines().getFirst().endOffset()).isEqualTo(104);
  }

  @Test
  void rejectsMalformedOffsetsOverlapsAndTruncatedProtocolRows() {
    for (String raw : new String[] {"no:line\n", ":line\n", "-1:line\n", "0:unfinished", "100:out\n",
        "0:first\n2:overlap\n", "0:" + "x".repeat(60) + "\n"}) {
      assertThatThrownBy(() -> SshLogGrep.decode(result(raw, 100, 150, 150, true, 200)))
          .as(raw).isInstanceOf(ConnectorException.class).hasMessageContaining("invalid log search");
    }
  }

  private static SshLogCommands.Result result(String data, long start, long end, long complete, boolean boundary,
      long size) {
    var snapshot = new SshLogCommands.Snapshot("1:2", size, "123");
    return new SshLogCommands.Result(data.getBytes(StandardCharsets.UTF_8), snapshot, snapshot, start, end,
        boundary, complete == end, complete, start);
  }
}
