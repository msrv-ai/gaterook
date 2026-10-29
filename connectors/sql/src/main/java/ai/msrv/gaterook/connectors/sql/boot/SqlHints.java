package ai.msrv.gaterook.connectors.sql.boot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Native-image hints of the SQL connectors; they live with the module, not in the core (ARCHITECTURE.md §2.3). The
 * drivers' own reflection is covered by the GraalVM reachability metadata repository (report 15 §4: postgresql,
 * mysql-connector-j, mssql-jdbc, HikariCP), including SQL Server's localized resource bundle. The module's
 * native-image.properties also includes the charsets SQL Server selects from runtime collation metadata.
 * What is left here is what the connector names itself.
 */
class SqlHints implements RuntimeHintsRegistrar {

  /** pgjdbc instantiates {@code sslfactory} by name; PostgresDialect names it for a CA from the JVM's trust store. */
  static final String PG_JAVA_SSL_FACTORY = "org.postgresql.ssl.DefaultJavaSSLFactory";

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool, connection, policy and template schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/sql/*.schema.json");
    hints.reflection().registerType(TypeReference.of(PG_JAVA_SSL_FACTORY), MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
  }
}
