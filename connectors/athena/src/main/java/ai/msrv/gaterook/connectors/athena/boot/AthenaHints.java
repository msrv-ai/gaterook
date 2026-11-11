package ai.msrv.gaterook.connectors.athena.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Athena connector. The AWS SDK's signer and STS client are covered by the hints of
 * connectors/cloud-auth and the logs connector; what is left are the connector's own schemas.
 */
class AthenaHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/athena/*.schema.json");
  }
}
