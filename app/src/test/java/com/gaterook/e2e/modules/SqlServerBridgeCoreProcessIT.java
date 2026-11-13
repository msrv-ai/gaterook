package com.gaterook.e2e.modules;

import org.junit.jupiter.api.Tag;

/**
 * The bridge of SQL Server, against SQL Server 2022 of the Compose services (profile {@code sqlserver}):
 * {@link SqlBridgeCoreProcessSteps} with the core as this release builds it ({@code -Pservices,sqlserver-it}).
 */
@Tag("sqlserver-it")
class SqlServerBridgeCoreProcessIT extends SqlBridgeCoreProcessSteps {

  SqlServerBridgeCoreProcessIT() {
    super(SqlDialectFixture.sqlServer());
  }
}
