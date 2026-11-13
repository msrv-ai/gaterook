package com.gaterook.core.connectors.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code plugin.source}: where the installed JAR came from. */
public enum PluginSource {
  FILE, URL, CATALOG;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<PluginSource> {
    public Converter() {
      super(values());
    }
  }
}
