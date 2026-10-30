package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQL Server connection checks using effective permissions instead of assuming database role membership. */
final class SqlServerProbe implements SqlProbe {

  @Override
  public Tls tls(Connection c, ServerInfo server) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
        "SELECT encrypt_option FROM sys.dm_exec_connections WHERE session_id = @@SPID")) {
      return rs.next() ? new Tls("TRUE".equalsIgnoreCase(rs.getString(1)), "TLS") : null;
    } catch (SQLException e) {
      // This DMV needs server-state permissions that a read-only application login should not receive.
      if (e.getErrorCode() == 297 || e.getErrorCode() == 300 || e.getErrorCode() == 229) {
        return null;
      }
      throw e;
    }
  }

  @Override
  public Access access(Connection c, ConnectionSettings s, ServerInfo server) throws SQLException {
    String user;
    boolean superuser;
    boolean schemaChanges;
    boolean replica;
    List<String> dangerous = new ArrayList<>();
    List<String> admin = new ArrayList<>();
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT ORIGINAL_LOGIN(), "
        + "IS_SRVROLEMEMBER('sysadmin'), HAS_PERMS_BY_NAME(NULL, 'SERVER', 'CONTROL SERVER'), "
        + "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CONTROL'), "
        + "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CREATE TABLE'), "
        + "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'ALTER ANY SCHEMA'), "
        + "CONVERT(nvarchar(60), DATABASEPROPERTYEX(DB_NAME(), 'Updateability')), "
        + "IS_SRVROLEMEMBER('securityadmin'), IS_SRVROLEMEMBER('serveradmin'), "
        + "IS_SRVROLEMEMBER('bulkadmin'), HAS_PERMS_BY_NAME(NULL, 'SERVER', 'IMPERSONATE ANY LOGIN')")) {
      if (!rs.next()) {
        throw new SQLException("SQL Server did not return the current login's permissions.");
      }
      user = rs.getString(1);
      superuser = rs.getInt(2) == 1 || rs.getInt(3) == 1;
      schemaChanges = rs.getInt(4) == 1 || rs.getInt(5) == 1 || rs.getInt(6) == 1;
      replica = "READ_ONLY".equalsIgnoreCase(rs.getString(7));
      if (superuser) admin.add("sysadmin or CONTROL SERVER");
      if (rs.getInt(4) == 1) admin.add("CONTROL DATABASE");
      if (rs.getInt(8) == 1) dangerous.add("securityadmin");
      if (rs.getInt(9) == 1) dangerous.add("serveradmin");
      if (rs.getInt(10) == 1) dangerous.add("bulkadmin");
      if (rs.getInt(11) == 1) dangerous.add("IMPERSONATE ANY LOGIN");
    }
    int readable = 0;
    int writable = 0;
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT "
        + "HAS_PERMS_BY_NAME(QUOTENAME(s.name)+'.'+QUOTENAME(o.name), 'OBJECT', 'SELECT'), "
        + "HAS_PERMS_BY_NAME(QUOTENAME(s.name)+'.'+QUOTENAME(o.name), 'OBJECT', 'INSERT'), "
        + "HAS_PERMS_BY_NAME(QUOTENAME(s.name)+'.'+QUOTENAME(o.name), 'OBJECT', 'UPDATE'), "
        + "HAS_PERMS_BY_NAME(QUOTENAME(s.name)+'.'+QUOTENAME(o.name), 'OBJECT', 'DELETE'), "
        + "HAS_PERMS_BY_NAME(QUOTENAME(s.name)+'.'+QUOTENAME(o.name), 'OBJECT', 'ALTER') "
        + "FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id "
        + "WHERE o.type IN ('U','V') AND o.is_ms_shipped = 0")) {
      while (rs.next()) {
        if (rs.getInt(1) == 1) readable++;
        if (rs.getInt(2) == 1 || rs.getInt(3) == 1 || rs.getInt(4) == 1) writable++;
        if (rs.getInt(5) == 1) schemaChanges = true;
      }
    }
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT TOP (1) 1 FROM sys.schemas "
        + "WHERE name NOT IN ('sys','INFORMATION_SCHEMA') AND "
        + "(HAS_PERMS_BY_NAME(name, 'SCHEMA', 'ALTER') = 1 OR HAS_PERMS_BY_NAME(name, 'SCHEMA', 'CONTROL') = 1)")) {
      schemaChanges |= rs.next();
    }
    List<String> extensions = new ArrayList<>();
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM sys.assemblies "
        + "WHERE is_user_defined = 1 AND permission_set <> 1 ORDER BY name")) {
      while (rs.next()) extensions.add(rs.getString(1));
    }
    return new Access(user, readable, writable,
        writable > 0 ? "INSERT, UPDATE or DELETE on " + writable + " tables" : null,
        schemaChanges, superuser, List.copyOf(dangerous), List.copyOf(admin), List.copyOf(extensions), replica);
  }

  @Override
  public List<String> builtInFunctions(Connection c) { return List.of(); }

  @Override
  public List<String> missingSchemas(Connection c, List<String> schemas) throws SQLException {
    List<String> missing = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM sys.schemas WHERE name = ?")) {
      for (String schema : schemas) {
        st.setString(1, schema);
        try (ResultSet rs = st.executeQuery()) {
          if (!rs.next()) missing.add(schema);
        }
      }
    }
    return List.copyOf(missing);
  }

  @Override
  public String readOnlyUserSql(ConnectionSettings s) {
    String db = SqlServerCatalog.quote(s.database() == null ? "app" : s.database());
    String schema = SqlServerCatalog.quote(s.defaultSchema());
    return "-- Create the gw_reader SQL login securely in your administration tool, then run:\n"
        + "USE " + db + ";\nCREATE USER [gw_reader] FOR LOGIN [gw_reader] WITH DEFAULT_SCHEMA = " + schema + ";\n"
        + "GRANT SELECT ON SCHEMA::" + schema + " TO [gw_reader];\n"
        + "GRANT SHOWPLAN TO [gw_reader];";
  }
}
