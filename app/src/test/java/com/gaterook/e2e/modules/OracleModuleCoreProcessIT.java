package com.gaterook.e2e.modules;

/**
 * Oracle Database as a connector module, against Oracle Database Free of the Compose services (profile
 * {@code oracle}): {@link SqlModuleCoreProcessSteps} with a core built with {@code -Dgateway.withoutBridge}
 * ({@code -Pservices}).
 */
class OracleModuleCoreProcessIT extends SqlModuleCoreProcessSteps {

  OracleModuleCoreProcessIT() {
    super(SqlDialectFixture.oracle());
  }
}
