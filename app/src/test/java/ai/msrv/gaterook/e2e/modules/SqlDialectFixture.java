package ai.msrv.gaterook.e2e.modules;

import ai.msrv.gaterook.e2e.TestServices;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import tools.jackson.databind.node.ObjectNode;

/**
 * A SQL dialect that is its own unit of delivery, with its database of the Compose test services: what the tests of
 * its module and of its bridge need to know of it.
 *
 * @param moduleId           the module's id, which is its folder under {@code modules/}
 * @param type               the connector type the module adds
 * @param typeName           the type's name on its card
 * @param selectFortyTwo     a query that returns 41 + 1 in a column named {@code N}
 * @param selectFortyOnePlus a query that adds its parameter {@code :n} to 41, for a template
 * @param columns            the columns {@code id} and {@code owner} of a table, as the dialect declares them
 */
record SqlDialectFixture(String moduleId, String type, String typeName, String selectFortyTwo,
    String selectFortyOnePlus, String columns) {

  static SqlDialectFixture oracle() {
    return new SqlDialectFixture("sql-oracle", "oracle", "Oracle Database", "SELECT 41 + 1 AS n FROM dual",
        "SELECT 41 + :n AS n FROM dual", "id NUMBER(10) PRIMARY KEY, owner VARCHAR2(40)");
  }

  static SqlDialectFixture sqlServer() {
    return new SqlDialectFixture("sql-sqlserver", "sqlserver", "SQL Server", "SELECT 41 + 1 AS n",
        "SELECT 41 + :n AS n", "id INT PRIMARY KEY, owner NVARCHAR(40)");
  }

  /** The statement that makes the table {@code name}. */
  String createTable(String name) {
    return "CREATE TABLE " + name + " (" + columns + ")";
  }

  /** A connection of the type to its test database, as {@code POST /api/connections} takes it. */
  ObjectNode connection(String name) {
    ObjectNode config = CoreProcess.JSON.createObjectNode();
    ObjectNode target = config.putObject("connection").put("host", "127.0.0.1");
    String password;
    if (type.equals("oracle")) {
      target.put("port", TestServices.port("GW_ORACLE_PORT")).put("database", TestServices.value("GW_ORACLE_SERVICE"))
          .put("sslMode", "disable");
      config.putObject("credentials").put("user", TestServices.value("GW_ORACLE_USER"));
      password = TestServices.value("GW_ORACLE_PASSWORD");
    } else {
      // The fixture's certificate is its own: the connection is encrypted and the certificate isn't verified.
      target.put("port", TestServices.port("GW_SQLSERVER_PORT")).put("database", "master").put("sslMode", "require");
      config.putObject("credentials").put("user", "sa");
      password = TestServices.value("GW_SQLSERVER_PASSWORD");
    }
    ObjectNode connection = CoreProcess.JSON.createObjectNode().put("name", name).put("type", type).put("env", "dev");
    connection.set("config", config);
    connection.putObject("secrets").putObject("/credentials/password").put("source", "local").put("value", password);
    return connection;
  }

  /** The arguments of {@code sql_query} and {@code sql_execute}. */
  static String arguments(String connection, String sql) {
    return CoreProcess.JSON.createObjectNode().put("connection", connection).put("sql", sql).toString();
  }
}
