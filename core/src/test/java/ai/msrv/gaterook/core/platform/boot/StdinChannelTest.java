package ai.msrv.gaterook.core.platform.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StdinChannelTest {

  @Test
  void theFirstLineIsTheBootstrapAndEofIsReportedAfterIt() throws Exception {
    CountDownLatch eof = new CountDownLatch(1);
    PipedOutputStream electron = new PipedOutputStream();
    StdinChannel stdin = StdinChannel.open(new PipedInputStream(electron), eof::countDown);

    electron.write("{\"v\":1}\r\nignored later input\n".getBytes(StandardCharsets.UTF_8));
    electron.flush();
    assertThat(new String(stdin.awaitLine(Duration.ofSeconds(5)), StandardCharsets.UTF_8)).isEqualTo("{\"v\":1}");
    assertThat(eof.await(200, TimeUnit.MILLISECONDS)).as("still running while stdin is open").isFalse();

    electron.close();
    assertThat(eof.await(5, TimeUnit.SECONDS)).as("EOF ends the core").isTrue();
  }

  @Test
  void noLineInTimeIsBootstrapTimeout() throws Exception {
    PipedOutputStream electron = new PipedOutputStream();
    StdinChannel stdin = StdinChannel.open(new PipedInputStream(electron), () -> {});

    assertThatThrownBy(() -> stdin.awaitLine(Duration.ofMillis(200)))
        .isInstanceOfSatisfying(GatewayStartupException.class,
            e -> assertThat(e.reason()).isEqualTo(StartupReason.BOOTSTRAP_TIMEOUT));
    electron.close();
  }

  @Test
  void eofBeforeTheLineIsInvalid() {
    CountDownLatch eof = new CountDownLatch(1);
    StdinChannel stdin = StdinChannel.open(new ByteArrayInputStream("{\"v\":1".getBytes(StandardCharsets.UTF_8)),
        eof::countDown);

    assertThatThrownBy(() -> stdin.awaitLine(Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(GatewayStartupException.class,
            e -> assertThat(e.reason()).isEqualTo(StartupReason.BOOTSTRAP_INVALID));
    assertThat(eof.getCount()).isZero();
  }

  @Test
  void anEndlessLineIsRefused() {
    byte[] huge = new byte[StdinChannel.MAX_LINE_BYTES + 10];
    Arrays.fill(huge, (byte) 'a');
    StdinChannel stdin = StdinChannel.open(new ByteArrayInputStream(huge), () -> {});

    assertThatThrownBy(() -> stdin.awaitLine(Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(GatewayStartupException.class,
            e -> assertThat(e.reason()).isEqualTo(StartupReason.BOOTSTRAP_INVALID));
  }
}
