package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Microsoft identity platform (Microsoft Entra ID's v2.0 endpoints) as the authorization server of an MCP server,
 * as Microsoft's remote Azure DevOps server ({@code https://mcp.dev.azure.com/<organization>}) names it. Its OAuth
 * departs from what the MCP authorization specification expects in four ways, and GateRook makes four exceptions for
 * it (ARCHITECTURE.md §7.6). They hold only for an issuer {@code https://<host>/<tenant>/v2.0} on one of Microsoft's
 * own sign-in hosts ({@link #HOSTS}), without user info, query or another port, whose authorization and token endpoints
 * are on the same host ({@link #serves}); any other host, a lookalike or a subdomain included, gets none of them.
 *
 * <ol>
 *   <li><b>PKCE.</b> Its metadata (OpenID Connect Discovery only) doesn't list {@code code_challenge_methods_supported},
 *       though its authorization code flow takes PKCE with S256, as Microsoft documents. GateRook sends S256 there as
 *       everywhere else, and would still refuse a list without it.</li>
 *   <li><b>No {@code resource}.</b> It takes the token's audience from the scopes
 *       ({@code https://mcp.dev.azure.com/.default}) and refuses the RFC 8707 {@code resource} parameter beside scopes
 *       of another resource (AADSTS9010010). GateRook leaves the parameter out there and sends the scopes with every
 *       token request, renewals included: a renewal without them gets a token for Microsoft Graph. The scopes then bind
 *       the token to its audience, so {@link #audienceProblem} holds them to the rule the resource metadata follows
 *       ({@link Discovery#resourceAllowed}): each names the MCP server.</li>
 *   <li><b>Public clients.</b> Its token endpoint lists no {@code none} among its authentication methods, yet an app
 *       registered with a "Mobile and desktop applications" redirect URI is a public client there: the person's app
 *       needs no client secret.</li>
 *   <li><b>Redirect.</b> It has neither dynamic registration nor client ID metadata documents, so the person registers
 *       an app in their tenant. The Entra admin center doesn't take an {@code http://127.0.0.1} redirect URI (only the
 *       app's manifest does) but takes {@code http://localhost/callback}, and Entra matches a loopback redirect URI with
 *       any port: the sign-in listens on any free port of {@code localhost}.</li>
 * </ol>
 */
public final class MicrosoftIdentityPlatform {

  /**
   * The hosts of its issuers, as their metadata names them (checked 2026-11-11): the global service, Azure Government,
   * and Microsoft Azure operated by 21Vianet. Their aliases ({@code login.windows.net}, {@code login.microsoft.com},
   * {@code login.chinacloudapi.cn}) serve metadata that names one of these as its issuer, which the issuer check of
   * RFC 8414 doesn't take for the alias.
   */
  static final Set<String> HOSTS = Set.of("login.microsoftonline.com", "login.microsoftonline.us",
      "login.partner.microsoftonline.cn");

  /** OpenID Connect's scopes: they name no resource and come with any resource's token. */
  private static final Set<String> OPENID_SCOPES = Set.of("openid", "profile", "email", "offline_access");

  /** A tenant: its ID, one of its domains, or {@code common}, {@code organizations}, {@code consumers}. */
  private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?");

  private static final String V2 = "/v2.0";

  private MicrosoftIdentityPlatform() {}

  /** Whether {@code issuer} is a v2.0 issuer on one of Microsoft's sign-in hosts: {@code https://<host>/<tenant>/v2.0}. */
  public static boolean issuer(String issuer) {
    URI u = parse(issuer);
    if (!onSignInHost(u) || u.getRawQuery() != null || u.getRawFragment() != null || u.getRawPath() == null) {
      return false;
    }
    String path = Discovery.trimSlash(u.getRawPath());
    return path.length() > V2.length() + 1 && path.endsWith(V2)
        && TENANT.matcher(path.substring(1, path.length() - V2.length())).matches();
  }

  /**
   * Whether an authorization server is the Microsoft identity platform: its issuer ({@link #issuer}), and its
   * authorization and token endpoints on the issuer's host.
   */
  public static boolean serves(String issuer, URI authorization, URI token) {
    return issuer(issuer) && sameHost(issuer, authorization) && sameHost(issuer, token);
  }

  /** {@link #serves} for a stored sign-in, which keeps its issuer and token endpoint. */
  public static boolean renews(String issuer, URI token) {
    return issuer(issuer) && sameHost(issuer, token);
  }

  /**
   * Why a token the Microsoft identity platform issues for {@code scope} wouldn't be for the MCP server at
   * {@code serverUrl}, or {@code null} when it is. Without the {@code resource} parameter the scopes name the token's
   * audience: {@code https://mcp.dev.azure.com/.default} is for {@code https://mcp.dev.azure.com}, the part before its
   * last slash. So at least one scope names a resource, and every scope that names one names the server: its origin, or
   * its URL or an ancestor path of it there, as for the resource metadata ({@link Discovery#resourceAllowed}). A scope
   * without a resource ({@code User.Read}) is Microsoft Graph's, and an application ID ({@code 00000003-…/.default},
   * {@code api://…/access}) names no server GateRook can check; only OpenID Connect's scopes name none and pass.
   * Otherwise a server could have GateRook ask for a token of another API, such as Microsoft Graph, and receive it.
   */
  public static String audienceProblem(String scope, URI serverUrl) {
    String origin = Discovery.trimSlash(Discovery.origin(serverUrl).toString());
    String host = OAuthHttp.host(serverUrl);
    boolean named = false;
    for (String s : scope == null ? new String[0] : scope.trim().split(" +")) {
      if (s.isEmpty() || OPENID_SCOPES.contains(s.toLowerCase(Locale.ROOT))) {
        continue;
      }
      if (!namesServer(s, serverUrl)) {
        return "Microsoft's sign-in issues a token for the API its scopes name, and GateRook sends that token to "
            + host + ". The scope " + OAuthHttp.clean(s) + " names another API, so GateRook doesn't ask for it. Ask "
            + "only for scopes of " + origin + ", such as " + origin + "/.default, under Scopes in the server's "
            + "sign-in settings.";
      }
      named = true;
    }
    if (!named) {
      return "Microsoft's sign-in issues a token for the API its scopes name, and no scope names " + host + ". Enter "
          + origin + "/.default under Scopes in the server's sign-in settings.";
    }
    return null;
  }

  private static boolean namesServer(String scope, URI serverUrl) {
    int slash = scope.lastIndexOf('/');
    if (slash <= 0) {
      return false;
    }
    String resource = scope.substring(0, slash);
    URI r = parse(resource);
    return r != null && r.getRawUserInfo() == null && r.getRawQuery() == null && r.getRawFragment() == null
        && Discovery.resourceAllowed(serverUrl, resource);
  }

  private static boolean sameHost(String issuer, URI endpoint) {
    return onSignInHost(endpoint) && endpoint.getHost().equalsIgnoreCase(parse(issuer).getHost());
  }

  /** HTTPS on one of {@link #HOSTS}, exactly, on the default port and without user info. */
  private static boolean onSignInHost(URI u) {
    return u != null && "https".equalsIgnoreCase(u.getScheme()) && u.getRawUserInfo() == null && u.getHost() != null
        && (u.getPort() == -1 || u.getPort() == 443) && HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT));
  }

  private static URI parse(String s) {
    if (s == null) {
      return null;
    }
    try {
      return new URI(s);
    } catch (URISyntaxException e) {
      return null;
    }
  }
}
