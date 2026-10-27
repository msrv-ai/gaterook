package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Test connection on PostgreSQL (report 04 §5, SQL checked against the roles ro, rw and postgres). */
final class PostgresProbe implements SqlProbe {

  /** Roles that reach outside the database: server files, programs, other sessions. */
  static final List<String> DANGEROUS_ROLES = List.of("pg_execute_server_program", "pg_write_server_files",
      "pg_read_server_files", "pg_signal_backend");

  /** Superusers of managed services: AWS RDS, Google Cloud SQL, Azure. */
  static final List<String> SUPERUSER_ROLES = List.of("rds_superuser", "cloudsqlsuperuser", "azure_pg_admin");

  /** Extensions and languages that run code outside the read's transaction or outside the database. */
  static final List<String> DANGEROUS_EXTENSIONS = List.of("dblink", "postgres_fdw", "plpython3u", "plpythonu",
      "plperlu", "pltclu", "adminpack", "file_fdw");

  @Override
  public Tls tls(Connection c, ServerInfo server) throws SQLException {
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT ssl, version FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
      if (rs.next()) {
        return new Tls(rs.getBoolean(1), rs.getString(2));
      }
      return new Tls(false, null);
    }
  }

  @Override
  public Access access(Connection c, ConnectionSettings s, ServerInfo server) throws SQLException {
    List<String> roles = new ArrayList<>(DANGEROUS_ROLES);
    roles.addAll(SUPERUSER_ROLES);
    roles.add("pg_write_all_data");
    String user;
    boolean superuser;
    boolean createRole;
    boolean createDb;
    boolean bypassRls;
    boolean replication;
    boolean replica;
    boolean dbCreate;
    List<String> memberOf = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT current_user, r.rolsuper, r.rolcreaterole, r.rolcreatedb, "
        + "r.rolbypassrls, r.rolreplication, pg_is_in_recovery(), "
        + "has_database_privilege(current_database(), 'CREATE'), "
        + "ARRAY(SELECT b.rolname::text FROM pg_roles b WHERE b.rolname = ANY(?) "
        + "AND pg_has_role(current_user, b.oid, 'MEMBER') ORDER BY 1) "
        + "FROM pg_roles r WHERE r.rolname = current_user")) {
      Array wanted = c.createArrayOf("text", roles.toArray());
      st.setArray(1, wanted);
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          throw new SQLException("The current user has no row in pg_roles.");
        }
        user = rs.getString(1);
        superuser = rs.getBoolean(2);
        createRole = rs.getBoolean(3);
        createDb = rs.getBoolean(4);
        bypassRls = rs.getBoolean(5);
        replication = rs.getBoolean(6);
        replica = rs.getBoolean(7);
        dbCreate = rs.getBoolean(8);
        Object granted = rs.getArray(9).getArray();
        if (granted instanceof Object[] names) {
          for (Object n : names) {
            memberOf.add(String.valueOf(n));
          }
        }
      }
    }
    int readable;
    int writable;
    String writeSchemas;
    boolean schemaCreate;
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE has_table_privilege(c.oid, 'SELECT')), "
             + "count(*) FILTER (WHERE has_table_privilege(c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE')), "
             + "string_agg(DISTINCT n.nspname, ', ') FILTER (WHERE has_table_privilege(c.oid, "
             + "'INSERT,UPDATE,DELETE,TRUNCATE')), "
             + "coalesce(bool_or(has_schema_privilege(n.oid, 'CREATE')), false) "
             + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
             + "WHERE c.relkind IN ('r','p','v','m','f') AND n.nspname NOT IN ('pg_catalog','information_schema') "
             + "AND n.nspname NOT LIKE 'pg\\_toast%'")) {
      rs.next();
      readable = rs.getInt(1);
      writable = rs.getInt(2);
      writeSchemas = rs.getString(3);
      schemaCreate = rs.getBoolean(4);
    }
    List<String> extensions = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT extname FROM pg_extension WHERE extname = ANY(?) "
        + "UNION SELECT lanname FROM pg_language WHERE lanname = ANY(?) ORDER BY 1")) {
      Array wanted = c.createArrayOf("text", DANGEROUS_EXTENSIONS.toArray());
      st.setArray(1, wanted);
      st.setArray(2, wanted);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          extensions.add(rs.getString(1));
        }
      }
    }
    List<String> dangerous = new ArrayList<>();
    memberOf.stream().filter(DANGEROUS_ROLES::contains).forEach(dangerous::add);
    boolean managedSuperuser = memberOf.stream().anyMatch(SUPERUSER_ROLES::contains);
    List<String> admin = new ArrayList<>();
    if (superuser) {
      admin.add("SUPERUSER");
    }
    memberOf.stream().filter(SUPERUSER_ROLES::contains).forEach(admin::add);
    if (createRole) {
      admin.add("CREATEROLE");
    }
    if (createDb) {
      admin.add("CREATEDB");
    }
    if (bypassRls) {
      admin.add("BYPASSRLS");
    }
    if (replication) {
      admin.add("REPLICATION");
    }
    boolean writeAll = memberOf.contains("pg_write_all_data");
    String write = writable > 0 || writeAll
        ? "INSERT, UPDATE, DELETE on " + (writeAll ? "every table" : star(writeSchemas)) : null;
    return new Access(user, readable, writeAll ? Math.max(writable, 1) : writable, write, schemaCreate || dbCreate,
        superuser || managedSuperuser, List.copyOf(dangerous), List.copyOf(admin), List.copyOf(extensions), replica);
  }

  @Override
  public List<String> builtInFunctions(Connection c) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT DISTINCT proname::text FROM pg_proc "
             + "WHERE pronamespace = 'pg_catalog'::regnamespace ORDER BY 1")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  @Override
  public List<String> missingSchemas(Connection c, List<String> schemas) throws SQLException {
    List<String> missing = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM pg_namespace n WHERE n.nspname = ? "
        + "AND has_schema_privilege(n.oid, 'USAGE')")) {
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
    String db = IdentifierRules.quoteIfNeeded(s.database() == null ? "app" : s.database(), false);
    String schema = IdentifierRules.quoteIfNeeded(s.defaultSchema() == null ? "public" : s.defaultSchema(), false);
    return "CREATE ROLE gw_reader LOGIN;\n"
        + "-- psql asks for the password; it never appears in this script\n"
        + "\\password gw_reader\n"
        + "GRANT CONNECT ON DATABASE " + db + " TO gw_reader;\n"
        + "GRANT USAGE ON SCHEMA " + schema + " TO gw_reader;\n"
        + "GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO gw_reader;\n"
        + "ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT SELECT ON TABLES TO gw_reader;";
  }

  /** {@code public, billing} → {@code public.*, billing.*}. */
  private static String star(String schemas) {
    if (schemas == null || schemas.isBlank()) {
      return "some tables";
    }
    List<String> out = new ArrayList<>();
    for (String s : schemas.split(", ")) {
      out.add(s + ".*");
    }
    return String.join(", ", out);
  }
}
