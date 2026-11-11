package ai.msrv.gaterook.connectors.trino.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Trino connector: its own schemas. The client is {@code java.net.http} and Jackson trees,
 * which need no reflection.
 */
class TrinoHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/trino/*.schema.json");
  }
}
