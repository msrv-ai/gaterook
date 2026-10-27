package ai.msrv.gaterook.core.clients.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/** {@code client_link.client}: the four clients the app connects in one click. */
public enum ClientKind {
  CLAUDE_DESKTOP, CODEX, CURSOR, ANTIGRAVITY;

  /** The id stored in rows and grants ({@code claude-desktop}). */
  public String id() {
    return KebabCaseEnumConverter.idOf(this);
  }

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<ClientKind> {
    public Converter() {
      super(values());
    }
  }
}
