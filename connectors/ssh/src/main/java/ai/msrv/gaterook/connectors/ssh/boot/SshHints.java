package ai.msrv.gaterook.connectors.ssh.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the SSH connector; they live with the module, not in the core (ARCHITECTURE.md §2.3).
 */
class SshHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool, connection and policy schemas are read with getResourceAsStream: null in native without this hint.
    hints.resources().registerPattern("connectors/ssh/*.schema.json");
  }
}
