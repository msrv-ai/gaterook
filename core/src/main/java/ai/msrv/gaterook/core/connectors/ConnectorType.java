package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import java.util.Objects;

/**
 * One row of the connector catalog as the registry knows it.
 *
 * @param enabled  {@code connector_type.enabled}: a type switched off serves no call and offers no new connection
 * @param verified whether the stored row's MAC verified; an unverified row ("Changed outside GateRook") counts as
 *                 off until the person reviews it
 */
public record ConnectorType(ConnectorManifest manifest, boolean enabled, boolean verified) {

  public ConnectorType {
    Objects.requireNonNull(manifest, "manifest");
  }

  public String id() {
    return manifest.id();
  }

  public ConnectorManifest.Kind kind() {
    return manifest.kind();
  }

  /** Whether calls, tests and new connections may use the type. */
  public boolean usable() {
    return enabled && verified;
  }
}
