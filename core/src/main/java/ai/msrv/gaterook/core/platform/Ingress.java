package ai.msrv.gaterook.core.platform;

/**
 * Where a call entered the core. Tokens are bound to their ingress: a token presented through another ingress is
 * rejected with 401 (ARCHITECTURE.md §5 step 2, §7.3).
 */
public enum Ingress {

  /**
   * {@code 127.0.0.1:7465}: local MCP clients with {@code gwt_}/{@code gwb_} tokens or tokens of the local OAuth
   * server.
   */
  LOCAL,

  /** The window's Playground: principal {@code playground}, same pipeline as every other call. */
  PLAYGROUND,

  /** {@code 127.0.0.1:7466} behind the tunnel: only OAuth tokens of the tunnel's authorization server. */
  TUNNEL;

  /**
   * Request attribute the tunnel's ingress filter sets on what it lets through on the public port; the local guard
   * refuses every request there without it (ARCHITECTURE.md §5 step 1).
   */
  public static final String TUNNEL_ACCEPTED_ATTRIBUTE = "ai.msrv.gaterook.publicIngressAccepted";
}
