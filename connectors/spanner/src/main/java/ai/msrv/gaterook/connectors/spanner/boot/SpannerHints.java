package ai.msrv.gaterook.connectors.spanner.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Spanner connector. Google's credentials are covered by the hints of connectors/cloud-auth
 * and the GCP connector; what is left are the connector's own schemas.
 */
class SpannerHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/spanner/*.schema.json");
  }
}
