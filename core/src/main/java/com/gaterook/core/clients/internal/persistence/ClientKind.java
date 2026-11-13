package com.gaterook.core.clients.internal.persistence;

import com.gaterook.core.platform.KebabCaseEnumConverter;

/** Known desktop clients; detection does not imply that a client supports MCP integration. */
public enum ClientKind {
  CLAUDE_DESKTOP, CODEX, CURSOR, ANTIGRAVITY, VSCODE, MICROSOFT_COPILOT;

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
