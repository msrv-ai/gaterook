package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Connection diagnostics using public dictionary views; no temporary tables or write probes. */
final class OracleProbe implements SqlProbe {
  private static final Set<String> DANGEROUS = Set.of("ALTER SYSTEM", "CREATE ANY DIRECTORY", "DROP ANY DIRECTORY",
      "CREATE EXTERNAL JOB", "EXECUTE ANY PROCEDURE", "BECOME USER", "ADMINISTER DATABASE TRIGGER");
  private static final String EFFECTIVE_GRANTEES = "GRANTEE IN (SYS_CONTEXT('USERENV', 'SESSION_USER'), 'PUBLIC') "
      + "OR GRANTEE IN (SELECT ROLE FROM SESSION_ROLES)";

  @Override
  public Tls tls(Connection c, ServerInfo server) throws SQLException {
    try (Statement st = c.createStatement();
         ResultSet rows = st.executeQuery("SELECT SYS_CONTEXT('USERENV', 'NETWORK_PROTOCOL') FROM DUAL")) {
      boolean enabled = rows.next() && "tcps".equalsIgnoreCase(rows.getString(1));
      return new Tls(enabled, enabled ? "TCPS" : null);
    }
  }

  @Override
  public Access access(Connection c, ConnectionSettings s, ServerInfo server) throws SQLException {
    String user;
    boolean sys;
    try (Statement st = c.createStatement(); ResultSet rows = st.executeQuery(
        "SELECT SYS_CONTEXT('USERENV', 'SESSION_USER'), SYS_CONTEXT('USERENV', 'ISDBA') FROM DUAL")) {
      rows.next(); user = rows.getString(1); sys = "TRUE".equalsIgnoreCase(rows.getString(2));
    }
    TreeSet<String> privileges = strings(c, "SELECT PRIVILEGE FROM SESSION_PRIVS");
    TreeSet<String> roles = strings(c, "SELECT ROLE FROM SESSION_ROLES");
    TreeSet<String> dangerous = new TreeSet<>();
    TreeSet<String> admin = new TreeSet<>();
    TreeSet<String> writes = new TreeSet<>();
    boolean schemaChanges = false;
    for (String privilege : privileges) {
      if (DANGEROUS.contains(privilege)) dangerous.add(privilege);
      else if (privilege.contains(" ANY ") || privilege.contains(" USER") || privilege.contains(" ROLE")) {
        admin.add(privilege);
      }
      if (privilege.startsWith("INSERT ") || privilege.startsWith("UPDATE ") || privilege.startsWith("DELETE ")) {
        writes.add(privilege);
      }
      if (privilege.startsWith("CREATE ") || privilege.startsWith("ALTER ") || privilege.startsWith("DROP ")) {
        schemaChanges = true;
      }
    }
    int owned = count(c, "SELECT COUNT(*) FROM USER_TABLES");
    if (owned > 0) {
      writes.add("Owned tables");
      schemaChanges = true;
    }
    writes.addAll(strings(c, "SELECT DISTINCT PRIVILEGE FROM ALL_TAB_PRIVS WHERE (" + EFFECTIVE_GRANTEES
        + ") AND PRIVILEGE IN ('INSERT', 'UPDATE', 'DELETE', 'ALTER', 'INDEX')"));
    // ALL_TABLES includes tables visible through non-SELECT grants; this is visibility, not a read grant count.
    String write = writes.isEmpty() ? null : String.join(", ", writes);
    boolean superuser = sys || "SYS".equals(user) || roles.contains("DBA");
    return new Access(user, -1, write == null ? 0 : -1, write, schemaChanges, superuser,
        List.copyOf(dangerous), List.copyOf(admin), List.of(), false);
  }

  @Override public List<String> builtInFunctions(Connection c) { return List.of(); }

  @Override
  public List<String> missingSchemas(Connection c, List<String> schemas) throws SQLException {
    List<String> missing = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM ALL_USERS WHERE USERNAME = ?")) {
      for (String schema : schemas) {
        var parts = IdentifierRules.parts(schema, SqlSyntax.ORACLE);
        String name = parts.size() == 1 ? IdentifierRules.normalize(parts.getFirst(), SqlSyntax.ORACLE) : schema;
        st.setString(1, name);
        try (ResultSet rows = st.executeQuery()) { if (!rows.next()) missing.add(schema); }
      }
    }
    return missing;
  }

  @Override
  public String readOnlyUserSql(ConnectionSettings s) {
    String schema = s.defaultSchema() == null ? "APP" : s.defaultSchema();
    return "CREATE USER GW_READER IDENTIFIED BY \"<a new password>\";\n"
        + "GRANT CREATE SESSION TO GW_READER;\n"
        + "-- Repeat for each table or view the connection should read.\n"
        + "GRANT SELECT ON " + IdentifierRules.quote(schema, false) + ".\"EXAMPLE_TABLE\" TO GW_READER;";
  }

  private static TreeSet<String> strings(Connection c, String sql) throws SQLException {
    TreeSet<String> values = new TreeSet<>();
    try (Statement st = c.createStatement(); ResultSet rows = st.executeQuery(sql)) {
      while (rows.next()) values.add(rows.getString(1));
    }
    return values;
  }

  private static int count(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rows = st.executeQuery(sql)) {
      return rows.next() ? rows.getInt(1) : 0;
    }
  }
}
