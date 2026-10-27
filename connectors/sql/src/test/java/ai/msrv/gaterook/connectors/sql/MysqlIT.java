package ai.msrv.gaterook.connectors.sql;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;

/** MySQL 8.4.10 from the generic tarball: {@code -Pmysql-it}. */
@Tag("mysql-it")
class MysqlIT extends MysqlFamilyIT {

  @RegisterExtension
  static final LocalMysql MYSQL = LocalMysql.mysql();

  @Override
  LocalMysql server() {
    return MYSQL;
  }

  /**
   * max_execution_time stops a SELECT that reads: a recursive CTE over three million rows (the hint lifts the default
   * recursion limit of 1,000, which would stop it first with another error).
   */
  @Override
  String slowQuery() {
    return "SELECT /*+ SET_VAR(cte_max_recursion_depth = 10000000) */ COUNT(*) FROM (WITH RECURSIVE r(n) AS "
        + "(SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 3000000) SELECT n FROM r) t";
  }

  @Override
  String sslMode() {
    // The server made its own certificate at --initialize: encrypted, not verified.
    return "require";
  }
}
