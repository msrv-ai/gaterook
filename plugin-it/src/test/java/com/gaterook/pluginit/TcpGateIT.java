package com.gaterook.pluginit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.WireCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The TCP gate in a real host, sandboxed where this OS has a sandbox (ARCHITECTURE.md §7.5): a plugin that declares
 * {@code {connection.host}:{connection.port}} reaches exactly that with its own {@code java.net.Socket}s, from the
 * call's thread and from a thread the call starts, and nothing else; a test the environment's policy refuses names
 * the private range to allow.
 */
class TcpGateIT {

  private static final String GENERATION = "fixture-tcp-client@1.0.0";

  @TempDir
  Path home;

  private Harness host;
  private ServerSocket server;
  private ServerSocket other;

  @BeforeEach
  void listen() throws IOException {
    InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    server = answering(new ServerSocket(0, 50, loopback), "db");
    other = answering(new ServerSocket(0, 50, loopback), "other");
  }

  @AfterEach
  void stop() throws IOException {
    if (host != null) {
      host.close();
    }
    server.close();
    other.close();
  }

  /** Answers each connection's first line with {@code name} and the line. */
  private static ServerSocket answering(ServerSocket s, String name) {
    Thread.ofVirtual().start(() -> {
      while (!s.isClosed()) {
        try {
          Socket c = s.accept();
          Thread.ofVirtual().start(() -> {
            try (c) {
              String line = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))
                  .readLine();
              OutputStream out = c.getOutputStream();
              out.write((name + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
              out.flush();
            } catch (IOException ignored) {
              // The plugin went away.
            }
          });
        } catch (IOException e) {
          return;
        }
      }
    });
    return s;
  }

  private ConnectionConfig cfg() {
    ObjectNode config = GppJson.object().put("host", "127.0.0.1").put("port", server.getLocalPort());
    return new ConnectionConfig("c1", "warehouse", "fixture-tcp-client", Env.DEV, 1, config, Set.of(), null);
  }

  private Messages.EgressGrant grant(List<String> allowPrivate) {
    return new Messages.EgressGrant(List.of(), allowPrivate, List.of(7465, 7466),
        List.of("127.0.0.1:" + server.getLocalPort()));
  }

  @Test
  void thePluginsOwnSocketsReachItsConnectionAndNothingElse() {
    host = Harness.start(GENERATION, Harness.fixtureJar("tcp-client"), home, true);
    ObjectNode args = GppJson.object().put("otherPort", other.getLocalPort()).put("corePort", 7465);
    JsonNode result = host.execute(GENERATION, new ToolCall("tc_1", "tcp_probe", "warehouse", args, null), cfg(),
        false, grant(List.of("127.0.0.0/8")), Map.of(), Duration.ofSeconds(20));
    JsonNode out = WireCodec.result(result).structuredContent();

    assertThat(out.path("granted").asString()).isEqualTo("answered db hello");
    assertThat(out.path("fromThread").asString()).as("a thread the call started").isEqualTo("answered db hello");
    assertThat(out.path("otherPort").asString()).contains("not allowed by ruleset");
    assertThat(out.path("corePort").asString()).contains("not allowed by ruleset");
    assertThat(out.path("selector").asString()).isEqualTo("com.gaterook.pluginhost.TcpGate$Selector");
    assertThat(host.logs()).anySatisfy(l -> assertThat(l).contains("Refused a connection to 127.0.0.1:"
        + other.getLocalPort()));
  }

  @Test
  void aTestThePolicyRefusesNamesThePrivateRangeToAllow() {
    host = Harness.start(GENERATION, Harness.fixtureJar("tcp-client"), home, true);
    TestResult t = WireCodec.test(host.test(GENERATION, cfg(), grant(List.of()), Map.of(), Duration.ofSeconds(20)));

    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("tcp.private-address");
    assertThat(t.error().message()).isEqualTo("127.0.0.1 is a private address. Allow 127.0.0.1/32 for warehouse on "
        + "dev in the environment's policy (http.allowedPrivateRanges).");
    assertThat(t.error().technical()).contains("not allowed by ruleset");
    assertThat(t.suggestedPolicyChange().toString())
        .isEqualTo("{\"kind\":\"allow-private\",\"cidr\":\"127.0.0.1/32\",\"connection\":\"warehouse\"}");
  }
}
