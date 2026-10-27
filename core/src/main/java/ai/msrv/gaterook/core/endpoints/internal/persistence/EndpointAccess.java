package ai.msrv.gaterook.core.endpoints.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code endpoint.access}: a {@code read-only} endpoint keeps every mutating pair Off. */
public enum EndpointAccess {
  STANDARD, READ_ONLY;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<EndpointAccess> {
    public Converter() {
      super(values());
    }
  }
}
