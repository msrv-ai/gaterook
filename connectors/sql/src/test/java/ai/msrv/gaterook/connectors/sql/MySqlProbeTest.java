package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MySqlProbeTest {

  @Test
  void readsMysqlAndMariadbRoleGrants() {
    assertThat(MySqlProbe.rolesInGrant("GRANT `reader`@`%`, `writer`@`localhost` TO `app`@`%`"))
        .containsExactly("`reader`@`%`", "`writer`@`localhost`");
    assertThat(MySqlProbe.rolesInGrant("grant `reader` TO `app`@`%`"))
        .containsExactly("`reader`");
    assertThat(MySqlProbe.rolesInGrant("GRANT `reader` \t,\t `writer` TO `app`@`%`"))
        .containsExactly("`reader`", "`writer`");
    assertThat(MySqlProbe.rolesInGrant("GRANT SELECT ON `app`.* TO `reader`@`%`"))
        .isEmpty();
  }

  @Test
  void rejectsIncompleteAndAmbiguousRoleListsInLinearTime() {
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      assertThat(MySqlProbe.rolesInGrant("GRANT `_`" + " `_`".repeat(100_000))).isEmpty();
      assertThat(MySqlProbe.rolesInGrant("GRANT " + "`reader`, ".repeat(100_000))).isEmpty();
    });
    assertThat(MySqlProbe.rolesInGrant("GRANT `reader`, TO `app`@`%`")).isEmpty();
    assertThat(MySqlProbe.rolesInGrant("GRANT `reader` trailing TO `app`@`%`")).isEmpty();
  }
}
