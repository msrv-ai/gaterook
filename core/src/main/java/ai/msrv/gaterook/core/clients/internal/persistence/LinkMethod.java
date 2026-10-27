package ai.msrv.gaterook.core.clients.internal.persistence;

import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;

/**
 * {@code client_link.method}: a {@code .mcpb} bundle (Claude Desktop), a bridge entry in a config file, the Codex
 * headers helper, a named token in a config file, or OAuth through the local authorization server.
 */
public enum LinkMethod {
  MCPB, CONFIG_BRIDGE, HELPER, TOKEN, OAUTH;

  @jakarta.persistence.Converter
  public static final class Converter extends KebabCaseEnumConverter<LinkMethod> {
    public Converter() {
      super(values());
    }
  }
}
