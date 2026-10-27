package ai.msrv.gaterook.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameReaderTest {

  /** Hands out at most {@code step} bytes per read, as a pipe does under load. */
  private static InputStream trickle(byte[] bytes, int step) {
    return new InputStream() {
      private int pos;

      @Override
      public int read() {
        return pos < bytes.length ? bytes[pos++] & 0xff : -1;
      }

      @Override
      public int read(byte[] b, int off, int len) {
        if (pos >= bytes.length) {
          return -1;
        }
        int n = Math.min(Math.min(len, step), bytes.length - pos);
        System.arraycopy(bytes, pos, b, off, n);
        pos += n;
        return n;
      }
    };
  }

  private static List<String> frames(FrameReader r) throws IOException {
    List<String> out = new ArrayList<>();
    int n;
    while ((n = r.next()) >= 0) {
      out.add(new String(r.buffer(), 0, n, StandardCharsets.UTF_8));
      r.wipe(n);
    }
    return out;
  }

  @Test
  void framesSplitAndJoinedAnyHowComeOutWhole() throws IOException {
    byte[] input = "{\"a\":1}\n{\"b\":\"тест\"}\r\n\n\n{\"c\":3}\n".getBytes(StandardCharsets.UTF_8);
    for (int step : new int[] {1, 2, 3, 7, 64, 65_536}) {
      assertThat(frames(new FrameReader(trickle(input, step), 1024)))
          .as("step " + step).containsExactly("{\"a\":1}", "{\"b\":\"тест\"}", "{\"c\":3}");
    }
  }

  @Test
  void aPartialLastLineIsDroppedAtTheEnd() throws IOException {
    FrameReader r = new FrameReader(new ByteArrayInputStream("{\"a\":1}\n{\"b\"".getBytes(StandardCharsets.UTF_8)),
        1024);
    assertThat(frames(r)).containsExactly("{\"a\":1}");
  }

  @Test
  void aFrameOverTheLimitIsAProtocolViolation() {
    byte[] big = new byte[2_000];
    Arrays.fill(big, (byte) 'x');
    byte[] input = (new String(big, StandardCharsets.US_ASCII) + "\n").getBytes(StandardCharsets.US_ASCII);
    FrameReader r = new FrameReader(trickle(input, 100), 1_000);
    assertThatThrownBy(r::next).isInstanceOf(FrameReader.FrameTooLarge.class);
  }

  @Test
  void aFrameExactlyAtTheLimitPasses() throws IOException {
    byte[] input = new byte[1_001];
    Arrays.fill(input, (byte) 'y');
    input[1_000] = '\n';
    FrameReader r = new FrameReader(new ByteArrayInputStream(input), 1_000);
    assertThat(r.next()).isEqualTo(1_000);
  }

  @Test
  void wipingZeroesTheFrame() throws IOException {
    FrameReader r = new FrameReader(new ByteArrayInputStream("{\"secret\":\"s3cr3t\"}\n"
        .getBytes(StandardCharsets.UTF_8)), 1024);
    int n = r.next();
    r.wipe(n);
    for (int i = 0; i < n; i++) {
      assertThat(r.buffer()[i]).isZero();
    }
  }
}
