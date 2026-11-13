package com.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The service step of the SSH log files form against an in-process server whose SFTP root is a temporary folder: once
 * the host key is trusted it signs in, offers the log folder and its log files (rotated, compressed and binary logs
 * left out), and stops looking once a folder and files are chosen. A changed host key is a note and nothing is sent.
 */
class SshLogsSuggestionsIT {

  @TempDir
  Path temp;

  private TestSshd sshd;
  private SshLogsConnector connector;
  private TestSecrets secrets;

  @BeforeEach
  void start() throws Exception {
    Path root = temp.toRealPath();
    Path logs = Files.createDirectories(root.resolve("var/log"));
    Files.writeString(logs.resolve("syslog"), "Nov 12 10:00:00 host sshd[1]: started\n");
    Files.writeString(logs.resolve("auth.log"), "x".repeat(3000));
    Files.writeString(logs.resolve("syslog.1"), "rotated\n");
    Files.writeString(logs.resolve("syslog.2.gz"), "gz");
    Files.writeString(logs.resolve("wtmp"), "binary");
    Files.createDirectories(logs.resolve("nginx"));
    sshd = TestSshd.start();
    sshd.server.setFileSystemFactory(new VirtualFileSystemFactory(root));
    sshd.server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
    connector = new SshLogsConnector(new SshHome(root, "localuser"), port -> false);
    secrets = new TestSecrets().with("/credentials/password", TestSshd.PASSWORD);
  }

  @AfterEach
  void stop() throws Exception {
    connector.close();
    sshd.close();
  }

  private ConnectionConfig cfg(String pinned, String root, String... files) {
    ObjectNode config = TestConfigs.password(sshd.port(), pinned);
    if (root != null) {
      ObjectNode logs = config.putObject("logs").put("root", root);
      for (String f : files) {
        logs.withArray("allowedFiles").add(f);
      }
    }
    return new ConnectionConfig("logs-draft", "draft", SshLogsConnector.TYPE, Env.DEV, 1, config,
        Set.of("/credentials/password", "/credentials/passphrase"), JsonNodeFactory.instance.objectNode());
  }

  private Suggestions service(ConnectionConfig cfg) {
    return connector.suggest(cfg, secrets, TestCtx.read(), Suggestions.Scope.SERVICE);
  }

  @Test
  void aTrustedHostOffersItsLogFolderAndLogFiles() {
    Suggestions s = service(cfg(sshd.hostKeyLine(), null));

    Suggestions.Field root = s.fields().get("/logs/root");
    assertThat(root.value()).isEqualTo("/var/log");
    assertThat(root.source()).isEqualTo("the server");
    Suggestions.Field files = s.fields().get("/logs/allowedFiles");
    assertThat(files.value()).isNull();
    assertThat(files.complete()).isTrue();
    assertThat(files.options()).containsExactly(Option.of("auth.log", "2.9 KB"), Option.of("syslog", "38 bytes"));
    assertThat(s.notes()).containsExactly("Signed in as deploy@127.0.0.1:" + sshd.port() + ". 2 log files in "
        + "/var/log: select the ones agents may read.");
    // Listing a folder runs no command on the host.
    assertThat(sshd.commands).isEmpty();
  }

  @Test
  void aChosenFolderListsItsFilesAndAFinishedChoiceSignsInToNothing() {
    Suggestions s = service(cfg(sshd.hostKeyLine(), "/var/log"));

    assertThat(s.fields()).doesNotContainKey("/logs/root");
    assertThat(s.fields().get("/logs/allowedFiles").options()).extracting(Option::value)
        .containsExactly("auth.log", "syslog");

    int sessions = sshd.sessions.get();
    Suggestions done = service(cfg(sshd.hostKeyLine(), "/var/log", "syslog"));
    assertThat(done.isEmpty()).isTrue();
    assertThat(sshd.sessions.get()).isEqualTo(sessions);
  }

  @Test
  void aChangedHostKeyIsANoteAndNothingIsSent() throws Exception {
    String other = HostKeys.line(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256).getPublic());

    Suggestions s = service(cfg(other, null));

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).contains("changed"));
    assertThat(sshd.passwordAttempts.get()).isZero();
    assertThat(secrets.resolved.get()).isZero();
  }
}
