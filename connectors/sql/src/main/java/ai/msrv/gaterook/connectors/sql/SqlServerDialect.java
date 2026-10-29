package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.OptionalLong;
import java.util.Properties;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Microsoft SQL Server over TCP with SQL authentication. The driver cannot enforce read-only transactions;
 * SQL Server reads therefore also require the connector's strict T-SQL admission checks and always roll back.
 */
public final class SqlServerDialect implements SqlDialect {

  private final SqlServerCatalog catalog = new SqlServerCatalog();
  private final SqlServerProbe probe = new SqlServerProbe();

  @Override
  public String type() { return "sqlserver"; }

  @Override
  public String displayName() { return "SQL Server"; }

  @Override
  public String description() {
    return "Microsoft SQL Server: schemas, tables and queries. Writes wait for approval.";
  }

  @Override
  public String configSchemaName() { return "sqlserver.config"; }

  @Override
  public boolean mysql() { return false; }

  @Override
  public Driver driver() { return new com.microsoft.sqlserver.jdbc.SQLServerDriver(); }

  /** All user-supplied fields are properties, so semicolons and braces can never add JDBC options. */
  @Override
  public String url(ConnectionSettings s) { return "jdbc:sqlserver://"; }

  @Override
  public Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs) throws IOException {
    Properties p = new Properties();
    String host = s.host();
    if (host != null && host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    p.setProperty("serverName", host);
    p.setProperty("portNumber", Integer.toString(s.port()));
    if (s.database() != null) {
      p.setProperty("databaseName", s.database());
    }
    p.setProperty("authentication", "SqlPassword");
    p.setProperty("integratedSecurity", "false");
    p.setProperty("applicationName", "gaterook");
    p.setProperty("loginTimeout", Integer.toString(Math.max(1, (s.connectTimeoutMs() + 999) / 1000)));
    p.setProperty("socketTimeout", Integer.toString(socketTimeoutMs));
    p.setProperty("cancelQueryTimeout", "2");
    p.setProperty("connectRetryCount", "0");
    p.setProperty("responseBuffering", "adaptive");
    p.setProperty("selectMethod", "direct");
    p.setProperty("prepareMethod", "prepexec");
    p.setProperty("enablePrepareOnFirstPreparedStatementCall", "true");
    p.setProperty("statementPoolingCacheSize", "0");
    p.setProperty("disableStatementPooling", "true");
    p.setProperty("useBulkCopyForBatchInsert", "false");
    p.setProperty("encrypt", "disable".equals(s.sslMode()) ? "false" : "true");
    p.setProperty("trustServerCertificate", "verify-full".equals(s.sslMode()) ? "false" : "true");
    if ("verify-full".equals(s.sslMode()) && s.caCertificate() != null) {
      p.setProperty("trustStore", CertificateFiles.pkcs12(certificates, s.caCertificate()).toString());
      p.setProperty("trustStoreType", "PKCS12");
      p.setProperty("trustStorePassword", CertificateFiles.TRUST_STORE_PASSWORD);
    }
    return p;
  }

  @Override
  public ServerInfo identify(Connection c) throws SQLException {
    return ServerInfo.parse("SQL Server", c.getMetaData().getDatabaseProductVersion());
  }

  @Override
  public void prelude(Connection c, ServerInfo server, ConnectionSettings s, long timeoutMs, boolean readOnly)
      throws SQLException {
    try (Statement st = c.createStatement()) {
      st.setEscapeProcessing(false);
      st.setQueryTimeout((int) Math.max(1, (timeoutMs + 999) / 1000));
      st.execute("SET QUOTED_IDENTIFIER ON; SET XACT_ABORT ON; SET NOCOUNT OFF; SET LOCK_TIMEOUT "
          + Math.max(1, Math.min(Integer.MAX_VALUE, timeoutMs)));
      // SQL Server cannot set a default schema for one session. Verify name resolution instead of assuming it.
      try (ResultSet rs = st.executeQuery("SELECT SCHEMA_NAME()")) {
        if (!rs.next() || !s.defaultSchema().equals(rs.getString(1))) {
          throw new SQLException("The configured default schema does not match this database user's default "
              + "schema. Update Default schema in the connection settings.", "42000");
        }
      }
    }
  }

  @Override
  public OptionalLong estimateRows(Connection c, ServerInfo server, SqlExecutor.BoundSql bound) throws SQLException {
    return withShowPlan(c, () -> {
      try (Statement st = SqlExecutor.create(c, "", bound, this)) {
        st.setQueryTimeout(5);
        if (!SqlExecutor.execute(st, "", bound)) {
          return OptionalLong.empty();
        }
        try (ResultSet rs = st.getResultSet()) {
          return rs != null && rs.next() ? estimatedRows(rs.getString(1)) : OptionalLong.empty();
        }
      }
    });
  }

  @FunctionalInterface
  interface PlanWork<T> {
    T run() throws SQLException;
  }

  /** SHOWPLAN is connection state: enable and disable it in separate batches and never pool a failed reset. */
  static <T> T withShowPlan(Connection c, PlanWork<T> work) throws SQLException {
    try {
      // The server may apply ON before execute or close reports a failure, so setup also needs cleanup.
      try (Statement st = c.createStatement()) {
        st.setEscapeProcessing(false);
        st.setQueryTimeout(5);
        st.execute("SET SHOWPLAN_XML ON");
      }
      return work.run();
    } finally {
      try (Statement st = c.createStatement()) {
        st.setEscapeProcessing(false);
        st.setQueryTimeout(5);
        st.execute("SET SHOWPLAN_XML OFF");
      } catch (SQLException reset) {
        try {
          c.abort(Runnable::run);
        } catch (SQLException abort) {
          reset.addSuppressed(abort);
        }
        throw reset;
      }
    }
  }

  /** Parse only the top-level statement estimate; disable all external XML input. */
  static OptionalLong estimatedRows(String xml) {
    if (xml == null || xml.length() > 4 * 1024 * 1024) {
      return OptionalLong.empty();
    }
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      NodeList statements = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))
          .getElementsByTagNameNS("http://schemas.microsoft.com/sqlserver/2004/07/showplan", "StmtSimple");
      if (statements.getLength() != 1) {
        return OptionalLong.empty();
      }
      var value = statements.item(0).getAttributes().getNamedItem("StatementEstRows");
      if (value == null) {
        return OptionalLong.empty();
      }
      double rows = Double.parseDouble(value.getNodeValue());
      return Double.isFinite(rows) && rows >= 0 && rows < Long.MAX_VALUE
          ? OptionalLong.of((long) Math.ceil(rows)) : OptionalLong.empty();
    } catch (Exception invalid) {
      return OptionalLong.empty();
    }
  }

  @Override
  public boolean explainNeedsWrite() { return true; }

  @Override
  public SqlCatalog catalog() { return catalog; }

  @Override
  public SqlProbe probe() { return probe; }

  @Override
  public void changePassword(Connection c, ServerInfo server, String user, char[] next) throws SQLException {
    throw new SQLFeatureNotSupportedException("SQL Server password rotation requires the current password. "
        + "Change it at the source, then update the stored secret.");
  }
}
