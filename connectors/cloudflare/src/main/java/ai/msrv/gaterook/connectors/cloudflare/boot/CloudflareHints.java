package ai.msrv.gaterook.connectors.cloudflare.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Cloudflare connector: its schemas. The API client is {@code java.net.http}, which the
 * image keeps as other connectors use it.
 */
class CloudflareHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/cloudflare/*.schema.json");
  }
}
