package ai.msrv.gaterook.connectors.spanner;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import java.util.regex.Pattern;

/**
 * The settings of one Spanner connection ({@code config.schema.json}): one database.
 *
 * @param project      the project of the instance
 * @param instance     the instance id
 * @param database     the database id
 * @param databaseRole the fine-grained access control role whose privileges every session gets ({@code creatorRole}),
 *                     or {@code null}: the credentials' own IAM access
 */
record SpannerSettings(String project, String instance, String database, String databaseRole, GcpSpec credentials) {

  static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]|[a-z0-9.-]{1,63}:[a-z][a-z0-9-]{4,28}"
      + "[a-z0-9]");
  /** An instance id: a lower-case letter first, then letters, digits and hyphens, 2 to 64 characters. */
  static final Pattern INSTANCE = Pattern.compile("[a-z][a-z0-9-]{0,62}[a-z0-9]");
  /** A database id: a lower-case letter first, then letters, digits, underscores and hyphens, 2 to 30 characters. */
  static final Pattern DATABASE = Pattern.compile("[a-z][a-z0-9_-]{0,28}[a-z0-9]");
  /** A database role: a letter first, then letters, digits and underscores. */
  static final Pattern ROLE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");
  /** A table, column or index name as Spanner's schemas allow it, optionally after its named schema. */
  static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

  static SpannerSettings of(ConnectionConfig cfg) throws ConnectorException {
    String project = cfg.string("/project");
    if (project == null || !PROJECT.matcher(project).matches()) {
      throw ConnectorException.badInput("Enter the Google Cloud project of the Spanner instance, such as acme-prod.");
    }
    String instance = cfg.string("/instance");
    if (instance == null || !INSTANCE.matcher(instance).matches()) {
      throw ConnectorException.badInput("Enter the Spanner instance id, such as orders-prod: lower-case letters, digits "
          + "and hyphens.");
    }
    String database = cfg.string("/database");
    if (database == null || !DATABASE.matcher(database).matches()) {
      throw ConnectorException.badInput("Enter the Spanner database id, such as orders: lower-case letters, digits, "
          + "underscores and hyphens.");
    }
    String role = cfg.string("/advanced/databaseRole");
    if (role != null && role.isBlank()) {
      role = null;
    }
    if (role != null && !ROLE.matcher(role).matches()) {
      throw ConnectorException.badInput(role + " isn't a database role name: letters, digits and underscores, a letter "
          + "first.");
    }
    GcpSpec spec;
    try {
      spec = GcpSpec.fromConfig(cfg.config(), "/credentials");
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The Google credentials of " + cfg.name() + " aren't complete: "
          + e.getMessage() + ".");
    }
    return new SpannerSettings(project, instance, database, role, spec);
  }

  /** {@code projects/<p>/instances/<i>/databases/<d>}: the database's resource name. */
  String databaseName() {
    return "projects/" + project + "/instances/" + instance + "/databases/" + database;
  }

  /** {@code <instance>/<database>}, how the connection's database reads in messages. */
  String label() {
    return instance + "/" + database;
  }

  /**
   * {@code name} or {@code schema.name} as {@code [schema, name]}, without quotes; {@code null} when a part isn't a
   * Spanner name. The schema is {@code null} when none is written: the database's default one.
   */
  static String[] table(String written) {
    if (written == null) {
      return null;
    }
    String plain = written.strip().replace("`", "").replace("\"", "");
    int dot = plain.indexOf('.');
    String schema = dot < 0 ? null : plain.substring(0, dot);
    String name = dot < 0 ? plain : plain.substring(dot + 1);
    if ((schema != null && !NAME.matcher(schema).matches()) || !NAME.matcher(name).matches()) {
      return null;
    }
    return new String[] {schema, name};
  }
}
