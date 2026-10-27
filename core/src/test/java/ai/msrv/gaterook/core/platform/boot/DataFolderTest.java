package ai.msrv.gaterook.core.platform.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Decision 11: the data folder is {@code ~/.gaterook} — the launcher's home before Spring runs and Spring's
 * {@code gateway.home} agree on it; {@code ~/.mcp-gateway} of earlier builds is never the default, and nothing is
 * moved or read from there.
 */
class DataFolderTest {

  @Test
  void theDefaultHomeIsGaterookUnderTheUsersHome() {
    String before = System.getProperty("gateway.home");
    System.clearProperty("gateway.home");
    try {
      assertThat(GatewayLauncher.defaultHome()).isEqualTo(Path.of(System.getProperty("user.home"), ".gaterook"));
    } finally {
      if (before != null) {
        System.setProperty("gateway.home", before);
      }
    }
  }

  @Test
  void anExplicitHomeWins() {
    String before = System.getProperty("gateway.home");
    System.setProperty("gateway.home", "/tmp/elsewhere");
    try {
      assertThat(GatewayLauncher.defaultHome()).isEqualTo(Path.of("/tmp/elsewhere"));
    } finally {
      if (before != null) {
        System.setProperty("gateway.home", before);
      } else {
        System.clearProperty("gateway.home");
      }
    }
  }

  @Test
  void springsDefaultHomeIsTheSame() throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
      assertThat(in).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(yaml).contains("  home: ${user.home}/.gaterook\n").doesNotContain("${user.home}/.mcp-gateway");
    }
  }
}
