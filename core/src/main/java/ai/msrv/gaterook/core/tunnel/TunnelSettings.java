package ai.msrv.gaterook.core.tunnel;

/**
 * Settings › Tunnel (setting {@code tunnel}): the provider and its stable address, the single Callers switch and the
 * endpoint the tunnel publishes. The tokens of a named tunnel and of ngrok are secrets of owner {@code tunnel}
 * (write-only, Replace on Secrets); only whether they are stored shows here.
 *
 * @param namedHostname the hostname routed to the named tunnel in Cloudflare ({@code gw.example.com}), or null
 * @param ngrokDomain   a reserved ngrok domain for a stable URL, or null for a random one per start
 * @param anthropicOnly Callers: only Anthropic's IP ranges reach the URL (default on)
 * @param endpoint      the slug of the endpoint the tunnel publishes (it stays chosen while the tunnel is off)
 * @param version       the stored version ({@code 0}: never saved), for optimistic locking of the form
 */
public record TunnelSettings(TunnelProvider provider, String namedHostname, String ngrokDomain, boolean anthropicOnly,
    String endpoint, boolean tunnelTokenStored, boolean ngrokAuthtokenStored, long version) {

  /** Field of the named tunnel's token in the tunnel's secrets. */
  public static final String TUNNEL_TOKEN_FIELD = "/namedTunnelToken";

  /** Field of ngrok's authtoken in the tunnel's secrets. */
  public static final String NGROK_AUTHTOKEN_FIELD = "/ngrokAuthtoken";

  /** The owner id of the tunnel's secrets ({@code secret.owner_kind = 'tunnel'}). */
  public static final String SECRET_OWNER = "tunnel";
}
