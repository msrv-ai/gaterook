package ai.msrv.gaterook.connectors.prometheus.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Prometheus connector; they live with the module, not in the core (ARCHITECTURE.md §2.3).
 * The connector uses no reflection: its answers are Jackson trees, and its HTTP client is the JDK's.
 */
class PrometheusHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/prometheus/*.schema.json");
  }
}
