package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The hints of Test connection from what the probes found (report 04 §5): severities and the kit's texts. */
class TestProbeHintsTest {

  private static final ConnectionSettings MYSQL = ConnectionSettings.of(Fixtures.config("mysql", Env.PROD,
      "db.prod.internal", 3306, "app", "app_rw", null, null), true);
  private static final SqlProbe.Tls TLS = new SqlProbe.Tls(true, "TLSv1.3");

  private static SqlProbe.Access access(String write, boolean superuser, List<String> dangerous,
      List<String> extensions, boolean replica) {
    return new SqlProbe.Access("app_rw", 12, write == null ? 0 : 12, write, false, superuser, dangerous, List.of(),
        extensions, replica);
  }

  @Test
  void mysqlPrivilegesThatReachOutsideTheDatabaseAreDangers() {
    List<Hint> hints = TestProbe.hints(Env.PROD, MYSQL, access(null, false, List.of("FILE", "SUPER", "SYSTEM_USER"),
        List.of(), false), TLS, List.of(), ServerInfo.parse("MySQL", "8.4.10"));

    assertThat(hints).extracting(Hint::severity).containsOnly(Severity.DANGER);
    assertThat(hints).extracting(Hint::text).containsExactly(
        "app_rw has FILE: it can read and write files on the database server. Use a user without it.",
        "app_rw has SUPER: it can stop other sessions and change server settings. Use a user without it.",
        "app_rw has SYSTEM_USER: it can change other users' sessions and accounts. Use a user without it.");
    assertThat(hints).extracting(Hint::field).containsOnly("/credentials/user");
  }

  @Test
  void allPrivilegesOnEverythingIsOneSuperuserDanger() {
    List<Hint> hints = TestProbe.hints(Env.DEV, MYSQL, access("INSERT, UPDATE, DELETE on *.*", true,
        List.of("ALL PRIVILEGES ON *.*"), List.of(), false), TLS, List.of(), ServerInfo.parse("MySQL", "8.4.10"));

    assertThat(hints).extracting(Hint::code).containsExactly("sql.superuser");
  }

  @Test
  void aReplicaAndAnOldServerAreSaid() {
    List<Hint> hints = TestProbe.hints(Env.DEV, MYSQL, access(null, false, List.of(), List.of(), true), TLS,
        List.of(), ServerInfo.parse("PostgreSQL", "11.22"));

    assertThat(hints).extracting(Hint::code).containsExactly("sql.replica", "sql.old-server");
    assertThat(hints.getFirst().text()).isEqualTo("Read replica: sql_execute will fail here.");
    assertThat(hints.get(1).text()).isEqualTo("PostgreSQL 11.22 is older than 12, which GateRook supports.");
  }

  @Test
  void writingOnStageIsAWarningAndTlsOffOnlyMattersOnProd() {
    SqlProbe.Tls off = new SqlProbe.Tls(false, null);

    assertThat(TestProbe.hints(Env.STAGE, MYSQL, access("INSERT on app.*", false, List.of(), List.of(), false), off,
        List.of(), ServerInfo.parse("MySQL", "8.4.10")))
        .extracting(Hint::code, Hint::severity)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("sql.user-can-write", Severity.WARNING));
    assertThat(TestProbe.hints(Env.PROD, MYSQL, access(null, false, List.of(), List.of(), false), off, List.of(),
        ServerInfo.parse("MySQL", "8.4.10"))).extracting(Hint::code).containsExactly("sql.tls-off");
  }

  @Test
  void serversAreNamedTheWayToolDescriptionsShowThem() {
    assertThat(ServerInfo.parse("PostgreSQL", "16.15 (Ubuntu 16.15-1.pgdg24.04+1)").label()).isEqualTo("PostgreSQL 16");
    assertThat(ServerInfo.parse("MySQL", "8.4.10").label()).isEqualTo("MySQL 8.4");
    assertThat(ServerInfo.parse("MariaDB", "11.4.5-MariaDB").full()).isEqualTo("MariaDB 11.4.5");
  }
}
