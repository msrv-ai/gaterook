package ai.msrv.gaterook.core.audit.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeSyslogTest {

  private static final String HEADER = "<134>1 2026-11-08T12:00:00Z host GateRook 42 tool-call ";

  @Test
  void separatesStructuredDataFromMultilineMessages() {
    String data = "[example value=\"escaped \\] bracket\"][other value=\"a\"]";
    FakeSyslog.Message parsed = FakeSyslog.parseMessage(HEADER + data + " first\nsecond", 3, true);
    assertThat(parsed.structuredData()).isEqualTo(data);
    assertThat(parsed.msg()).isEqualTo("first\nsecond");
    assertThat(parsed.connection()).isEqualTo(3);
    assertThat(parsed.clientCertificate()).isTrue();
    assertThat(FakeSyslog.parseMessage(HEADER + "- {}", 1, false).structuredData()).isEqualTo("-");
  }

  @Test
  void handlesLongAndMalformedFramesWithoutBacktracking() {
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      String message = "] a".repeat(100_000);
      assertThat(FakeSyslog.parseMessage(HEADER + "[example] " + message, 1, false).msg())
          .isEqualTo(message);
      assertThat(FakeSyslog.parseMessage(HEADER + "[" + "a".repeat(100_000), 1, false)).isNull();
    });
    assertThat(FakeSyslog.parseMessage(HEADER + "[example]missing-space", 1, false)).isNull();
    assertThat(FakeSyslog.parseMessage(HEADER + "[example value=\"unfinished\\", 1, false)).isNull();
  }
}
