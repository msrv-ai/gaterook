package ai.msrv.gaterook.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.connections.internal.ConnectionRows;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionEntity;
import ai.msrv.gaterook.core.connections.internal.persistence.ConnectionRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.ConnectorTypeRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginEntity;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginRepository;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginSource;
import ai.msrv.gaterook.core.connectors.internal.persistence.PluginState;
import ai.msrv.gaterook.core.connectors.plugins.PluginStore;
import ai.msrv.gaterook.core.endpoints.ClientGrants;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.core.secrets.internal.crypto.BackupCodec;
import ai.msrv.gaterook.core.secrets.internal.crypto.KeysetFile;
import ai.msrv.gaterook.testfixtures.TestCore;
import com.google.crypto.tink.subtle.AesGcmJce;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovery across installations must preserve encrypted data while invalidating old authorizations. */
class FullBackupIT {
  @Test
  void aPairedSnapshotRestoresSecretsAndKeepsRevokedRowsAuthenticated() throws Exception {
    Path source;
    byte[] portable;
    String grant;
    String token;
    try (TestCore core = TestCore.start("full-backup-source")) {
      var connection = core.connection("backup-db", "pgfake", Env.DEV, "db.test");
      var endpoints = core.bean(EndpointService.class);
      var endpoint = endpoints.create("backup-dev", null, EndpointService.StartFrom.EMPTY, null);
      core.bean(EndpointConnections.class).add("backup-dev", "backup-db", null);
      var pair = endpoints.snapshot("backup-dev").pairs().stream()
          .filter(p -> p.toolKey().equals("sql_execute")).findFirst().orElseThrow();
      core.bean(EndpointTools.class).setTemporaryBypass("backup-dev", "sql_execute", "backup-db", 30,
          pair.rowVersion(), null);
      grant = core.bean(ClientGrants.class).create(Ids.next("gr_"), "codex", "backup-dev", "backup-client", null).id();
      token = core.bean(TokenService.class).issue("backup-dev", "backup-token", 7, null).id();
      core.pendingApproval(endpoint.id(), "backup-dev", connection.id(), "backup-db", "sql_execute", token);
      source = stage(core, "source1");
      portable = core.bean(FullBackupService.class).snapshot(source.toString());
      assertThat(core.bean(ClientGrants.class).find(grant).orElseThrow().active()).isTrue();
    }

    Path targetHome;
    try (TestCore target = TestCore.start("full-backup-target")) {
      targetHome = target.home();
      Path staged = stage(target, "restore1");
      Files.copy(source.resolve("gateway.db"), staged.resolve("gateway.db"));
      byte[] protectedKey = target.bean(FullBackupService.class).validate(staged.toString(), portable);
      Files.write(staged.resolve("master.keyset"), protectedKey);
      Arrays.fill(protectedKey, (byte) 0);
      assertThat(target.jdbc().queryForObject("SELECT count(*) FROM connection", Integer.class)).isZero();
    }
    // The desktop performs this replacement only after the core has closed all connections.
    Path staged = targetHome.resolve("tmp/full-backup-restore1");
    Files.copy(staged.resolve("gateway.db"), targetHome.resolve("gateway.db"), StandardCopyOption.REPLACE_EXISTING);
    Files.delete(targetHome.resolve("master.keyset"));
    Files.copy(staged.resolve("master.keyset"), targetHome.resolve("master.keyset"));
    try (TestCore restored = TestCore.reopen(targetHome)) {
      assertThat(restored.bean(MasterKeyLock.class).locked()).isFalse();
      var restoredGrant = restored.bean(ClientGrants.class).find(grant).orElseThrow();
      assertThat(restoredGrant.verified()).isTrue();
      assertThat(restoredGrant.revokedAt()).isNotNull();
      assertThat(restored.jdbc().queryForObject("SELECT revoked_at FROM api_token WHERE id=?", Long.class, token)).isNotNull();
      assertThat(restored.jdbc().queryForObject("SELECT status FROM approval WHERE principal_id=?", String.class, token)).isEqualTo("cancelled");
      var endpoint = restored.bean(EndpointService.class).snapshot("backup-dev");
      assertThat(endpoint.blocked()).isFalse();
      assertThat(endpoint.pairs().stream().filter(p -> p.toolKey().equals("sql_execute"))).singleElement()
          .satisfies(p -> assertThat(p.bypassUntil()).isNull());
      assertThat(restored.bean(MasterKeyService.class).status().protects()).isPositive();
      assertThat(restored.bean(SettingsService.class).value("general", "openAtLogin").asBoolean()).isFalse();
      assertThat(restored.bean(SettingsService.class).value("general", "startHidden").asBoolean()).isFalse();
    } finally {
      Arrays.fill(portable, (byte) 0);
    }
  }

  @Test
  void internalEndpointsRequireAuthenticationAndValidationWorksWhileTheLiveKeyIsLocked() throws Exception {
    try (TestCore core = TestCore.start("full-backup-internal")) {
      AdminMvc api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
      Path directory = stage(core, "internal");
      Map<String, String> body = Map.of("directory", directory.toString());
      assertThat(api.sendUnsigned(HttpMethod.POST, "/internal/full-backup/snapshot", body).status()).isEqualTo(401);
      api.expect(404, HttpMethod.POST, "/api/full-backup/snapshot", body);
      var snapshot = api.expect(200, HttpMethod.POST, "/internal/full-backup/snapshot", body);
      String keyset = snapshot.path("keyset").asString();
      assertThat(keyset).isNotEmpty();
      var master = core.bean(MasterKeyLock.class);
      master.lock(MasterKeyLock.Reason.MASTER_KEY_MISSING);
      api.expect(503, HttpMethod.POST, "/internal/full-backup/snapshot", body);
      api.expect(200, HttpMethod.POST, "/internal/full-backup/validate",
          Map.of("directory", directory.toString(), "keyset", keyset));
      assertThat(master.locked()).as("preparation never changes the live installation").isTrue();
    }
  }

  @Test
  void neverResignsTamperedAuthorizationRows() throws Exception {
    try (TestCore core = TestCore.start("full-backup-tampered-row")) {
      core.bean(EndpointService.class).create("backup-dev", null, EndpointService.StartFrom.EMPTY, null);
      String grant = core.bean(ClientGrants.class)
          .create(Ids.next("gr_"), "codex", "backup-dev", "original-label", null).id();
      Path directory = stage(core, "tampered");
      FullBackupService service = core.bean(FullBackupService.class);
      byte[] portable = service.snapshot(directory.toString());
      try {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("gateway.db"));
            var s = c.prepareStatement("UPDATE client_grant SET label='forged-label' WHERE id=?")) {
          s.setString(1, grant);
          s.executeUpdate();
        }
        assertThatThrownBy(() -> service.validate(directory.toString(), portable))
            .hasMessageContaining("changed outside GateRook");
        assertThat(core.bean(ClientGrants.class).find(grant).orElseThrow().active()).isTrue();
      } finally {
        Arrays.fill(portable, (byte) 0);
      }
    }
  }

  @Test
  void requiresMatchingPluginFilesAndSafelyResignsDisabledPluginAndMcpStartupSettings() throws Exception {
    try (TestCore core = TestCore.start("full-backup-plugin-inventory")) {
      byte[] jar = "fixture plugin contents".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
      Instant now = Instant.now();
      PluginStore store = core.bean(PluginStore.class);
      ConnectionRows connections = core.bean(ConnectionRows.class);
      TransactionTemplate tx = new TransactionTemplate(core.bean(PlatformTransactionManager.class));
      PluginEntity plugin = tx.execute(status -> {
        PluginEntity row = core.bean(PluginRepository.class).saveAndFlush(new PluginEntity("backup-plugin", "1.0.0",
            false, PluginSource.FILE, sha, jar.length, Map.of(), now));
        ReflectionTestUtils.invokeMethod(store, "sign", row);
        return row;
      });
      ConnectionEntity mcp = tx.execute(status -> {
        core.bean(ConnectorTypeRepository.class).saveAndFlush(new ConnectorTypeEntity("mcp-server",
            ConnectorManifest.Kind.MCP_SERVER, "1.0.0", Map.of(), sha, null, now));
        ConnectionEntity row = core.bean(ConnectionRepository.class).saveAndFlush(new ConnectionEntity("backup-mcp",
            "mcp-server", Env.DEV, Map.of("transport", "stdio", "start", "with-core"), now));
        connections.sign(row);
        return row;
      });
      Path directory = stage(core, "plugins1");
      FullBackupService service = core.bean(FullBackupService.class);
      byte[] portable = service.snapshot(directory.toString());
      try {
        assertThat(service.pluginFiles(directory.toString())).containsExactly(new FullBackupService.PluginFile(
            "plugins/backup-plugin/1.0.0/plugin.jar", sha));
        assertThatThrownBy(() -> service.validate(directory.toString(), portable)).hasMessageContaining("missing a plugin");
        Path artifact = directory.resolve("plugins/backup-plugin/1.0.0/plugin.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "wrong contents");
        assertThatThrownBy(() -> service.validate(directory.toString(), portable)).hasMessageContaining("does not match");
        Files.write(artifact, jar);
        byte[] protectedKey = service.validate(directory.toString(), portable);
        Arrays.fill(protectedKey, (byte) 0);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("gateway.db"));
            var s = c.createStatement()) {
          try (var r = s.executeQuery("SELECT state,version,mac FROM plugin WHERE id='backup-plugin'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString("state")).isEqualTo("disabled");
            plugin.setState(PluginState.DISABLED, now);
            ReflectionTestUtils.setField(plugin, "version", r.getLong("version"));
            plugin.setMac(r.getBytes("mac"));
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(store, "verify", plugin)).isTrue();
          }
          try (var r = s.executeQuery("SELECT config,version,mac FROM connection WHERE name='backup-mcp'")) {
            assertThat(r.next()).isTrue();
            assertThat(TestCore.json(r.getString("config")).path("start").asString()).isEqualTo("on-first-call");
            mcp.reconfigure(Env.DEV, Map.of("transport", "stdio", "start", "on-first-call"), now);
            ReflectionTestUtils.setField(mcp, "version", r.getLong("version"));
            mcp.setMac(r.getBytes("mac"));
            assertThat(connections.verify(mcp)).isTrue();
          }
        }
      } finally {
        Arrays.fill(portable, (byte) 0);
      }
    }
  }

  @Test
  void rejectsWrongKeysCorruptionSchemaChangesAndUnsafeStagingWithoutTouchingLiveData() throws Exception {
    try (TestCore core = TestCore.start("full-backup-invalid")) {
      core.connection("backup-db", "pgfake", Env.DEV, "db.test");
      FullBackupService service = core.bean(FullBackupService.class);
      Path original = stage(core, "original");
      byte[] portable = service.snapshot(original.toString());
      try {
        byte[] wrongKey;
        try (TestCore other = TestCore.start("full-backup-wrong-key")) {
          wrongKey = other.bean(FullBackupService.class).snapshot(stage(other, "wrongkey").toString());
        }
        assertThatThrownBy(() -> service.validate(original.toString(), wrongKey))
            .isInstanceOf(java.security.GeneralSecurityException.class);
        Arrays.fill(wrongKey, (byte) 0);
        Path corrupt = stage(core, "corrupt1");
        Files.writeString(corrupt.resolve("gateway.db"), "broken");
        assertThatThrownBy(() -> service.validate(corrupt.toString(), portable)).isInstanceOf(java.io.IOException.class);
        Path changed = stage(core, "changed1");
        Files.copy(original.resolve("gateway.db"), changed.resolve("gateway.db"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + changed.resolve("gateway.db")); var s = c.createStatement()) {
          s.execute("CREATE TABLE unexpected(value TEXT)");
        }
        assertThatThrownBy(() -> service.validate(changed.toString(), portable)).hasMessageContaining("same GateRook database version");
        Path outside = Files.createDirectories(core.home().resolve("full-backup-outside"));
        assertThatThrownBy(() -> service.snapshot(outside.toString())).hasMessageContaining("outside");
        Path link = core.home().resolve("tmp/full-backup-symlink");
        Files.createSymbolicLink(link, outside);
        assertThatThrownBy(() -> service.snapshot(link.toString())).hasMessageContaining("outside");
        assertThat(core.bean(MasterKeyLock.class).locked()).isFalse();
        assertThat(core.jdbc().queryForObject("SELECT count(*) FROM connection", Integer.class)).isEqualTo(1);
      } finally {
        Arrays.fill(portable, (byte) 0);
      }
    }
  }

  @Test
  void restoreRewrapsThePortableKeyForTheDestinationProtectionKey() throws Exception {
    try (TestCore core = TestCore.start("full-backup-protection")) {
      Path staged = stage(core, "protect1");
      byte[] portable = core.bean(FullBackupService.class).snapshot(staged.toString());
      byte[] fileKey = new byte[32];
      new java.security.SecureRandom().nextBytes(fileKey);
      core.bean(MasterKeyService.class).protect(fileKey.clone());
      byte[] result = core.bean(FullBackupService.class).validate(staged.toString(), portable);
      try {
        Files.write(staged.resolve("master.keyset"), result);
        var loaded = new KeysetFile(staged).load(new AesGcmJce(fileKey));
        assertThat(loaded.state()).isEqualTo(KeysetFile.State.LOADED);
        assertThat(loaded.encrypted()).isTrue();
        assertThat(loaded.handle().getPrimary().getId()).isEqualTo(BackupCodec.read(portable, null).getPrimary().getId());
        assertThat(new KeysetFile(staged).load(new AesGcmJce(new byte[32])).state()).isEqualTo(KeysetFile.State.INVALID);
      } finally {
        Arrays.fill(portable, (byte) 0);
        Arrays.fill(fileKey, (byte) 0);
        Arrays.fill(result, (byte) 0);
      }
    }
  }

  private static Path stage(TestCore core, String name) throws Exception {
    return Files.createDirectory(core.home().resolve("tmp/full-backup-" + name));
  }
}
