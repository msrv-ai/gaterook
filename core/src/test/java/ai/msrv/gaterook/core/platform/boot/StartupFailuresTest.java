package ai.msrv.gaterook.core.platform.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.BindException;
import java.sql.SQLException;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.tomcat.ConnectorStartFailedException;
import org.springframework.boot.web.server.PortInUseException;

class StartupFailuresTest {

  @Test
  void namedFailuresKeepTheirReason() {
    var failure = StartupFailures.classify(new BeanCreationException("x",
        new GatewayStartupException(StartupReason.DB_LOCKED, "Another GateRook core is using /h")), 7465);

    assertThat(failure.reason()).isEqualTo(StartupReason.DB_LOCKED);
    assertThat(failure.detail()).contains("Another GateRook core");
  }

  @Test
  void portFailuresCarryThePort() {
    assertThat(StartupFailures.classify(new PortInUseException(7465), 1).port()).isEqualTo(7465);
    assertThat(StartupFailures.classify(new ConnectorStartFailedException(7470), 1))
        .satisfies(f -> {
          assertThat(f.reason()).isEqualTo(StartupReason.PORT_IN_USE);
          assertThat(f.port()).isEqualTo(7470);
        });
    assertThat(StartupFailures.classify(new IllegalStateException(new BindException("in use")), 7465).port())
        .isEqualTo(7465);
  }

  @Test
  void sqliteBusyIsDbLockedEvenInsideAMigration() {
    SQLException busy = new SQLException("[SQLITE_BUSY] The database file is locked", null, 5);

    assertThat(StartupFailures.classify(new FlywayException("migrate", busy), 1).reason())
        .isEqualTo(StartupReason.DB_LOCKED);
    assertThat(StartupFailures.classify(new FlywayException("bad sql"), 1).reason())
        .isEqualTo(StartupReason.DB_MIGRATION_FAILED);
  }

  @Test
  void anythingElseIsStartupFailedWithoutItsMessage() {
    var failure = StartupFailures.classify(new IllegalStateException("password=hunter2"), 1);

    assertThat(failure.reason()).isEqualTo(StartupReason.STARTUP_FAILED);
    assertThat(failure.detail()).doesNotContain("hunter2").contains("IllegalStateException");
  }
}
