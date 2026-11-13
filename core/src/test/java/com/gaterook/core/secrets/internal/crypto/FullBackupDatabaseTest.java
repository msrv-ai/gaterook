package com.gaterook.core.secrets.internal.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.gaterook.connector.api.Env;
import com.gaterook.core.connections.internal.ConnectionRows;
import com.gaterook.core.connections.internal.persistence.ConnectionEntity;
import com.gaterook.core.connections.internal.persistence.ConnectionRepository;
import com.gaterook.core.platform.HmacKeys;
import com.gaterook.core.platform.RowMac;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.subtle.Hkdf;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema gate of the restore validator on scratch SQLite files built by the production migrations: a backup must
 * have exactly the live schema, and only SQLite's own bookkeeping may differ.
 */
class FullBackupDatabaseTest {
  private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
  private static final String REFUSAL = "requires the same GateRook database version";

  @TempDir static Path shared;
  private static Path live;
  private static KeysetHandle master;

  @TempDir Path dir;
  private byte[] root;

  @BeforeAll
  static void migrate() throws Exception {
    live = Files.createDirectory(shared.resolve("live")).resolve("gateway.db");
    Flyway.configure().dataSource("jdbc:sqlite:" + live, null, null).locations("classpath:db/migration").load()
        .migrate();
    master = Keysets.generate();
  }

  @Test
  void acceptsACompleteBackupOfTheLiveSchemaAndSanitizesIt() throws Exception {
    Path backup = backup("backup");

    FullBackupDatabase.prepare(backup, live, master, NOW);

    assertThat(query(backup, "SELECT value FROM setting WHERE key='general'")).contains("\"openAtLogin\":false");
  }

  /** The statistics appear only after ANALYZE, so one installation can have them and another not. */
  @Test
  void ignoresStatisticsTablesThatOnlyTheBackupHas() throws Exception {
    Path backup = backup("backup");
    execute(backup, "ANALYZE");
    assertThat(query(backup, "SELECT count(*) FROM sqlite_schema WHERE name='sqlite_stat1'")).isEqualTo("1");

    FullBackupDatabase.prepare(backup, live, master, NOW);

    assertThat(query(backup, "SELECT value FROM setting WHERE key='general'")).contains("\"openAtLogin\":false");
  }

  @Test
  void ignoresStatisticsTablesThatOnlyTheLiveDatabaseHas() throws Exception {
    Path analyzed = Files.createDirectory(dir.resolve("analyzed")).resolve("gateway.db");
    Files.copy(live, analyzed);
    execute(analyzed, "ANALYZE");
    assertThat(query(analyzed, "SELECT count(*) FROM sqlite_schema WHERE name='sqlite_stat1'")).isEqualTo("1");
    Path backup = backup("backup");

    FullBackupDatabase.prepare(backup, analyzed, master, NOW);

    assertThat(query(backup, "SELECT value FROM setting WHERE key='general'")).contains("\"openAtLogin\":false");
  }

  @Test
  void exemptsOnlyTheObjectsSqliteCreatesItself() {
    assertThat(exempt("table", "sqlite_sequence", "CREATE TABLE sqlite_sequence(name,seq)")).isTrue();
    for (String statistics : List.of("sqlite_stat1", "sqlite_stat3", "sqlite_stat4")) {
      assertThat(exempt("table", statistics, "CREATE TABLE " + statistics + "(tbl,idx,stat)")).isTrue();
    }
    assertThat(exempt("index", "sqlite_autoindex_setting_1", null)).isTrue();

    assertThat(exempt("trigger", "sqlite_sequence", "CREATE TRIGGER sqlite_sequence AFTER INSERT ON setting "
        + "BEGIN SELECT 1; END")).as("kind").isFalse();
    assertThat(exempt("view", "sqlite_stat1", "CREATE VIEW sqlite_stat1 AS SELECT key FROM setting")).isFalse();
    assertThat(exempt("trigger", "sqlite_autoindex_setting_1", null)).isFalse();
    assertThat(exempt("index", "sqlite_autoindex_setting_1", "CREATE INDEX x ON setting(key)"))
        .as("a definition").isFalse();
    assertThat(exempt("table", "sqlite_extra", "CREATE TABLE sqlite_extra(value)")).as("name").isFalse();
    assertThat(exempt("table", "SQLITE_SEQUENCE", "CREATE TABLE SQLITE_SEQUENCE(name,seq)")).isFalse();
    assertThat(exempt("table", "sqlite1sequence", "CREATE TABLE sqlite1sequence(name,seq)")).isFalse();
    assertThat(exempt("index", "sqlite1autoindex_setting_1", null)).isFalse();
    assertThat(exempt("index", "SQLITE_AUTOINDEX_setting_1", null)).isFalse();
    assertThat(exempt(null, "sqlite_sequence", null)).as("a row it can't read").isFalse();
    assertThat(exempt("table", null, null)).isFalse();
  }

  /**
   * The restore moves a server that starts with the core to "on first call" and signs its row again. The row was
   * signed by {@link ConnectionRows}, which signs {@code enabled: false} for a paused server, so the check and the new
   * signature need the same fields: before, a backup holding a paused server could not be restored at all.
   */
  @ParameterizedTest(name = "paused: {0}")
  @ValueSource(booleans = {true, false})
  void aServerThatStartsWithTheCoreIsSignedAgainAsItsOwnerVerifiesItPausedOrNot(boolean paused) throws Exception {
    Path backup = backup("backup");
    ConnectionRows owner = new ConnectionRows(rowMac(root), mock(ConnectionRepository.class),
        JsonMapper.builder().build());
    ConnectionEntity server = new ConnectionEntity("backup-mcp", "mcp-server", Env.DEV,
        Map.of("transport", "stdio", "start", "with-core"), NOW);
    if (paused) {
      server.setEnabled(false, NOW);
    }
    owner.sign(server);
    execute(backup, "INSERT INTO connector_type(id, kind, impl_version, manifest, manifest_sha256, enabled, "
        + "updated_at, version) VALUES ('mcp-server', 'MCP_SERVER', '1.0.0', '{}', '" + "0".repeat(64) + "', 1, 1, 0)");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup);
        PreparedStatement s = c.prepareStatement("INSERT INTO connection(id, name, type, env, enabled, config, "
            + "created_at, updated_at, mac, version) VALUES (?, 'backup-mcp', 'mcp-server', 'dev', ?, ?, 1, 1, ?, 0)")) {
      s.setString(1, server.getId());
      s.setBoolean(2, !paused);
      s.setString(3, JsonMapper.builder().build().writeValueAsString(server.getConfig()));
      s.setBytes(4, server.getMac());
      s.executeUpdate();
    }

    FullBackupDatabase.prepare(backup, live, master, NOW);

    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup); Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT enabled, config, version, mac FROM connection")) {
      assertThat(r.next()).isTrue();
      assertThat(r.getBoolean("enabled")).as("a paused server stays paused").isEqualTo(!paused);
      assertThat(r.getString("config")).contains("\"start\":\"on-first-call\"");
      server.reconfigure(Env.DEV, Map.of("transport", "stdio", "start", "on-first-call"), NOW);
      ReflectionTestUtils.setField(server, "version", r.getLong("version"));
      server.setMac(r.getBytes("mac"));
    }
    assertThat(owner.verify(server)).as("the owner verifies the new signature").isTrue();
  }

  /**
   * In a LIKE pattern an underscore matches any character, and SQLite creates no object named {@code sqlite_…} itself
   * but loads one from an edited file, so no name pattern may stand in for "SQLite's own".
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("objectsTheLiveSchemaLacks")
  void refusesABackupWithAnObjectTheLiveSchemaLacks(Craft craft) throws Exception {
    Path backup = backup("backup");
    execute(backup, craft.statements().toArray(String[]::new));
    if (craft.from() != null) {
      rename(backup, craft.from(), craft.to());
    }
    byte[] before = Files.readAllBytes(backup);

    assertThatThrownBy(() -> FullBackupDatabase.prepare(backup, live, master, NOW))
        .isInstanceOf(IOException.class).hasMessageContaining(REFUSAL);
    assertThat(Files.readAllBytes(backup)).as("a refused backup is left as it came").isEqualTo(before);
  }

  static Stream<Craft> objectsTheLiveSchemaLacks() {
    String trigger = "AFTER INSERT ON setting BEGIN SELECT 1; END";
    String view = "AS SELECT key FROM setting";
    return Stream.of(
        Craft.of("a trigger named sqlite and a letter", "CREATE TRIGGER sqlite1extra " + trigger),
        Craft.of("a view named sqlite and a letter", "CREATE VIEW sqliteXextra " + view),
        Craft.of("a trigger with an ordinary name", "CREATE TRIGGER extra_trigger " + trigger),
        Craft.of("a view with an ordinary name", "CREATE VIEW extra_view " + view),
        Craft.of("a table with an ordinary name", "CREATE TABLE extra_table(value TEXT)"),
        Craft.of("an index with an ordinary name", "CREATE INDEX extra_index ON setting(updated_at)"),
        Craft.of("a trigger under a reserved name", "CREATE TRIGGER extra_trigger " + trigger)
            .renamed("extra_trigger", "sqlite_extra"),
        Craft.of("a trigger under a reserved upper-case name", "CREATE TRIGGER extra_trigger " + trigger)
            .renamed("extra_trigger", "SQLITE_EXTRA"),
        Craft.of("a view under a reserved name", "CREATE VIEW extra_view " + view)
            .renamed("extra_view", "sqlite_extra"),
        Craft.of("a table named like a statistics table", "CREATE TABLE extra_table(value TEXT)")
            .renamed("extra_table", "sqlite_stat9"),
        Craft.of("an index named like an automatic one", "CREATE INDEX extra_index ON setting(updated_at)")
            .renamed("extra_index", "sqlite_autoindex_setting_9"),
        Craft.of("a changed definition of a live trigger", "DROP TRIGGER template_version_no_update",
            "CREATE TRIGGER template_version_no_update BEFORE UPDATE ON template_version BEGIN SELECT 1; END"),
        Craft.of("a live trigger that is missing", "DROP TRIGGER template_version_no_update"));
  }

  /** Statements that make a backup differ from the live schema, and optionally the rename of one object in it. */
  private record Craft(String description, List<String> statements, String from, String to) {
    static Craft of(String description, String... statements) {
      return new Craft(description, List.of(statements), null, null);
    }

    Craft renamed(String from, String to) {
      return new Craft(description, statements, from, to);
    }

    @Override public String toString() {
      return description;
    }
  }

  /** The row MAC a restore of a backup sealed with {@code root} signs with, derived as the validator derives it. */
  private static RowMac rowMac(byte[] root) throws Exception {
    byte[] key = Hkdf.computeHkdf("HMACSHA256", root, new byte[0],
        HmacKeys.Purpose.ROW_MAC.info().getBytes(StandardCharsets.UTF_8), 32);
    return new RowMac((purpose, data) -> {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }, JsonMapper.builder().build());
  }

  /**
   * A complete backup of {@link #live}: its copy with the sealed integrity root that a real snapshot carries, kept in
   * {@link #root} for tests that sign rows of their own.
   */
  private Path backup(String name) throws Exception {
    Path file = Files.createDirectory(dir.resolve(name)).resolve("gateway.db");
    Files.copy(live, file);
    root = new byte[32];
    new SecureRandom().nextBytes(root);
    Envelope.Sealed sealed = Envelope.seal(master, "mac-root", 1, root);
    String value = "{\"v\":1,\"keyId\":" + sealed.keyId() + ",\"dek\":\""
        + Base64.getEncoder().encodeToString(sealed.wrappedDek()) + "\",\"ct\":\""
        + Base64.getEncoder().encodeToString(sealed.ciphertext()) + "\"}";
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        PreparedStatement s = c.prepareStatement(
            "INSERT INTO setting(key, value, version, updated_at) VALUES ('secrets.mac-root', ?, 0, 1)")) {
      s.setString(1, value);
      s.executeUpdate();
    }
    return file;
  }

  private static boolean exempt(String type, String name, String sql) {
    return FullBackupDatabase.bookkeeping(Arrays.asList(type, name, "setting", sql));
  }

  private static void execute(Path file, String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
      for (String sql : statements) {
        s.execute(sql);
      }
    }
  }

  /** Renames an object in the schema table, as an edited file can: SQLite refuses the name in a CREATE statement. */
  private static void rename(Path file, String from, String to) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
      s.execute("PRAGMA writable_schema=ON");
      try (PreparedStatement rename = c.prepareStatement("UPDATE sqlite_schema SET name=?, "
          + "tbl_name=CASE WHEN type IN ('table', 'view') THEN ? ELSE tbl_name END, sql=replace(sql, ?, ?) "
          + "WHERE name=?")) {
        rename.setString(1, to);
        rename.setString(2, to);
        rename.setString(3, from);
        rename.setString(4, to);
        rename.setString(5, from);
        assertThat(rename.executeUpdate()).isEqualTo(1);
      }
      s.execute("PRAGMA writable_schema=OFF");
    }
    assertThat(query(file, "SELECT count(*) FROM sqlite_schema WHERE name='" + to + "'")).isEqualTo("1");
  }

  private static String query(Path file, String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      while (r.next()) {
        values.add(r.getString(1));
      }
    }
    assertThat(values).hasSize(1);
    return values.get(0);
  }
}
