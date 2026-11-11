package ai.msrv.gaterook.connectors.databricks.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Databricks connector: its schemas. It calls the workspace with {@code java.net.http} and
 * Jackson trees only, which need no reflection.
 */
class DatabricksHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/databricks/*.schema.json");
  }
}
