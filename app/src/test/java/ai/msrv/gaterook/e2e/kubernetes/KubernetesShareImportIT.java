package ai.msrv.gaterook.e2e.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionTester;
import ai.msrv.gaterook.core.connections.HealthMonitor;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointShareService;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The C1 chain through an endpoint share, with the real Kubernetes connector in the assembled core: a pasted
 * kubeconfig (a secret, included) whose user signs in with a credential command, and the opt-in to run it with the
 * fingerprint of exactly that command, as someone allowed it on their computer and an older GateRook exported it.
 * The import drops both, creates disabled drafts, and the command never starts: not in the production preflight, not
 * in a health check, not when the person tests the draft. It starts only once the person opted in and trusted the
 * command on this computer. The command writes a marker file, so whether it ran is a fact of the file system.
 */
class KubernetesShareImportIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("k8s-share-import-it-secret-01234".getBytes(StandardCharsets.US_ASCII));
  private static final String OPT_IN = "/allowExecPlugin: Not imported. Confirm this trust setting on this computer "
      + "with Test connection, or set it yourself.";
  private static final String ALLOWED = "/trustedExec: Not imported. Confirm this trust setting on this computer "
      + "with Test connection, or set it yourself.";

  private static ConfigurableApplicationContext core;
  private static Path work;

  @BeforeAll
  static void start() throws Exception {
    Path homes = Files.createDirectories(Path.of("target", "core-homes").toAbsolutePath());
    Path home = Files.createTempDirectory(homes, "k8s-share-");
    work = Files.createTempDirectory(homes, "k8s-share-command-");
    // Health checks as the person has them: a new enabled connection is checked at once.
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=5m",
            "--gateway.connections.health-initial-delay=1h",
            "--gateway.clients.user-home=" + home.resolve("user-home"));
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void aSharedCredentialCommandNeverStartsBeforeThePersonAllowsItHere() throws Exception {
    Path marker = work.resolve("credential-command-ran");
    String kubeconfig = kubeconfig(credentialCommand(marker));
    ConnectionTester tester = core.getBean(ConnectionTester.class);
    ObjectNode signIn = (ObjectNode) JSON.readTree("{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\"}");
    ObjectNode optedIn = signIn.deepCopy().put("allowExecPlugin", true);
    // What Trust records for this command on a computer that allows it: Test connection shows it, nothing runs.
    String fingerprint = tester.testDraft("kubernetes", Env.DEV, optedIn, secret(kubeconfig), null, null).result()
        .needsConfirmation().configPatch().path("trustedExec").asString();
    assertThat(fingerprint).matches("[0-9a-f]{64}");
    assertThat(marker).doesNotExist();

    // The file: an endpoint as GateRook exports it, with the connection as an older GateRook wrote it.
    core.getBean(EndpointService.class).create("k8s-share-source", null, EndpointService.StartFrom.EMPTY, "test");
    EndpointShareService shares = core.getBean(EndpointShareService.class);
    ObjectNode bundle = shares.export("k8s-share-source", List.of(), false);
    ArrayNode entries = bundle.putArray("connections");
    for (String env : List.of("dev", "prod")) {
      ObjectNode entry = entries.addObject().put("id", "cluster-" + env).put("name", "shared-cluster-" + env)
          .put("type", "kubernetes").put("env", env);
      entry.set("config", optedIn.deepCopy().put("trustedExec", fingerprint));
      entry.putObject("secrets").put("/kubeconfig", kubeconfig);
      entry.putArray("omittedSecretFields");
      entry.putArray("tools");
      entry.putArray("templates");
      entry.putArray("warnings");
    }

    JsonNode preview = shares.preview(bundle);
    assertThat(preview.path("connections")).hasSize(2).allSatisfy(row -> {
      assertThat(row.path("config")).isEqualTo(signIn);
      assertThat(row.path("warnings").toString()).contains("Trust settings in this file are not imported: "
          + "/allowExecPlugin, /trustedExec.");
    });

    JsonNode imported = shares.importBundle(bundle, List.of("cluster-dev", "cluster-prod"), false, true, null,
        "test");

    // Production too: a draft is not tested for its rights, so the import asked for no confirmation.
    assertThat(imported.path("connections")).hasSize(2)
        .allMatch(connection -> connection.path("needsSetup").asBoolean());
    ConnectionConfigs configs = core.getBean(ConnectionConfigs.class);
    for (String name : List.of("shared-cluster-dev", "shared-cluster-prod")) {
      var stored = configs.byName(name).orElseThrow();
      assertThat(stored.config().config()).as(name).isEqualTo(signIn);
      assertThat(stored.enabled()).as(name).isFalse();
      assertThat(stored.secretIds()).as(name).containsOnlyKeys("/kubeconfig");
      assertThat(stored.config().metadata().path("setupMissingFields")).as(name)
          .anyMatch(item -> item.asString().equals(OPT_IN)).anyMatch(item -> item.asString().equals(ALLOWED));
    }
    assertThat(core.getBean(HealthMonitor.class).checkAll()).as("a draft is not checked").isZero();
    assertThat(marker).as("the credential command started during the import").doesNotExist();

    // The person tests the draft in its editor with the stored kubeconfig: the connector asks them to allow the
    // command, which opts in too; the file's own opt-in and fingerprint were never taken.
    ConnectionTester.Outcome offered = tester.testDraft("kubernetes", Env.DEV, signIn, Map.of(), "shared-cluster-dev",
        null);
    assertThat(offered.result().ok()).isFalse();
    assertThat(offered.result().needsConfirmation().configPatch().path("allowExecPlugin").asBoolean()).isTrue();
    assertThat(offered.result().needsConfirmation().configPatch().path("trustedExec").asString())
        .isEqualTo(fingerprint);
    // Opted in already, Test connection shows the same command and fingerprint, the one the file carried, to trust.
    ConnectionTester.Outcome asked = tester.testDraft("kubernetes", Env.DEV, optedIn, Map.of(), "shared-cluster-dev",
        null);
    assertThat(asked.result().needsConfirmation().configPatch().path("trustedExec").asString())
        .isEqualTo(fingerprint);
    assertThat(marker).as("the credential command started before the person trusted it").doesNotExist();

    // Only their Trust on this computer lets it start (so the checks above are not vacuous).
    ConnectionTester.ConfirmOutcome trusted = tester.confirm(asked.confirmationId(), "Allowed on this computer");
    tester.testDraft("kubernetes", Env.DEV, trusted.config(), Map.of(), "shared-cluster-dev", null);
    assertThat(marker).exists();
  }

  private static Map<String, SecretInput> secret(String kubeconfig) {
    return Map.of("/kubeconfig", SecretInput.local(kubeconfig.toCharArray()));
  }

  /** A credential plugin that records each start in {@code marker} and answers with a token. */
  private static Path credentialCommand(Path marker) throws Exception {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    Path command = work.resolve(windows ? "auth-plugin.cmd" : "auth-plugin");
    String credential = "{\"apiVersion\":\"client.authentication.k8s.io/v1\",\"kind\":\"ExecCredential\","
        + "\"status\":{\"token\":\"shared-exec-token\"}}";
    if (windows) {
      Files.writeString(command, "@echo off\r\necho run>> \"" + marker + "\"\r\necho " + credential + "\r\n");
    } else {
      Files.writeString(command, "#!/bin/sh\necho run >> '" + marker + "'\nprintf '%s\\n' '" + credential + "'\n");
      assertThat(command.toFile().setExecutable(true)).isTrue();
    }
    return command;
  }

  /** A kubeconfig of a cluster nothing answers for, whose user signs in with {@code command}. */
  private static String kubeconfig(Path command) throws Exception {
    int port;
    try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = free.getLocalPort();
    }
    HeldCertificate ca = new HeldCertificate.Builder().certificateAuthority(0).commonName("kubernetes").build();
    return """
        apiVersion: v1
        kind: Config
        current-context: shared
        clusters: [{name: shared, cluster: {server: "https://127.0.0.1:%d", certificate-authority-data: %s}}]
        contexts: [{name: shared, context: {cluster: shared, user: shared}}]
        users:
        - name: shared
          user:
            exec: {apiVersion: client.authentication.k8s.io/v1, command: %s}
        """.formatted(port, Base64.getEncoder().encodeToString(ca.certificatePem().getBytes(StandardCharsets.UTF_8)),
            JSON.writeValueAsString(command.toString()));
  }
}
