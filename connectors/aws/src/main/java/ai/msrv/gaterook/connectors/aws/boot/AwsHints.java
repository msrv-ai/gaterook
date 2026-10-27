package ai.msrv.gaterook.connectors.aws.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the AWS connector; they live with the module, not in the core (ARCHITECTURE.md §2.3).
 */
class AwsHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool, connection and policy schemas are read with getResourceAsStream: null in native without this hint.
    hints.resources().registerPattern("connectors/aws/*.schema.json");
  }
}
