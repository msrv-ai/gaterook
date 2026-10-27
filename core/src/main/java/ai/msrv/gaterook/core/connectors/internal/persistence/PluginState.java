package ai.msrv.gaterook.core.connectors.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code plugin.state}. */
public enum PluginState {
  INSTALLED, DISABLED, REVOKED, FAILED;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<PluginState> {
    public Converter() {
      super(values());
    }
  }
}
