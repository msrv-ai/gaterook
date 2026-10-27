package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigAndCommandsTest {

  @Test
  void prefixComesFromTheName() {
    assertThat(ServerConfig.defaultPrefix("github-mcp")).isEqualTo("github_");
    assertThat(ServerConfig.defaultPrefix("9lives")).isEqualTo("mcp9lives_");
    assertThat(ServerConfig.defaultPrefix("averyveryverylongname")).isEqualTo("averyveryverylo_");
    assertThat(ServerConfig.validPrefix("github_")).isTrue();
    assertThat(ServerConfig.validPrefix("Git_")).isFalse();
  }

  @Test
  void prodGetsShorterIdleAndCallTimeouts() {
    ConnectionConfig dev = Fixtures.cfg("c1", "fs", Env.DEV, 1, Fixtures.object("""
        {"transport":"stdio","command":["node","index.js"],"env":[{"name":"TOKEN"}]}"""));
    ConnectionConfig prod = new ConnectionConfig("c2", "fs", "mcp-server", Env.PROD, 1, dev.config(), Set.of(), null);

    ServerConfig d = ServerConfig.of(dev);
    ServerConfig p = ServerConfig.of(prod);

    assertThat(d.idleStop()).isEqualTo(Duration.ofMinutes(15));
    assertThat(d.callTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(p.idleStop()).isEqualTo(Duration.ofMinutes(5));
    assertThat(p.callTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(d.env()).containsExactly(new ServerConfig.Binding("TOKEN", "/env/0/secretRef"));
    // A binding whose secret isn't stored names no field: the start says so instead of sending nothing.
    assertThat(p.env()).containsExactly(new ServerConfig.Binding("TOKEN", null));
    assertThat(d.toolPrefix()).isEqualTo("fs_");
  }

  @Test
  void npxWithoutAnExactVersionIsNotPinned() {
    assertThat(Commands.notPinned(List.of("npx", "-y", "@modelcontextprotocol/server-github"))).isTrue();
    assertThat(Commands.notPinned(List.of("npx", "-y", "@modelcontextprotocol/server-github@2025.4.8"))).isFalse();
    assertThat(Commands.notPinned(List.of("npx", "-y", "server-x@latest"))).isTrue();
    assertThat(Commands.notPinned(List.of("/usr/local/bin/npx.cmd", "pkg@^1.2.0"))).isTrue();
    assertThat(Commands.notPinned(List.of("pnpm", "dlx", "pkg@1.0.0"))).isFalse();
    assertThat(Commands.notPinned(List.of("uvx", "mcp-server-fetch"))).isTrue();
    assertThat(Commands.notPinned(List.of("uvx", "mcp-server-fetch==2025.4.7"))).isFalse();
    assertThat(Commands.notPinned(List.of("node", "dist/index.js"))).isFalse();
  }
}
