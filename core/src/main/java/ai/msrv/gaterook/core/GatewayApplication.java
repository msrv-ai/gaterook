package ai.msrv.gaterook.core;

import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * The MCP Gateway core.
 *
 * <p>Every direct subpackage is a Spring Modulith module (ARCHITECTURE.md §2.2) whose public API is the package
 * root; {@code platform} is shared by all of them. {@code connector-api} and {@code ai.msrv.gaterook.connectors.*} lie
 * outside this package, so for Modulith they are libraries: the core reaches connectors only through
 * {@code Connector} beans that the connector modules register with their own auto-configurations.
 */
@SpringBootApplication
@Modulithic(sharedModules = "platform")
public class GatewayApplication {

  /** Started by the desktop app with a bootstrap line on stdin (ARCHITECTURE.md §7.1); see {@link GatewayLauncher}. */
  public static void main(String[] args) {
    GatewayLauncher.launch(GatewayApplication.class, args);
  }
}
