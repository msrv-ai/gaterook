package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import java.util.List;
import java.util.Optional;

/**
 * GateRook's own OAuth apps, registered at authorization servers that admit only apps registered with them (neither
 * Client ID Metadata Documents nor dynamic registration), so that the person doesn't have to create one. GateRook signs
 * in there with the device authorization grant (RFC 8628): such a provider takes a public client's device codes and
 * tokens without a client secret, which an app on the person's computer couldn't keep, and the person enters a
 * one-time code on the provider's own page. A client ID is public, as every OAuth client ID is: it names the app on
 * the consent page and authenticates nothing.
 *
 * <p>GitHub is the one today: its authorization code flow takes no client without its secret. GitHub Enterprise Server
 * has an authorization server of its own; an administrator can register an app there and point
 * {@code gateway.mcp-sign-in.github.issuer} and {@code gateway.mcp-sign-in.github.client-id} at it.
 */
public final class GateRookApps {

  /** GitHub's authorization server, as the resource metadata of GitHub's MCP server names it. */
  public static final String GITHUB_ISSUER = "https://github.com/login/oauth";

  /**
   * The client ID of GateRook's OAuth app on GitHub (owned by the gaterook organization), with its device flow enabled
   * and no client secret in use.
   */
  public static final String GITHUB_CLIENT_ID = "Ov23ctr8pxy4jy8NwDha";

  /** One app: the issuer it is registered at and its client ID. */
  public record App(String issuer, String clientId) {}

  private final List<App> apps;

  /** The apps whose client ID isn't blank: an empty one turns that app off. */
  public GateRookApps(List<App> apps) {
    this.apps = apps.stream().filter(a -> a.issuer() != null && !a.issuer().isBlank() && a.clientId() != null
        && !a.clientId().isBlank()).map(a -> new App(Discovery.trimSlash(a.issuer().strip()), a.clientId().strip()))
        .toList();
  }

  /** GateRook's app at {@code issuer}, compared without trailing slashes. */
  public Optional<App> at(String issuer) {
    String wanted = Discovery.trimSlash(issuer);
    return apps.stream().filter(a -> a.issuer().equals(wanted)).findFirst();
  }
}
