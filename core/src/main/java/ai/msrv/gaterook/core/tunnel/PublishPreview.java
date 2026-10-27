package ai.msrv.gaterook.core.tunnel;

import java.util.List;

/**
 * {@code POST /api/tunnel/publish/preview}: exactly what becomes reachable from the internet and what stays on this
 * computer, for the confirmation dialog (kit {@code gw-dialog--warning}, BLUEPRINT §9).
 *
 * @param publicPaths  {@code /mcp/<slug>}, {@code /oauth/*}, {@code /.well-known/oauth-*}
 * @param local        what stays local: the UI and admin API, the other endpoints
 * @param risk         the endpoint's risk callout in words, or null
 * @param refusal      why it can't be published (prod connections, disabled, another endpoint is public), or null
 * @param warnings     what the person should know (quick tunnel address changes, any caller, DCR)
 */
public record PublishPreview(String endpoint, String env, TunnelProvider provider, List<String> publicPaths,
    List<String> local, List<Connection> connections, Tools tools, String risk, String refusal,
    boolean anthropicOnly, List<String> warnings) {

  public record Connection(String name, String env) {}

  /** The endpoint's tools by mode. */
  public record Tools(int read, int approval, int off) {}
}
