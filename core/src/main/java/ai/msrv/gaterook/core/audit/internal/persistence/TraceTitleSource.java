package ai.msrv.gaterook.core.audit.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code trace.title_source}: {@code _meta["ai.msrv.gaterook/task"]}, the first {@code note}, or {@code clientInfo}. */
public enum TraceTitleSource {
  META, NOTE, CLIENT;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<TraceTitleSource> {
    public Converter() {
      super(values());
    }
  }
}
