package com.gaterook.e2e.modules;

/**
 * The bridge of Oracle Database, against Oracle Database Free of the Compose services (profile {@code oracle}):
 * {@link SqlBridgeCoreProcessSteps} with the core as this release builds it ({@code -Pservices}).
 */
class OracleBridgeCoreProcessIT extends SqlBridgeCoreProcessSteps {

  OracleBridgeCoreProcessIT() {
    super(SqlDialectFixture.oracle());
  }
}
