package ai.msrv.gaterook.core.secrets.internal.crypto;

import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.RowMac;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.subtle.Hkdf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validates a detached snapshot and invalidates authorizations before it can replace live data. */
public final class FullBackupDatabase {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long MAX_DATABASE_BYTES = 1L << 30;
  private static final String SCHEMA = "SELECT type, name, tbl_name, sql FROM sqlite_schema "
      + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name";
  private static final String HISTORY = "SELECT version, description, type, script, checksum, success "
      + "FROM flyway_schema_history ORDER BY installed_rank";

  private FullBackupDatabase() {}

  public record PluginFile(String path, String sha256) {}

  public static void prepare(Path source, Path current, KeysetHandle master, Instant now)
      throws IOException, GeneralSecurityException {
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSameFile(source, current)
        || Files.size(source) > MAX_DATABASE_BYTES
        || Files.size(source) < 512) {
      throw new IOException("The backup database is missing or exceeds the 1 GiB limit.");
    }
    for (String suffix : List.of("-wal", "-shm", "-journal")) {
      if (Files.exists(source.resolveSibling(source.getFileName() + suffix), LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("The backup database is not a self-contained snapshot.");
      }
    }
    byte[] root = null;
    byte[] rowKey = null;
    try {
      try (Connection input = open(source, true); Connection reference = open(current, true)) {
        if (!rows(input, SCHEMA).equals(rows(reference, SCHEMA))
            || !rows(input, HISTORY).equals(rows(reference, HISTORY))) {
          throw new IOException("This backup requires the same GateRook database version that created it.");
        }
        try (Statement s = input.createStatement(); ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
          if (!r.next() || !"ok".equals(r.getString(1)) || r.next()) {
            throw new IOException("The backup database failed its integrity check.");
          }
        }
        try (Statement s = input.createStatement(); ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) {
          if (r.next()) throw new IOException("The backup database has broken references.");
        }
        verifySecrets(input, master);
        root = purposeRoot(input, master);
      }
      verifyPluginFiles(source.getParent(), pluginFiles(source, now));
      rowKey = Hkdf.computeHkdf("HMACSHA256", root, new byte[0],
          HmacKeys.Purpose.ROW_MAC.info().getBytes(StandardCharsets.UTF_8), 32);
      byte[] signingKey = rowKey;
      RowMac rows = new RowMac((purpose, data) -> {
        try {
          Mac mac = Mac.getInstance("HmacSHA256");
          mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
          return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
          throw new IllegalStateException("Can't authenticate the restored authorization rows.", e);
        }
      }, JSON);
      sanitize(source, rows, now.toEpochMilli());
    } catch (SQLException | RuntimeException e) {
      throw new IOException("The backup database is damaged or unsupported.", e);
    } finally {
      if (root != null) Arrays.fill(root, (byte) 0);
      if (rowKey != null) Arrays.fill(rowKey, (byte) 0);
    }
  }

  /** Required immutable plugin generations from the database snapshot, never from the changing live registry. */
  public static List<PluginFile> pluginFiles(Path database, Instant now) throws IOException {
    List<PluginFile> files = new ArrayList<>();
    try (Connection c = open(database, true); Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT id,current_version,sha256,previous_version,previous_sha256,"
            + "previous_kept_until FROM plugin ORDER BY id")) {
      while (r.next()) {
        files.add(pluginFile(r.getString("id"), r.getString("current_version"), r.getString("sha256")));
        if (r.getString("previous_version") != null && (r.getObject("previous_kept_until") == null
            || now.toEpochMilli() < r.getLong("previous_kept_until"))) {
          files.add(pluginFile(r.getString("id"), r.getString("previous_version"), r.getString("previous_sha256")));
        }
      }
    } catch (SQLException e) {
      throw new IOException("The backup's plugin inventory cannot be read.", e);
    }
    return List.copyOf(files);
  }

  private static PluginFile pluginFile(String id, String version, String sha) throws IOException {
    if (id == null || !id.matches("[a-z][a-z0-9-]{0,47}") || version == null
        || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}") || sha == null
        || !sha.matches("[a-fA-F0-9]{64}")) {
      throw new IOException("The backup contains invalid plugin metadata.");
    }
    return new PluginFile("plugins/" + id + "/" + version + "/plugin.jar", sha.toLowerCase(Locale.ROOT));
  }

  private static void verifyPluginFiles(Path directory, List<PluginFile> files)
      throws IOException, GeneralSecurityException {
    for (PluginFile expected : files) {
      Path file = directory;
      for (String segment : expected.path().split("/")) {
        file = file.resolve(segment);
        if (Files.isSymbolicLink(file)) throw new IOException("The backup contains a linked plugin file.");
      }
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("The backup is missing a plugin generation required by its database.");
      }
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file)) {
        byte[] buffer = new byte[64 * 1024];
        for (int length; (length = input.read(buffer)) != -1;) sha.update(buffer, 0, length);
      }
      if (!HexFormat.of().formatHex(sha.digest()).equals(expected.sha256())) {
        throw new IOException("A plugin file does not match the backup database. Create a new full backup.");
      }
    }
  }

  private static Connection open(Path path, boolean readOnly) throws SQLException {
    SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(readOnly);
    config.enableLoadExtension(false);
    config.setBusyTimeout(5_000);
    Connection c = config.createConnection("jdbc:sqlite:" + path.toAbsolutePath());
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA trusted_schema=OFF");
      s.execute("PRAGMA foreign_keys=ON");
      if (!readOnly) {
        s.execute("PRAGMA journal_mode=DELETE");
        s.execute("PRAGMA synchronous=FULL");
      }
    } catch (SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  private static List<List<String>> rows(Connection c, String sql) throws SQLException {
    List<List<String>> values = new ArrayList<>();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
      while (r.next()) {
        List<String> row = new ArrayList<>();
        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getString(i));
        values.add(row);
      }
    }
    return values;
  }

  private static void verifySecrets(Connection c, KeysetHandle master) throws SQLException, GeneralSecurityException {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT secret_id, secret_version, "
        + "ciphertext, wrapped_dek, enc_format FROM secret_version "
        + "WHERE ciphertext IS NOT NULL OR wrapped_dek IS NOT NULL")) {
      while (r.next()) {
        byte[] ciphertext = r.getBytes("ciphertext");
        byte[] wrapped = r.getBytes("wrapped_dek");
        if (r.getInt("enc_format") != Envelope.FORMAT || ciphertext == null || wrapped == null) {
          throw new GeneralSecurityException("A secret in the backup uses an unsupported encryption format.");
        }
        byte[] plain = Envelope.open(master, r.getString("secret_id"), r.getInt("secret_version"), ciphertext, wrapped);
        Arrays.fill(plain, (byte) 0);
      }
    }
  }

  private static byte[] purposeRoot(Connection c, KeysetHandle master) throws SQLException, GeneralSecurityException {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
        "SELECT value FROM setting WHERE key='secrets.mac-root'")) {
      if (!r.next()) throw new GeneralSecurityException("The backup's integrity key is missing.");
      JsonNode value = JSON.readTree(r.getString(1));
      byte[] root = Envelope.open(master, "mac-root", value.path("v").asInt(1),
          Base64.getDecoder().decode(value.path("ct").asString()),
          Base64.getDecoder().decode(value.path("dek").asString()));
      if (root.length != 32) {
        Arrays.fill(root, (byte) 0);
        throw new GeneralSecurityException("The backup's integrity key is invalid.");
      }
      return root;
    }
  }

  private static void sanitize(Path file, RowMac mac, long now) throws SQLException, IOException {
    try (Connection c = open(file, false)) {
      c.setAutoCommit(false);
      try {
        revokeGrants(c, mac, now);
        removeTemporaryBypass(c, mac, now);
        disablePlugins(c, mac, now);
        stopAutomaticMcp(c, mac, now);
        disableLoginStartup(c, now);
        update(c, "UPDATE api_token SET revoked_at=?, revoke_reason='Full backup restored', version=version+1 "
            + "WHERE revoked_at IS NULL", now);
        update(c, "UPDATE oauth_grant SET state='revoked', revoked_at=?, revoke_reason='Full backup restored', "
            + "version=version+1 WHERE revoked_at IS NULL", now);
        update(c, "DELETE FROM oauth_token");
        update(c, "DELETE FROM notify_pairing");
        update(c, "UPDATE approval SET status='cancelled', error='Cancelled by full backup restore', "
            + "prepared_state=NULL, result_masked=NULL, version=version+1 WHERE status IN ('pending','running')");
        update(c, "UPDATE notify_delivery SET state='resolved', resolved_at=?, version=version+1 "
            + "WHERE state IN ('sending','sent')", now);
        update(c, "UPDATE client_link SET status='removed', removed_at=?, version=version+1 "
            + "WHERE removed_at IS NULL", now);
        c.commit();
      } catch (SQLException | IOException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static void revokeGrants(Connection c, RowMac mac, long now) throws SQLException, IOException {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM client_grant WHERE revoked_at IS NULL")) {
      while (r.next()) {
        ObjectNode f = mac.fields();
        f.put("client", r.getString("client"));
        f.put("endpointId", r.getString("endpoint_id"));
        f.put("label", r.getString("label"));
        f.put("createdAt", r.getLong("created_at"));
        f.putNull("revokedAt");
        if (r.getObject("confirmed_at") != null) f.put("confirmedAt", r.getLong("confirmed_at"));
        verified(mac, "client_grant", r, f);
        f.put("revokedAt", now);
        update(c, "UPDATE client_grant SET revoked_at=?, version=version+1, mac=? WHERE id=?", now,
            mac.sign("client_grant", r.getString("id"), r.getLong("version") + 1, f), r.getString("id"));
      }
    }
  }

  private static void removeTemporaryBypass(Connection c, RowMac mac, long now) throws SQLException, IOException {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM endpoint_tool WHERE temporary_bypass IS NOT NULL")) {
      while (r.next()) {
        ObjectNode f = mac.fields();
        f.put("endpointId", r.getString("endpoint_id"));
        f.put("connectionId", r.getString("connection_id"));
        f.put("toolKey", r.getString("tool_key"));
        f.put("mode", r.getString("mode"));
        f.set("overrides", tree(r, "overrides"));
        f.set("roConfirmation", tree(r, "ro_confirmation"));
        f.set("temporaryBypass", tree(r, "temporary_bypass"));
        verified(mac, "endpoint_tool", r, f);
        f.remove("temporaryBypass");
        update(c, "UPDATE endpoint_tool SET temporary_bypass=NULL, updated_at=?, version=version+1, mac=? WHERE id=?",
            now, mac.sign("endpoint_tool", r.getString("id"), r.getLong("version") + 1, f), r.getString("id"));
      }
    }
  }

  private static void disablePlugins(Connection c, RowMac mac, long now) throws SQLException, IOException {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM plugin WHERE state='installed'")) {
      while (r.next()) {
        ObjectNode f = mac.fields();
        f.put("currentVersion", r.getString("current_version"));
        f.put("previousVersion", r.getString("previous_version"));
        f.put("state", "INSTALLED");
        f.put("signed", r.getBoolean("signed"));
        f.put("source", r.getString("source").toUpperCase(Locale.ROOT));
        f.put("sha256", r.getString("sha256"));
        f.put("sizeBytes", r.getLong("size_bytes"));
        f.put("catalogEntry", r.getString("catalog_entry"));
        if (r.getString("previous_sha256") != null) f.put("previousSha256", r.getString("previous_sha256"));
        verified(mac, "plugin", r, f);
        f.put("state", "DISABLED");
        update(c, "UPDATE plugin SET state='disabled', updated_at=?, version=version+1, mac=? WHERE id=?", now,
            mac.sign("plugin", r.getString("id"), r.getLong("version") + 1, f), r.getString("id"));
      }
    }
  }

  private static void stopAutomaticMcp(Connection c, RowMac mac, long now) throws SQLException, IOException {
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM connection WHERE type='mcp-server'")) {
      while (r.next()) {
        JsonNode config = tree(r, "config");
        if (!"with-core".equals(config.path("start").asString(null))) continue;
        ObjectNode f = mac.fields();
        f.put("name", r.getString("name"));
        f.put("type", r.getString("type"));
        f.put("env", r.getString("env"));
        f.set("config", config);
        f.set("metadata", tree(r, "metadata"));
        verified(mac, "connection", r, f);
        ((ObjectNode) config).put("start", "on-first-call");
        update(c, "UPDATE connection SET config=?, updated_at=?, version=version+1, mac=? WHERE id=?",
            JSON.writeValueAsString(config), now,
            mac.sign("connection", r.getString("id"), r.getLong("version") + 1, f), r.getString("id"));
      }
    }
  }

  private static void disableLoginStartup(Connection c, long now) throws SQLException {
    ObjectNode general = JSON.createObjectNode();
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT value FROM setting WHERE key='general'")) {
      if (r.next()) general = (ObjectNode) JSON.readTree(r.getString(1));
    }
    general.put("openAtLogin", false);
    general.put("startHidden", false);
    update(c, "INSERT INTO setting(key,value,version,updated_at) VALUES ('general',?,0,?) "
        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value, version=setting.version+1, updated_at=excluded.updated_at",
        JSON.writeValueAsString(general), now);
  }

  private static JsonNode tree(ResultSet r, String column) throws SQLException {
    String value = r.getString(column);
    return value == null ? JSON.nullNode() : JSON.readTree(value);
  }

  private static void verified(RowMac mac, String table, ResultSet r, ObjectNode fields) throws SQLException, IOException {
    if (!mac.verify(table, r.getString("id"), r.getLong("version"), fields, r.getBytes("mac"))) {
      throw new IOException("The backup contains authorization settings changed outside GateRook. "
          + "Review them in the original installation before backing it up again.");
    }
  }

  private static void update(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      s.executeUpdate();
    }
  }
}
