package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test connection on MySQL and MariaDB (report 04 §5): {@code SHOW GRANTS} read line by line. MySQL 8 shows only the
 * grants of active roles, so the roles granted to the user are expanded with
 * {@code SHOW GRANTS FOR CURRENT_USER() USING …}; MariaDB shows a role's grants with {@code SHOW GRANTS FOR role}.
 */
final class MySqlProbe implements SqlProbe {

  private static final Pattern GRANT = Pattern.compile("^GRANT (.+?) ON (\\S+(?:\\.\\S+)?) TO ",
      Pattern.CASE_INSENSITIVE);
  /** {@code GRANT `r1`@`%`,`r2`@`%` TO …} (MySQL roles) or {@code GRANT `r1` TO …} (MariaDB). */
  private static final Pattern ROLE_GRANT = Pattern.compile("^GRANT ((?:`[^`]+`(?:@`[^`]*`)?\\s*,?\\s*)+) TO ",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern ROLE = Pattern.compile("`([^`]+)`(?:@`([^`]*)`)?");

  static final Set<String> WRITE = Set.of("INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "INDEX",
      "CREATE VIEW", "TRIGGER", "EVENT", "CREATE ROUTINE", "ALTER ROUTINE", "EXECUTE", "ALL PRIVILEGES", "ALL",
      "CREATE TEMPORARY TABLES", "REFERENCES", "LOCK TABLES", "DELETE HISTORY");
  static final Set<String> SCHEMA_CHANGES = Set.of("CREATE", "DROP", "ALTER", "INDEX", "CREATE VIEW", "TRIGGER",
      "EVENT", "CREATE ROUTINE", "ALTER ROUTINE", "ALL PRIVILEGES", "ALL");
  /** Privileges that reach the server's files or every session: a danger hint. */
  static final Set<String> DANGEROUS = Set.of("FILE", "SUPER", "SYSTEM_USER");
  /** Other administrative privileges: listed under admin. */
  static final Set<String> ADMIN = Set.of("PROCESS", "SHUTDOWN", "RELOAD", "CREATE USER", "GRANT OPTION",
      "REPLICATION SLAVE", "REPLICATION CLIENT", "CREATE TABLESPACE", "BINLOG ADMIN", "CONNECTION ADMIN",
      "READ_ONLY ADMIN", "REPLICATION SLAVE ADMIN", "REPLICA MONITOR", "SLAVE MONITOR", "BINLOG MONITOR");

  @Override
  public Tls tls(Connection c, ServerInfo server) throws SQLException {
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_version'")) {
      if (rs.next()) {
        String version = rs.getString(2);
        boolean on = version != null && !version.isBlank();
        return new Tls(on, on ? version : null);
      }
      return new Tls(false, null);
    }
  }

  @Override
  public Access access(Connection c, ConnectionSettings s, ServerInfo server) throws SQLException {
    String user;
    boolean readOnlyServer;
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(server.mariaDb() ? "SELECT CURRENT_USER(), @@read_only, 0"
             : "SELECT CURRENT_USER(), @@read_only, @@super_read_only")) {
      rs.next();
      user = rs.getString(1);
      readOnlyServer = rs.getInt(2) == 1 || rs.getInt(3) == 1;
    }
    List<String> lines = grants(c, "SHOW GRANTS");
    Set<String> roles = new LinkedHashSet<>();
    for (String line : lines) {
      Matcher m = ROLE_GRANT.matcher(line);
      if (m.find() && !GRANT.matcher(line).find()) {
        Matcher r = ROLE.matcher(m.group(1));
        while (r.find()) {
          roles.add(r.group(2) == null ? "`" + r.group(1) + "`" : "`" + r.group(1) + "`@`" + r.group(2) + "`");
        }
      }
    }
    List<String> all = new ArrayList<>(lines);
    if (!roles.isEmpty()) {
      if (server.mariaDb()) {
        for (String role : roles) {
          all.addAll(grants(c, "SHOW GRANTS FOR " + role));
        }
      } else {
        all.addAll(grants(c, "SHOW GRANTS FOR CURRENT_USER() USING " + String.join(", ", roles)));
      }
    }
    Set<String> writeObjects = new TreeSet<>();
    Set<String> writePrivileges = new TreeSet<>();
    Set<String> dangerous = new TreeSet<>();
    Set<String> admin = new TreeSet<>();
    Set<String> globalPrivileges = new TreeSet<>();
    boolean schemaChanges = false;
    boolean superuser = false;
    for (String line : all) {
      Matcher m = GRANT.matcher(line);
      if (!m.find()) {
        continue;
      }
      String object = m.group(2).replace("`", "");
      for (String raw : splitPrivileges(m.group(1))) {
        String p = raw.toUpperCase(Locale.ROOT).replaceAll("\\s*\\(.*\\)$", "").strip();
        if ((p.equals("ALL PRIVILEGES") || p.equals("ALL")) && object.equals("*.*")) {
          superuser = true;
        }
        if (object.equals("*.*")) {
          globalPrivileges.add(p);
        }
        if (WRITE.contains(p)) {
          writePrivileges.add(p.equals("ALL") ? "ALL PRIVILEGES" : p);
          writeObjects.add(object);
        }
        if (SCHEMA_CHANGES.contains(p)) {
          schemaChanges = true;
        }
        if (DANGEROUS.contains(p)) {
          dangerous.add(p);
        } else if (ADMIN.contains(p) || p.endsWith("_ADMIN")) {
          admin.add(p);
        }
      }
      if (line.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION")) {
        admin.add("GRANT OPTION");
      }
    }
    // MySQL 8 spells ALL PRIVILEGES ON *.* out privilege by privilege: SUPER or SYSTEM_USER with the power to make
    // users and hand out grants is the same superuser.
    if (globalPrivileges.contains("GRANT OPTION") || all.stream().anyMatch(l -> l.toUpperCase(Locale.ROOT)
        .contains(" ON *.* TO ") && l.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION"))) {
      globalPrivileges.add("GRANT OPTION");
    }
    if ((globalPrivileges.contains("SUPER") || globalPrivileges.contains("SYSTEM_USER"))
        && globalPrivileges.contains("CREATE USER") && globalPrivileges.contains("GRANT OPTION")) {
      superuser = true;
    }
    if (superuser) {
      dangerous.add("ALL PRIVILEGES ON *.*");
    }
    int readable;
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN ("
             + MySqlCatalog.SYSTEM_SCHEMAS + ")")) {
      rs.next();
      readable = rs.getInt(1);
    }
    String write = writeObjects.isEmpty() ? null : String.join(", ", shortList(writePrivileges)) + " on "
        + String.join(", ", writeObjects);
    return new Access(user, readable, writeObjects.isEmpty() ? 0 : -1, write, schemaChanges, superuser,
        List.copyOf(dangerous), List.copyOf(admin), List.of(), readOnlyServer);
  }

  @Override
  public List<String> builtInFunctions(Connection c) {
    return List.of();
  }

  @Override
  public List<String> missingSchemas(Connection c, List<String> schemas) throws SQLException {
    List<String> missing = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
      for (String schema : schemas) {
        st.setString(1, schema);
        try (ResultSet rs = st.executeQuery()) {
          if (!rs.next()) {
            missing.add(schema);
          }
        }
      }
    }
    return missing;
  }

  @Override
  public String readOnlyUserSql(ConnectionSettings s) {
    String db = s.database() == null ? "app" : s.database();
    return "-- MySQL 8 prints the generated password once; on MariaDB write IDENTIFIED BY '<a new password>'\n"
        + "CREATE USER 'gw_reader'@'%' IDENTIFIED BY RANDOM PASSWORD;\n"
        + "GRANT SELECT, SHOW VIEW ON " + IdentifierRules.quote(db, true) + ".* TO 'gw_reader'@'%';";
  }

  private static List<String> grants(Connection c, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement()) {
      st.setEscapeProcessing(false);
      try (ResultSet rs = st.executeQuery(sql)) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  /** {@code SELECT, INSERT, UPDATE (a, b)}: commas inside column lists don't split. */
  static List<String> splitPrivileges(String list) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    StringBuilder current = new StringBuilder();
    for (char c : list.toCharArray()) {
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      }
      if (c == ',' && depth == 0) {
        out.add(current.toString().strip());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    if (!current.isEmpty()) {
      out.add(current.toString().strip());
    }
    return out;
  }

  /** INSERT, UPDATE, DELETE first, then the rest, at most five. */
  private static List<String> shortList(Set<String> privileges) {
    List<String> out = new ArrayList<>();
    for (String p : List.of("ALL PRIVILEGES", "INSERT", "UPDATE", "DELETE")) {
      if (privileges.contains(p)) {
        out.add(p);
      }
    }
    for (String p : privileges) {
      if (!out.contains(p) && out.size() < 5) {
        out.add(p);
      }
    }
    return out;
  }
}
