package com.gaterook.core.connectors.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

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
