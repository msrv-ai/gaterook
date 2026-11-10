package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Disconnect and Remove bridge end a client's access first and always: a config file GateRook can't change (it no
 * longer parses, it can't be written) leaves the entry for the person, named with its file, and never keeps the
 * access alive. Removing GateRook's Codex entry keeps a table someone put between its markers.
 */
class ClientDisconnectIT {

  private static Path userHome;
  private static Path codexConfig;
  private static Path claudeConfig;
  private static TestCore core;
  private static ClientService clients;

  @BeforeAll
  static void start() throws IOException {
    userHome = Files.createTempDirectory(Files.createDirectories(Path.of("target", "client-homes").toAbsolutePath()),
        "disconnect-");
    codexConfig = Files.createDirectories(userHome.resolve(".codex")).resolve("config.toml");
    claudeConfig = Files.createDirectories(userHome.resolve(".config/Claude")).resolve("claude_desktop_config.json");
    core = TestCore.start("client-disconnect", "--gateway.clients.user-home=" + userHome, "--gateway.clients.os=linux");
    clients = core.bean(ClientService.class);
    core.bean(EndpointService.class).create("dev", null, EndpointService.StartFrom.EMPTY, null);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  /** Each test starts from no client files, whatever the previous one left. */
  @AfterEach
  void forgetTheFiles() throws IOException {
    Files.deleteIfExists(codexConfig);
    Files.deleteIfExists(claudeConfig);
  }

  @Test
  void aCodexFileThatNoLongerParsesStillLetsDisconnectEndTheAccess() throws IOException {
    ClientViews.Link link = connect("codex", "helper");
    String broken = Files.readString(codexConfig) + "\nthis line = = isn't TOML\n";
    Files.writeString(codexConfig, broken);

    ClientViews.Preview preview = clients.previewDisconnect(link.id());
    assertThat(preview.files()).isEmpty();
    assertThat(preview.byHand()).isNotNull();
    assertThat(preview.byHand().file()).endsWith(".codex/config.toml");
    assertThat(preview.byHand().entry()).isEqualTo(link.entryName());
    assertThat(preview.byHand().reason()).contains("isn't valid TOML");

    ClientViews.Disconnected done = clients.disconnect(link.id(), preview.previewId());
    assertThat(done.entryRemoved()).isFalse();
    assertThat(done.byHand()).isEqualTo(preview.byHand());
    assertThat(String.join(" ", done.notes())).contains(link.entryName()).contains("remove the entry by hand");
    assertThat(grantRevoked(link.id())).as("the access ends although the file stays").isTrue();
    assertThat(Files.readString(codexConfig)).as("the file stays as the person left it").isEqualTo(broken);
    assertThat(clients.clients().clients().stream().flatMap(c -> c.links().stream()).map(ClientViews.Link::id))
        .doesNotContain(link.id());
  }

  @Test
  void aFileGateRookCantWriteStillLetsDisconnectEndTheAccess() throws IOException {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    ClientViews.Link link = connect("codex", "helper");
    Path dir = codexConfig.getParent();
    String ours = Files.readString(codexConfig);
    ClientViews.Preview preview = clients.previewDisconnect(link.id());
    assertThat(preview.files()).hasSize(1);
    assertThat(preview.byHand()).isNull();

    Set<PosixFilePermission> mode = Files.getPosixFilePermissions(dir);
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
    ClientViews.Disconnected done;
    try {
      done = clients.disconnect(link.id(), preview.previewId());
    } finally {
      Files.setPosixFilePermissions(dir, mode);
    }
    assertThat(done.entryRemoved()).isFalse();
    assertThat(done.byHand()).isNotNull();
    assertThat(done.byHand().entry()).isEqualTo(link.entryName());
    assertThat(done.byHand().reason()).startsWith("GateRook can't write it:");
    assertThat(String.join(" ", done.notes())).contains(link.entryName()).contains("remove the entry by hand");
    assertThat(grantRevoked(link.id())).isTrue();
    assertThat(Files.readString(codexConfig)).isEqualTo(ours);
  }

  @Test
  void removingOurCodexEntryKeepsATableSomeoneAddedBetweenItsMarkers() throws IOException {
    ClientViews.Link link = connect("codex", "helper");
    String ours = Files.readString(codexConfig);
    String end = "# <<< GateRook · " + link.entryName();
    assertThat(ours).contains(end);
    String added = "[profiles.work]\nmodel = \"gpt-5\"\n";
    Files.writeString(codexConfig, ours.replace(end, added + end));

    ClientViews.Preview preview = clients.previewDisconnect(link.id());
    assertThat(preview.byHand()).isNull();
    ClientViews.Disconnected done = clients.disconnect(link.id(), preview.previewId());
    assertThat(done.entryRemoved()).isTrue();
    assertThat(Files.readString(codexConfig)).contains(added).doesNotContain(link.entryName())
        .doesNotContain("GateRook");
    assertThat(grantRevoked(link.id())).isTrue();
  }

  @Test
  void removeBridgeEndsEveryAccessAndNamesTheEntryItCouldntRemove() throws IOException {
    ClientViews.Link link = connect("claude-desktop", "config-bridge");
    String broken = Files.readString(claudeConfig).replaceFirst("\\}\\s*$", ",}\n");
    Files.writeString(claudeConfig, broken);

    ClientViews.BridgeRemoved removed = clients.removeBridge();
    assertThat(removed.byHand()).singleElement().satisfies(b -> {
      assertThat(b.file()).endsWith("claude_desktop_config.json");
      assertThat(b.entry()).isEqualTo(link.entryName());
    });
    assertThat(String.join(" ", removed.notes())).contains("remove the entry by hand");
    assertThat(grantRevoked(link.id())).isTrue();
    assertThat(Files.readString(claudeConfig)).isEqualTo(broken);
  }

  private static ClientViews.Link connect(String client, String method) {
    ClientViews.Preview preview = clients.previewConnect(client,
        new ClientService.ConnectRequest("dev", method, null, null, null));
    return clients.connect(client, preview.previewId()).link();
  }

  private static boolean grantRevoked(String linkId) {
    return core.jdbc().queryForObject("select count(*) from client_grant g join client_link l on l.grant_id = g.id "
        + "where l.id = ? and g.revoked_at is not null", Integer.class, linkId) == 1;
  }
}
