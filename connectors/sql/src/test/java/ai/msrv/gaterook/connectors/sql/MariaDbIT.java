package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** MariaDB 11.4.5 from the mariaDB4j-db-linux64 jar, through Connector/J: {@code -Pmysql-it}. */
@Tag("mysql-it")
class MariaDbIT extends MysqlFamilyIT {

  @RegisterExtension
  static final LocalMysql MARIADB = LocalMysql.mariaDb();

  @Override
  LocalMysql server() {
    return MARIADB;
  }

  /** max_statement_time stops every statement on MariaDB, SLEEP included. */
  @Override
  String slowQuery() {
    return "SELECT SLEEP(10)";
  }

  @Override
  String sslMode() {
    return "disable";
  }

  @Test
  void readOnlyStopsNextval() throws Exception {
    long before = count("SELECT COUNT(*) FROM app.ticket_seq WHERE next_not_cached_value > 1");

    assertThatThrownBy(() -> read("rw", "SELECT NEXTVAL(app.ticket_seq)")).hasMessageContaining("READ ONLY");
    assertThat(count("SELECT COUNT(*) FROM app.ticket_seq WHERE next_not_cached_value > 1")).isEqualTo(before);
  }

  @Test
  void theServerIsMariaDb() {
    TestResult r = CONNECTOR.test(cfg("ro", Env.DEV), secrets("ro"), Fixtures.Context.of(Env.DEV, WORK));

    assertThat(r.serverVersion()).isEqualTo("MariaDB 11.4.5");
    assertThat(r.metadataUpdate().path("serverVersion").asString()).isEqualTo("MariaDB 11.4");
  }
}
