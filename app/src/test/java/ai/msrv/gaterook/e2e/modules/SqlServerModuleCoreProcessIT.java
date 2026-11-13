package ai.msrv.gaterook.e2e.modules;

import org.junit.jupiter.api.Tag;

/**
 * SQL Server as a connector module, against SQL Server 2022 of the Compose services (profile {@code sqlserver}):
 * {@link SqlModuleCoreProcessSteps} with a core built with {@code -Dgateway.withoutBridge}
 * ({@code -Pservices,sqlserver-it}).
 */
@Tag("sqlserver-it")
class SqlServerModuleCoreProcessIT extends SqlModuleCoreProcessSteps {

  SqlServerModuleCoreProcessIT() {
    super(SqlDialectFixture.sqlServer());
  }
}
