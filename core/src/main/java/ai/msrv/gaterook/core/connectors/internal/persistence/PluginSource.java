package ai.msrv.gaterook.core.connectors.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

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
