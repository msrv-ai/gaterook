package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The credential command runs as an argv without a shell, with a clean environment, stdin closed and a deadline; its
 * token is cached until it expires (report 08 §6). The "plugin" here is a shell script that records how it ran.
 */
@DisabledOnOs(OS.WINDOWS)
class ExecCredentialsTest {

  @TempDir
  Path dir;

  private Path plugin;

  @BeforeEach
  void writePlugin() throws Exception {
    plugin = dir.resolve("fake-auth-plugin");
    Files.writeString(plugin, """
        #!/bin/sh
        out="$1"
        echo run >> "$out/runs"
        env | cut -d= -f1 | sort > "$out/env"
        printf '%s' "$KUBERNETES_EXEC_INFO" > "$out/info.json"
        printf '%s\\n' "$@" > "$out/args"
        read line || true
        case "$2" in
          fail) echo "error: not signed in to cnry-account" >&2; exit 3 ;;
          hang) sleep 30 ;;
          expired) printf '%s' '{"apiVersion":"client.authentication.k8s.io/v1","kind":"ExecCredential",'
                   echo '"status":{"token":"cnry-exec-old","expirationTimestamp":"2000-01-01T00:00:00Z"}}' ;;
          cert) echo '{"status":{"clientCertificateData":"x","clientKeyData":"y"}}' ;;
          *) printf '%s' '{"apiVersion":"client.authentication.k8s.io/v1","kind":"ExecCredential",'
             echo '"status":{"token":"cnry-exec-token","expirationTimestamp":"2099-01-01T00:00:00Z"}}' ;;
        esac
        """);
    plugin.toFile().setExecutable(true);
  }

  private ExecCredentials.Spec spec(String mode) throws ConnectorException {
    JsonNode exec = json("""
        {"apiVersion":"client.authentication.k8s.io/v1","command":"%s","args":["%s","%s","it's $HOME"],
         "env":[{"name":"MARKER","value":"m"}],"provideClusterInfo":true}
        """.formatted(plugin, dir, mode));
    return ExecCredentials.spec(exec, dir, json("{\"server\":\"https://k8s:6443\"}"));
  }

  @Test
  void runsTheCommandWithAnArgvAndACleanEnvironment() throws Exception {
    ExecCredentials creds = new ExecCredentials(spec("ok"), Clock.systemUTC());

    assertThat(creds.token(Duration.ofSeconds(10))).isEqualTo("cnry-exec-token");

    Set<String> env = Files.readAllLines(dir.resolve("env")).stream().collect(Collectors.toSet());
    // sh itself adds PWD, SHLVL and _; nothing of the gateway's own environment arrives.
    assertThat(env).isSubsetOf("PATH", "HOME", "MARKER", "KUBERNETES_EXEC_INFO", "PWD", "SHLVL", "_", "OLDPWD");
    assertThat(env).contains("MARKER", "KUBERNETES_EXEC_INFO");
    JsonNode info = json(Files.readString(dir.resolve("info.json")));
    assertThat(info.at("/spec/interactive").asBoolean(true)).isFalse();
    assertThat(info.at("/spec/cluster/server").asString()).isEqualTo("https://k8s:6443");
    // No shell: "$HOME" in an argument stays text.
    assertThat(Files.readAllLines(dir.resolve("args"))).containsExactly(dir.toString(), "ok", "it's $HOME");
  }

  @Test
  void theTokenIsCachedUntilItExpires() throws Exception {
    ExecCredentials creds = new ExecCredentials(spec("ok"), Clock.systemUTC());

    creds.token(Duration.ofSeconds(10));
    creds.token(Duration.ofSeconds(10));
    assertThat(creds.current()).isEqualTo("cnry-exec-token");
    assertThat(Files.readAllLines(dir.resolve("runs"))).hasSize(1);

    creds.invalidate();
    creds.token(Duration.ofSeconds(10));
    assertThat(Files.readAllLines(dir.resolve("runs"))).hasSize(2);

    ExecCredentials expired = new ExecCredentials(spec("expired"), Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),
        ZoneOffset.UTC));
    expired.token(Duration.ofSeconds(10));
    expired.token(Duration.ofSeconds(10));
    assertThat(Files.readAllLines(dir.resolve("runs"))).hasSize(4);
  }

  @Test
  void aFailingCommandSaysWhyWithItsFirstErrorLine() throws Exception {
    ExecCredentials creds = new ExecCredentials(spec("fail"), Clock.systemUTC());

    assertThatThrownBy(() -> creds.token(Duration.ofSeconds(10))).isInstanceOfSatisfying(ConnectorException.class,
        e -> assertThat(e.getMessage()).contains("exited with 3: error: not signed in"));
    assertThat(creds.current()).isNull();
  }

  @Test
  void aHangingCommandIsKilledAtTheDeadline() throws Exception {
    ExecCredentials creds = new ExecCredentials(spec("hang"), Clock.systemUTC());

    long started = System.nanoTime();
    assertThatThrownBy(() -> creds.token(Duration.ofMillis(800))).isInstanceOfSatisfying(ConnectorException.class,
        e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("Run it in a terminal once");
        });
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void onlyTokensAreSupported() throws Exception {
    ExecCredentials creds = new ExecCredentials(spec("cert"), Clock.systemUTC());

    assertThatThrownBy(() -> creds.token(Duration.ofSeconds(10))).hasMessageContaining("client certificate");
  }

  @Test
  void theFingerprintCoversProgramArgumentsAndEnvironment() throws Exception {
    ExecCredentials.Spec a = spec("ok");
    ExecCredentials.Spec b = spec("other");
    JsonNode withEnv = json("""
        {"apiVersion":"client.authentication.k8s.io/v1","command":"%s","args":["%s","ok","it's $HOME"],
         "env":[{"name":"MARKER","value":"changed"}]}
        """.formatted(plugin, dir));

    assertThat(ExecCredentials.fingerprint(a)).isEqualTo(ExecCredentials.fingerprint(spec("ok")));
    assertThat(ExecCredentials.fingerprint(a)).isNotEqualTo(ExecCredentials.fingerprint(b));
    assertThat(ExecCredentials.fingerprint(a)).isNotEqualTo(ExecCredentials.fingerprint(ExecCredentials.spec(withEnv,
        dir, null)));
    assertThat(a.commandLine()).isEqualTo(plugin + " " + dir + " ok 'it'\\''s $HOME'");
  }

  @Test
  void theSpecIsCheckedBeforeAnythingRuns() {
    assertThatThrownBy(() -> ExecCredentials.spec(json("{\"apiVersion\":\"client.authentication.k8s.io/v1alpha1\","
        + "\"command\":\"x\"}"), dir, null)).hasMessageContaining("v1alpha1");
    assertThatThrownBy(() -> ExecCredentials.spec(json("{\"apiVersion\":\"client.authentication.k8s.io/v1\","
        + "\"command\":\"no-such-plugin-anywhere\"}"), dir, null)).hasMessageContaining("isn't installed");
    assertThatThrownBy(() -> ExecCredentials.spec(json("{\"apiVersion\":\"client.authentication.k8s.io/v1\","
        + "\"command\":\"" + plugin + "\",\"env\":[{\"name\":\"A=B\",\"value\":\"x\"}]}"), dir, null))
        .hasMessageContaining("invalid name");
    // A relative path with a slash is resolved against the kubeconfig's folder, as kubectl does.
    assertThat(Arrays.asList(plugin.getFileName().toString())).isNotEmpty();
  }
}
