package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SshLogFilesTest {
  @Test
  void rejectsAmbiguousPathsBeforeIo() {
    for (String value : new String[] {"../a", "a/../b", "a//b", "/file", "a\\b", "a\n", "a\0", "a/./b"}) {
      assertThatThrownBy(() -> SshLogSettings.relative(value)).isInstanceOf(Exception.class);
    }
    assertThat(SshLogSettings.join("/", "app.log")).isEqualTo("/app.log");
  }
}
