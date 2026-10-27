package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The log of a plugin and its host (report 16 §5, review 2026-10-27): what a plugin writes can be anything, of any
 * size. The core read the host's stderr with {@code readLine()}, so a plugin printing one endless line grew a buffer
 * until the core ran out of memory; lines now come in bounded pieces, and only a bounded prefix of one is masked and
 * kept — the secrets in it masked, as before.
 */
class PluginLogTest {

  @TempDir
  Path tmp;

  @Test
  void anEndlessLineComesInBoundedPieces() throws Exception {
    long total = 10L * 1024 * 1024;
    InputStream endless = new InputStream() {
      long left = total;

      @Override
      public int read() {
        return left-- > 0 ? 'x' : -1;
      }
    };
    List<Integer> pieces = new ArrayList<>();
    PluginLog.lines(endless, PluginLog.MAX_READ, line -> pieces.add(line.length()));
    assertThat(pieces).allMatch(n -> n <= PluginLog.MAX_READ);
    assertThat(pieces.stream().mapToLong(Integer::longValue).sum()).isEqualTo(total);
  }

  @Test
  void linesAreSplitAtLineBreaks() throws Exception {
    List<String> lines = new ArrayList<>();
    PluginLog.lines(new ByteArrayInputStream("one\r\ntwo\nthree".getBytes(StandardCharsets.UTF_8)), 64, lines::add);
    assertThat(lines).containsExactly("one", "two", "three");
  }

  @Test
  void aHugeLineIsMaskedAndKeptOnlyInPart() throws Exception {
    Path file = tmp.resolve("plugin.log");
    try (PluginLog log = new PluginLog(file, Clock.systemUTC())) {
      log.taint("cnry-plugin-log-secret");
      log.stderr("token cnry-plugin-log-secret " + "x".repeat(2_000_000) + " cnry-plugin-log-secret");
      String written = Files.readString(file);
      assertThat(written).doesNotContain("cnry-plugin-log-secret").contains("token ‹masked›");
      assertThat(written.length()).isLessThan(PluginLog.MAX_LINE + 200);
    }
  }
}
