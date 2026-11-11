package ai.msrv.gaterook.core.mcpservers;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.AwsSigV4;
import ai.msrv.gaterook.connectors.cloudauth.GoogleApis;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.CallContext;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.AppBinding;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.Discovery;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.GateRookApps;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.LoopbackCallback;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.MicrosoftIdentityPlatform;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.OAuthClient;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.OAuthHttp;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.Pkce;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.SignInBundle;
import ai.msrv.gaterook.core.mcpservers.internal.oauth.WwwAuthenticate;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SecretFingerprints;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.SecretOwner;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * OAuth sign-ins to remote MCP servers (ARCHITECTURE.md §7.5): the person adds a server by its URL, signs in in their
 * own browser as they would in Claude Desktop, Cursor or Codex, and GateRook — not the AI app — holds the tokens. The
 * agent never sees them; every request of the server's HTTP session carries them, renewed before they expire.
 *
 * <p>A sign-in is a flow kept in memory for at most {@link #FLOW_TTL}: discovery (the server's 401 challenge, its
 * resource metadata, its authorization server's metadata), a client (GateRook by its published Client ID Metadata
 * Document where the authorization server accepts one, else registered dynamically under its own name, or the
 * person's OAuth app), PKCE S256 and a {@code state}, and a redirect URI on this computer that GateRook itself listens
 * on ({@link LoopbackCallback}). Authorization servers that accept only redirect URIs on this computer —
 * the kind the AI apps' command-line clients use — therefore accept GateRook's too, and the code comes back to
 * GateRook, never to the app. When the browser can't come back (another device, a blocked port), the person pastes the
 * address it ended on; its {@code state} must match.
 *
 * <p>An authorization server that admits neither the metadata document nor a registration, but where GateRook has its
 * own app ({@link GateRookApps}: GitHub), gets a device sign-in instead (RFC 8628): the flow asks for a one-time code,
 * the person enters it on the provider's page, and the flow polls the token endpoint until they allowed access. There
 * is no redirect and no listener, so the browser may be on any device.
 *
 * <p>The Microsoft identity platform (Microsoft Entra ID) admits only the person's app, registered in their tenant as a
 * public client with the redirect URI {@code http://localhost/callback} on any port, and takes the token's audience
 * from the scopes instead of the {@code resource} parameter, so the scopes must name the server
 * ({@link MicrosoftIdentityPlatform}).
 *
 * <p>The tokens of a stored server are one encrypted secret of the server ({@link SignInBundle}), renewed in place:
 * no Setup change, no new connection version, so a renewal never restarts anything. A renewal the server refuses
 * (revoked, expired, more permissions wanted) leaves the server "needs sign-in" until the person signs in again; its
 * calls don't run meanwhile and say so.
 */
@Service
public class McpServerSignIns {

  /** The field of a server's sign-in secret ({@code secret.owner_kind = 'mcp-server'}). */
  public static final String FIELD = "/oauth";
  /**
   * The field of where the client secret of the person's OAuth app may go ({@link AppBinding}): kept when the server
   * signs out, deleted with the server.
   */
  public static final String APP_FIELD = "/oauth-app";
  static final Duration FLOW_TTL = Duration.ofMinutes(10);
  static final int MAX_FLOWS = 8;
  /** A token is renewed this long before it expires. */
  static final Duration RENEW_MARGIN = Duration.ofSeconds(60);
  /**
   * A token is renewed before it expires at most this often: an authorization server that issues tokens for a second
   * would otherwise be asked for new ones at every request. A token the server refuses is renewed at once.
   */
  static final Duration MIN_RENEW_INTERVAL = Duration.ofSeconds(60);
  /** The tokens of one sign-in that stay masked: the access and refresh tokens of the last four renewals. */
  static final int KEEP_TOKENS = 8;
  static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
  /** The callback port of an OAuth app the person registers, unless they choose another. */
  public static final int APP_CALLBACK_PORT = 7471;
  static final String DEFAULT_PATH = "/callback";

  /** Polls of a device sign-in in a row that may fail to reach the server before the sign-in fails. */
  static final int MAX_POLL_TROUBLE = 5;

  private static final Logger log = LoggerFactory.getLogger(McpServerSignIns.class);
  /** Refusals of a renewal that call for a new sign-in; {@code bad_refresh_token} is GitHub's. */
  private static final Set<String> REFUSED_GRANT = Set.of("invalid_grant", "invalid_client", "unauthorized_client",
      "invalid_scope", "access_denied", "bad_refresh_token");

  /**
   * How a server wants to be signed in to, for the review before it is added.
   *
   * @param cloud        {@code google} or {@code aws} for a server on that cloud, which can sign in with its cloud's
   *                     credentials instead (§7.6); otherwise {@code null}
   * @param secretRequired over an OAuth app, whether the authorization server takes it only with its client secret
   *                       (no public clients, as Google's); {@code null} without an app
   * @param tokenEndpoint  where a sign-in sends the client secret of the person's OAuth app: the review sends it back
   *                       with the issuer when the person signs in with one ({@link AppBinding.Server})
   */
  public record AuthView(String kind, String issuer, String authorizationHost, String scope, String registration,
      String redirectUri, String message, String cloud, Boolean secretRequired, String tokenEndpoint) {

    public AuthView(String kind, String issuer, String authorizationHost, String scope, String registration,
        String redirectUri, String message, String cloud, Boolean secretRequired) {
      this(kind, issuer, authorizationHost, scope, registration, redirectUri, message, cloud, secretRequired, null);
    }

    public AuthView(String kind, String issuer, String authorizationHost, String scope, String registration,
        String redirectUri, String message) {
      this(kind, issuer, authorizationHost, scope, registration, redirectUri, message, null, null, null);
    }
  }

  /**
   * The authorization server a review showed ({@link AuthView#issuer}, {@link AuthView#tokenEndpoint}), sent back by
   * the sign-in of a server not yet added when the person typed the client secret of their OAuth app: the secret goes
   * to that one only.
   */
  public record Reviewed(String issuer, String tokenEndpoint) {}

  /**
   * One sign-in in progress.
   *
   * @param state            {@code waiting} (the browser is open), {@code signed-in}, {@code failed}, {@code cancelled}
   *                         or {@code expired}
   * @param authorizationUrl the page the browser opens, for copying when it can't be opened
   * @param userCode         a device sign-in's one-time code, which the person enters on that page, while it waits;
   *                         otherwise {@code null}
   */
  public record SignInView(String id, String state, String server, String issuer, String authorizationHost,
      String redirectUri, String authorizationUrl, Instant expiresAt, String scope, String message, String userCode) {}

  /**
   * The sign-in of a stored server.
   *
   * @param state {@code signed-in}, {@code needs-sign-in}, {@code signed-out}, or {@code none} for a server without
   *              one
   */
  public record StatusView(String server, String state, String issuer, String scope, Instant expiresAt,
      boolean renewable, Instant signedInAt, Instant renewedAt, String message, boolean registered,
      String redirectUri) {}

  /**
   * What a sign-in is for: a draft (the review of Add MCP server) or a stored server, its URL and the person's
   * choices ({@code auth} of its config).
   */
  record Target(ConnectionConfig cfg, String connectionId, String name, URI url, JsonNode auth, char[] clientSecret,
      String secretVersion, AppBinding.Server reviewed) {

    /**
     * @param secretVersion which stored client secret a stored server has ({@code <secret id>@<version>}), or
     *                      {@code null}
     * @param reviewed      the authorization server the review showed when the person typed the client secret of a
     *                      server not yet added, or {@code null}
     */
    Target {}
  }

  private final SecretService secrets;
  private final CallContexts contexts;
  private final EnvironmentPolicies policies;
  private final ConnectionConfigs configs;
  private final EventBus events;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ObjectProvider<McpServerRuntime> runtime;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final GateRookApps apps;
  private final Map<String, Flow> flows = new ConcurrentHashMap<>();
  private final Map<String, Holder> holders = new ConcurrentHashMap<>();

  /**
   * @param githubIssuer   the authorization server of GateRook's GitHub app: GitHub's, or a GitHub Enterprise Server's
   * @param githubClientId that app's client ID; empty turns it off, and GitHub's MCP server then needs the person's app
   */
  public McpServerSignIns(SecretService secrets, CallContexts contexts, EnvironmentPolicies policies,
      ConnectionConfigs configs, EventBus events, ObjectProvider<SetupChangeRecorder> setupChanges,
      ObjectProvider<McpServerRuntime> runtime, Clock clock, PlatformTransactionManager transactions,
      @Value("${gateway.mcp-sign-in.github.issuer:" + GateRookApps.GITHUB_ISSUER + "}") String githubIssuer,
      @Value("${gateway.mcp-sign-in.github.client-id:" + GateRookApps.GITHUB_CLIENT_ID + "}") String githubClientId) {
    this.secrets = secrets;
    this.contexts = contexts;
    this.policies = policies;
    this.configs = configs;
    this.events = events;
    this.setupChanges = setupChanges;
    this.runtime = runtime;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.apps = new GateRookApps(List.of(new GateRookApps.App(githubIssuer, githubClientId)));
  }

  // ── Review ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * How the server of {@code cfg} wants clients to sign in, looked at without signing in: {@code none} (it answered
   * without one), {@code oauth} (with where and how), {@code refused} (a key in a header, probably) or
   * {@code unknown}. Never throws for the server's sake: the review shows what was found.
   */
  AuthView discover(ConnectionConfig cfg, URI url) {
    try {
      Discovery.Result d = Discovery.discover(httpOf(cfg), url);
      String cloud = cloud(url);
      return switch (d.kind()) {
        case NONE -> new AuthView("none", null, null, null, null, null, null, cloud, null);
        case REFUSED -> new AuthView("refused", null, null, null, null, null, d.message(), cloud, null);
        case UNKNOWN -> new AuthView("unknown", null, null, null, null, null, d.message(), cloud, null);
        case OAUTH, OPTIONAL -> review(d, cfg.config().path("auth"), cloud, ownApp(d.server()).isPresent());
      };
    } catch (OAuthHttp.Failure e) {
      return new AuthView("unknown", null, null, null, null, null, e.getMessage(), cloud(url), null);
    } finally {
      if (cfg.id().startsWith("draft-")) {
        contexts.evict(cfg.id());
      }
    }
  }

  /**
   * The review of a server that offers an OAuth sign-in: how GateRook becomes its client, whether the person's OAuth
   * app needs its client secret, the redirect URI to register in it, and what to do when the person needs one.
   *
   * @param gaterookApp GateRook has its own app at the server's authorization server ({@link GateRookApps})
   */
  static AuthView review(Discovery.Result d, JsonNode auth, String cloud, boolean gaterookApp) {
    Discovery.Server s = d.server();
    boolean app = auth.path("clientId").isString();
    // GateRook's own app comes after the ways that need nothing registered in advance: those sign in through the
    // browser's redirect, without a code to enter.
    String registration = app ? "app"
        : OAuthClient.acceptsMetadataDocument(s, redirectFor(auth, false)) ? "metadata-document"
        : s.dynamicRegistration() ? "dynamic"
        : gaterookApp ? "gaterook-app" : "app-required";
    boolean appRequired = "app-required".equals(registration);
    Boolean secret = app || appRequired ? !OAuthClient.publicClients(s) : null;
    // A Desktop app client of Google takes any loopback address: there is nothing to register.
    boolean google = "google".equals(cloud);
    boolean microsoft = s.microsoftIdentityPlatform();
    String redirect = (app || appRequired) && !google ? appRedirectToRegister(auth, s) : null;
    String scope = scope(d, auth);
    String message = !appRequired ? null : google ? "Google doesn't let apps register themselves. Sign in with Google "
        + "Cloud credentials instead, or create an OAuth client of the Desktop app type in Google Cloud Console (APIs & "
        + "Services › Credentials) and enter its client ID and client secret."
        : microsoft ? entraAppNeeded(redirect) + " Then enter its Application (client) ID. It needs no client secret."
        : s.issuer() + " doesn't let apps register themselves. Create an OAuth app there for GateRook with the "
            + "redirect URI " + redirect + ", then enter its client ID" + (Boolean.TRUE.equals(secret)
                ? " and client secret." : ".");
    String audience = microsoft ? MicrosoftIdentityPlatform.audienceProblem(scope, d.serverUrl()) : null;
    if (audience != null) {
      message = message == null ? audience : message + " " + audience;
    }
    return new AuthView(d.kind() == Discovery.Kind.OPTIONAL ? "optional" : "oauth", s.issuer(),
        s.authorization().getHost(), scope, registration, redirect, message, cloud, secret, s.token().toString());
  }

  /** What the person sets up in Microsoft Entra ID for a sign-in at the Microsoft identity platform. */
  private static String entraAppNeeded(String redirect) {
    return "Microsoft Entra ID doesn't let apps register themselves. Register an app for GateRook in your tenant "
        + "(Microsoft Entra admin center › App registrations): add the redirect URI " + redirect + " under Mobile and "
        + "desktop applications, turn on Allow public client flows, and add the API permissions of the server, with "
        + "admin consent.";
  }

  // ── Sign-in flows ───────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Starts a sign-in: the browser is to open {@link SignInView#authorizationUrl} (Electron opens it from
   * {@link #authorizationUrl}), and the redirect comes back to a listener of this flow.
   *
   * @throws ProblemException 422 when the server doesn't want an OAuth sign-in or can't take GateRook as a client
   */
  SignInView start(Target t) {
    sweep();
    try {
      flows.values().stream().filter(f -> f.name.equals(t.name()) && f.phase.equals("waiting")).toList()
          .forEach(f -> f.endIfWaiting("cancelled", "Replaced by a new sign-in."));
      if (flows.size() >= MAX_FLOWS) {
        throw ProblemException.conflict("Too many sign-ins", "Finish or cancel a sign-in that is waiting first.");
      }
      Flow flow = begin(t);
      flows.put(flow.id, flow);
      flow.startPolling();
      publish(flow);
      return flow.view();
    } catch (OAuthHttp.Failure e) {
      throw ProblemException.unprocessable("Can't sign in", e.getMessage(), "/config/url");
    } finally {
      if (t.clientSecret() != null) {
        Arrays.fill(t.clientSecret(), '\0');
      }
    }
  }

  private Flow begin(Target t) throws OAuthHttp.Failure {
    OAuthHttp http = httpOf(t.cfg());
    Discovery.Result d = Discovery.discover(http, t.url());
    switch (d.kind()) {
      case NONE -> throw new OAuthHttp.Failure(t.name() + " answers without a sign-in, so there's nothing to sign in "
          + "to. Connect it without one.");
      case REFUSED -> throw new OAuthHttp.Failure(t.name() + " doesn't offer an OAuth sign-in. " + d.message()
          + " Add its key as a header instead.");
      case UNKNOWN -> throw new OAuthHttp.Failure(d.message());
      default -> {
        // OAuth, required or optional.
      }
    }
    JsonNode auth = t.auth();
    String scope = scope(d, auth);
    requireAudience(d, scope, t.url());
    String clientId = auth.path("clientId").isString() ? auth.path("clientId").asString() : null;
    String secret = t.clientSecret() == null || t.clientSecret().length == 0 ? null : new String(t.clientSecret());
    if (clientId != null && secret != null) {
      requireAppServer(t, d.server(), clientId, secret);
    }
    Flow flow = new Flow(Ids.next("msi_"), t.connectionId(), t.name(), t.cfg(), d, scope, fingerprint(t.cfg()));
    flow.secretVersion = t.secretVersion();
    try {
      if (clientId != null) {
        flow.listen(appRedirect(auth, d.server()));
        flow.client = OAuthClient.preRegistered(d.server(), clientId, secret, flow.callback.redirectUri());
      } else if (OAuthClient.acceptsMetadataDocument(d.server(), redirectFor(auth, false))) {
        // Nothing to register, which the MCP authorization spec prefers to the deprecated dynamic registration: the
        // authorization server reads GateRook's published document, which lists this redirect for any port.
        flow.listen(redirectFor(auth, false));
        flow.client = OAuthClient.metadataDocument(flow.callback.redirectUri());
      } else {
        OAuthClient.Client previous = previousClient(t, d);
        Optional<GateRookApps.App> own = ownApp(d.server());
        if (previous != null && flow.tryListen(URI.create(previous.redirectUri()))) {
          flow.client = previous;
        } else if (d.server().dynamicRegistration()) {
          flow.client = register(http, d, flow, auth);
        } else if (own.isPresent()) {
          // A device sign-in: a code to enter on the provider's page, no redirect to listen for.
          flow.client = OAuthClient.ownApp(own.get().clientId());
          flow.device = OAuthClient.deviceAuthorization(http, d.server(), flow.client, scope, d.resource(),
              clock.instant());
          flow.authorizationUrl = flow.device.verificationUriComplete() != null
              ? flow.device.verificationUriComplete() : flow.device.verificationUri();
          Instant latest = clock.instant().plus(FLOW_TTL);
          flow.expiresAt = flow.device.expiresAt().isBefore(latest) ? flow.device.expiresAt() : latest;
          return flow;
        } else if (d.server().microsoftIdentityPlatform()) {
          throw new OAuthHttp.Failure(entraAppNeeded(appRedirectToRegister(auth, d.server())) + " Then enter its "
              + "Application (client) ID in the server's sign-in settings.");
        } else {
          throw new OAuthHttp.Failure(d.server().issuer() + " doesn't let apps register themselves. Create an OAuth "
              + "app there for GateRook with the redirect URI " + redirectFor(auth, true) + ", then enter its "
              + "client ID in the server's sign-in settings.");
        }
      }
    } catch (OAuthHttp.Failure | RuntimeException e) {
      flow.close();
      throw e;
    }
    flow.pkce = Pkce.create();
    flow.state = Pkce.random(32);
    flow.authorizationUrl = OAuthClient.authorizationUrl(d.server(), flow.client, flow.pkce, flow.state, scope,
        d.resource());
    flow.expiresAt = clock.instant().plus(FLOW_TTL);
    return flow;
  }

  /**
   * At the Microsoft identity platform, which gets no {@code resource} parameter, only the scopes say whom the token is
   * for: a sign-in starts only when they name the server ({@link MicrosoftIdentityPlatform#audienceProblem}).
   */
  static void requireAudience(Discovery.Result d, String scope, URI url) throws OAuthHttp.Failure {
    if (d.server().microsoftIdentityPlatform()) {
      String audience = MicrosoftIdentityPlatform.audienceProblem(scope, url);
      if (audience != null) {
        throw new OAuthHttp.Failure(audience);
      }
    }
  }

  /**
   * Registers GateRook for the flow's redirect URI. An authorization server that refuses {@code 127.0.0.1} gets
   * {@code localhost} on the same port (and the other way round), unless the person chose the host.
   */
  private OAuthClient.Client register(OAuthHttp http, Discovery.Result d, Flow flow, JsonNode auth)
      throws OAuthHttp.Failure {
    flow.listen(redirectFor(auth, false));
    try {
      return OAuthClient.register(http, d.server(), flow.callback.redirectUri());
    } catch (OAuthHttp.Failure e) {
      if (auth.path("callbackHost").isString() || !redirectRefused(e)) {
        throw e;
      }
      URI first = flow.callback.redirectUri();
      String other = first.getHost().equals("localhost") ? "127.0.0.1" : "localhost";
      flow.closeListener();
      flow.listen(URI.create("http://" + other + ":" + first.getPort() + first.getRawPath()));
      return OAuthClient.register(http, d.server(), flow.callback.redirectUri());
    }
  }

  private static boolean redirectRefused(OAuthHttp.Failure e) {
    String error = e.error() == null ? "" : e.error();
    return error.equals("invalid_redirect_uri") || error.equals("invalid_client_metadata")
        || String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("redirect");
  }

  /** The client a stored server registered before, reused when its redirect URI can be listened on again. */
  private OAuthClient.Client previousClient(Target t, Discovery.Result d) {
    if (t.connectionId() == null) {
      return null;
    }
    SignInBundle b = holder(t.connectionId()).load();
    // The client and its secret were issued by one authorization server: they go to it alone, by its issuer and its
    // token endpoint, which a server's own metadata could otherwise give to another issuer's name.
    if (b == null || !b.client().registered() || b.client().redirectUri() == null
        || !d.server().issuer().equals(b.issuer()) || b.tokenEndpoint() == null
        || !Discovery.canonical(b.tokenEndpoint()).equals(Discovery.canonical(d.server().token()))) {
      return null;
    }
    return b.client();
  }

  /**
   * The authorization server a client secret of the person's OAuth app may go to ({@link AppBinding}). A server not
   * yet added signs in only with the one its review showed when the person typed the secret. A stored server signs in
   * only with the one its secret was used with: its binding, or for a sign-in kept from an earlier version the one its
   * bundle names (the binding is recorded then); without either, the first sign-in records it. A changed client ID or a
   * new version of the secret (the person entered it again) starts a new binding.
   *
   * @throws OAuthHttp.Failure when discovery found another authorization server: the secret isn't sent
   */
  private void requireAppServer(Target t, Discovery.Server server, String clientId, String secret)
      throws OAuthHttp.Failure {
    if (t.connectionId() == null) {
      AppBinding.Server reviewed = t.reviewed();
      if (reviewed == null) {
        throw new OAuthHttp.Failure("Review " + t.name() + " again before you sign in with the client secret of your "
            + "OAuth app: the sign-in goes only to the authorization server the review showed.");
      }
      if (!reviewed.matches(server)) {
        throw new OAuthHttp.Failure(t.name() + " now names another authorization server (" + host(server.issuer())
            + ", tokens at " + host(server.token().toString()) + ") than the review showed (" + host(reviewed.issuer())
            + "), so GateRook doesn't send it the client secret of your OAuth app. Review " + t.name() + " again.");
      }
      return;
    }
    if (t.secretVersion() == null) {
      return;
    }
    AppBinding binding = appBinding(t, clientId, secret);
    if (binding != null && !binding.allows(server)) {
      boolean sameIssuer = Discovery.trimSlash(binding.issuer()).equals(Discovery.trimSlash(server.issuer()));
      String trusted = sameIssuer ? host(server.token().toString()) : host(server.issuer());
      throw new OAuthHttp.Failure((sameIssuer ? "The authorization server of " + t.name() + " ("
          + host(server.issuer()) + ") now takes its tokens at " + host(server.token().toString()) + " instead of "
          + host(binding.tokenEndpoint().toString()) : t.name() + " now sends its sign-in to " + host(server.issuer())
          + " instead of " + host(binding.issuer())) + ", where the client secret of your OAuth app was used, so "
          + "GateRook doesn't send the secret there. If you trust " + trusted + " with it, enter the client secret "
          + "again (in Secrets, or by editing " + t.name() + " in Connections), then sign in.");
    }
  }

  /** The binding of a stored server's client secret, as {@link #requireAppServer} describes; {@code null} for none. */
  private AppBinding appBinding(Target t, String clientId, String secret) {
    String connectionId = t.connectionId();
    AppBinding stored;
    try (var resolver = secrets.resolver(owner(connectionId), Set.of(APP_FIELD), new Taint())) {
      if (resolver.isSet(APP_FIELD)) {
        try (SecretResolver.Secret s = resolver.secret(APP_FIELD)) {
          stored = AppBinding.fromJson(s.unsafeString());
        }
        // A binding of another client ID or secret version: the person entered the client details again.
        return stored.covers(clientId, t.secretVersion()) ? stored : null;
      }
    } catch (ConnectorException | RuntimeException e) {
      log.warn("Where the client secret of MCP server {} may go couldn't be read: {}", t.name(), e.getMessage());
      return null;
    }
    // A sign-in from before bindings were recorded: the server it signed in with, if it was made with this secret.
    AppBinding earlier = AppBinding.fromBundle(holder(connectionId).load(), clientId, secret, t.secretVersion(),
        clock.instant());
    if (earlier != null) {
      try {
        writeTx.executeWithoutResult(s -> secrets.storeIssued(owner(connectionId), APP_FIELD, t.name() + "/oauth-app",
            earlier.toJson()));
      } catch (RuntimeException e) {
        log.warn("Where the client secret of MCP server {} may go wasn't stored: {}", t.name(), e.getMessage());
      }
    }
    return earlier;
  }

  /** {@code GET …/sign-ins/{id}}. */
  SignInView view(String id) {
    sweep();
    return flow(id).view();
  }

  /** The page Electron opens for {@code id} ({@code GET /internal/mcp-servers/sign-ins/{id}/authorization}). */
  public String authorizationUrl(String id) {
    sweep();
    Flow f = flow(id);
    if (!f.phase.equals("waiting")) {
      throw ProblemException.conflict("Sign-in over", "This sign-in is " + f.phase + ". Start a new one.");
    }
    return f.authorizationUrl.toString();
  }

  /**
   * The address the browser ended on, pasted by the person when it couldn't come back by itself: handled like the
   * redirect, {@code state} included.
   */
  SignInView paste(String id, String address) {
    sweep();
    Flow f = flow(id);
    if (!f.phase.equals("waiting")) {
      return f.view();
    }
    if (f.device != null) {
      throw ProblemException.unprocessable("Nothing to paste", "This sign-in ends on "
          + f.device.verificationUri().getHost() + ": enter the code there and allow access.", "/address");
    }
    URI u;
    try {
      u = URI.create(address == null ? "" : address.strip());
    } catch (IllegalArgumentException e) {
      throw ProblemException.unprocessable("Not an address", "Paste the whole address from the browser's address "
          + "bar, starting with http.", "/address");
    }
    if (u.getRawQuery() == null) {
      throw ProblemException.unprocessable("No sign-in in it", "That address has no code and state. Paste the "
          + "address the browser shows after you allowed access.", "/address");
    }
    LoopbackCallback.Page page = f.handle(OAuthHttp.query(u.getRawQuery()));
    if (f.phase.equals("waiting")) {
      throw ProblemException.unprocessable("Not this sign-in", page.text(), "/address");
    }
    return f.view();
  }

  /** {@code DELETE …/sign-ins/{id}}. */
  void cancel(String id) {
    Flow f = flows.get(id);
    if (f != null) {
      f.endIfWaiting("cancelled", "Cancelled.");
      flows.remove(id);
    }
  }

  /**
   * The finished sign-in of a draft, for adding the server: its tokens and the requests that list its tools. The flow
   * is used up.
   *
   * @throws ProblemException 422 when it isn't signed in, or was made for other settings
   */
  DraftSignIn take(String id, ConnectionConfig cfg) {
    sweep();
    Flow f = flow(id);
    if (f.connectionId != null) {
      throw ProblemException.unprocessable("Not a new server's sign-in", "This sign-in belongs to " + f.name + ".",
          "/signIn");
    }
    if (!f.phase.equals("signed-in") || f.bundle == null) {
      throw ProblemException.unprocessable("Not signed in", "Sign in to " + f.name + " first.", "/signIn");
    }
    if (!f.fingerprint.equals(fingerprint(cfg))) {
      throw ProblemException.unprocessable("Settings changed", "The URL or sign-in settings changed after you signed "
          + "in. Sign in again.", "/signIn");
    }
    flows.remove(id);
    return new DraftSignIn(f.bundle, cfg);
  }

  // ── Stored servers ──────────────────────────────────────────────────────────────────────────────────────────

  /** {@code GET /api/mcp-servers/{name}/sign-in}. */
  StatusView status(ConnectionConfig cfg) {
    if (!oauth(cfg)) {
      return new StatusView(cfg.name(), "none", null, null, null, false, null, null, null, false, null);
    }
    SignInBundle b = holder(cfg.id()).load();
    if (b == null) {
      return new StatusView(cfg.name(), "signed-out", null, null, null, false, null, null, optional(cfg)
          ? cfg.name() + " answers without a sign-in, with less access. Sign in for the rest."
          : cfg.name() + " isn't signed in.", false, null);
    }
    boolean needs = b.needsSignIn() != null || b.accessToken() == null
        || (!b.renewable() && b.expiresWithin(Duration.ZERO, clock.instant()));
    return new StatusView(cfg.name(), needs ? "needs-sign-in" : "signed-in", b.issuer(),
        b.grantedScope() != null ? b.grantedScope() : b.scope(), b.expiresAt(), b.renewable(), b.signedInAt(),
        b.renewedAt(), needs ? needsMessage(cfg.name(), b) : null, b.client().registered(), b.client().redirectUri());
  }

  /**
   * The sign-in of a server just added: stored in the transaction that stores the server, with where the client
   * secret of the person's OAuth app may go when it has one.
   *
   * @param secretVersion which stored client secret the server has ({@code <secret id>@<version>}), or {@code null}
   */
  void store(String connectionId, String name, SignInBundle bundle, String secretVersion) {
    secrets.storeIssued(owner(connectionId), FIELD, name + "/oauth-sign-in", bundle.toJson());
    bind(connectionId, name, bundle, secretVersion);
    holders.remove(connectionId);
  }

  /**
   * Records that the client secret of the person's OAuth app goes to the authorization server {@code b} signed in
   * with ({@link AppBinding}); nothing for any other client. Joins the caller's transaction.
   */
  private void bind(String connectionId, String name, SignInBundle b, String secretVersion) {
    OAuthClient.Client c = b.client();
    if (secretVersion == null || c.registered() || c.secret() == null || b.issuer() == null
        || b.tokenEndpoint() == null) {
      return;
    }
    secrets.storeIssued(owner(connectionId), APP_FIELD, name + "/oauth-app", AppBinding.of(c.id(), secretVersion,
        b.issuer(), b.tokenEndpoint(), clock.instant()).toJson());
  }

  /** {@code POST /api/mcp-servers/{name}/sign-out}: the tokens are revoked where the server allows it and forgotten. */
  void signOut(ConnectionConfig cfg, String reason) {
    SignInBundle b = holder(cfg.id()).load();
    writeTx.executeWithoutResult(s -> {
      secrets.deleteForOwnerFields(owner(cfg.id()), List.of(FIELD));
      record(cfg, "MCP server " + cfg.name() + " signed out", b == null ? null : "Signed in with "
          + host(b.issuer()), "Signed out", reason);
    });
    holders.remove(cfg.id());
    stopSessions(cfg.id());
    if (b != null) {
      revokeLater(cfg, b);
    }
    publish(cfg.name(), "signed-out", null);
  }

  /** A server is being removed (in its deletion's transaction): its sign-in goes, the tokens are revoked after. */
  void removing(String connectionId) {
    Holder h = holders.remove(connectionId);
    SignInBundle b = h == null ? null : h.bundle;
    ConnectionConfig cfg = configs.peek(connectionId).orElse(null);
    if (b == null && cfg != null) {
      b = holder(connectionId).load();
      holders.remove(connectionId);
    }
    secrets.deleteForOwner(owner(connectionId));
    if (b != null && cfg != null) {
      SignInBundle revoke = b;
      afterCommit(() -> revokeLater(cfg, revoke));
    }
  }

  /** After the caller's transaction commits (or now, without one): a rolled-back removal keeps its sign-in. */
  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          action.run();
        }
      });
    } else {
      action.run();
    }
  }

  /** The runtime opens a session for a stored server ({@code Host.authorization}). */
  Optional<McpServerRuntime.Authorization> authorization(String connectionId) {
    ConnectionConfig cfg = configs.byId(connectionId).filter(ConnectionConfigs.Stored::verified)
        .map(ConnectionConfigs.Stored::config).orElse(null);
    if (cfg == null || !oauth(cfg)) {
      return Optional.empty();
    }
    Holder holder = holder(connectionId);
    if (optional(cfg) && holder.load() == null) {
      // A server that answers without a sign-in connects without one until the person signs in.
      return Optional.empty();
    }
    return Optional.of(new StoredAuthorization(cfg, holder));
  }

  // ── A flow ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** One sign-in in progress; its listener, or a device sign-in's poller, lives as long as it waits. */
  private final class Flow {
    final String id;
    final String connectionId;
    final String name;
    final ConnectionConfig cfg;
    final Discovery.Result discovery;
    final String scope;
    final String fingerprint;
    OAuthClient.Client client;
    Pkce pkce;
    String state;
    URI authorizationUrl;
    Instant expiresAt;
    LoopbackCallback callback;
    /** A device sign-in's code (RFC 8628), or {@code null} for one whose browser comes back to the listener. */
    OAuthClient.DeviceCode device;
    /** Which stored client secret a stored server's sign-in uses ({@link Target#secretVersion}), or {@code null}. */
    String secretVersion;
    volatile Thread poller;
    volatile String phase = "waiting";
    volatile String message;
    volatile SignInBundle bundle;
    /** When it stopped waiting: a failed or cancelled view stays a minute for the window to show it. */
    volatile Instant endedAt;

    Flow(String id, String connectionId, String name, ConnectionConfig cfg, Discovery.Result discovery, String scope,
        String fingerprint) {
      this.id = id;
      this.connectionId = connectionId;
      this.name = name;
      this.cfg = cfg;
      this.discovery = discovery;
      this.scope = scope;
      this.fingerprint = fingerprint;
    }

    void listen(URI redirect) throws OAuthHttp.Failure {
      try {
        callback = LoopbackCallback.open(redirect.getHost(), Math.max(0, redirect.getPort()), redirect.getRawPath(),
            this::handle);
      } catch (IOException e) {
        throw new OAuthHttp.Failure("GateRook can't listen on port " + redirect.getPort() + " for the sign-in: it is "
            + "in use. Choose another callback port in the server's sign-in settings, or close the program that "
            + "uses it.");
      }
    }

    boolean tryListen(URI redirect) {
      try {
        listen(redirect);
        return true;
      } catch (OAuthHttp.Failure e) {
        return false;
      }
    }

    void closeListener() {
      if (callback != null) {
        callback.close();
        callback = null;
      }
    }

    /** The redirect (or a pasted address): the code is redeemed here, so the page can say how it ended. */
    synchronized LoopbackCallback.Page handle(Map<String, String> query) {
      if (!phase.equals("waiting")) {
        return new LoopbackCallback.Page(400, "Sign-in over", "This sign-in has ended already. Go back to GateRook.",
            true);
      }
      String given = query.get("state");
      if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
          state.getBytes(StandardCharsets.UTF_8))) {
        return new LoopbackCallback.Page(400, "Not this sign-in", "This isn't the sign-in GateRook is waiting for. "
            + "Start it again from GateRook.", false);
      }
      Discovery.Server server = discovery.server();
      String iss = query.get("iss");
      if ((iss != null || server.issParameter()) && (iss == null
          || !Discovery.trimSlash(iss).equals(Discovery.trimSlash(server.issuer())))) {
        // RFC 9207: the redirect came from another authorization server than the one this sign-in went to.
        end("failed", "The answer came from another authorization server than " + server.issuer() + ".");
        return failedPage();
      }
      if (query.containsKey("error")) {
        String description = query.get("error_description");
        end("failed", server.issuer() + " ended the sign-in: " + OAuthHttp.clean(query.get("error"))
            + (description == null ? "" : " — " + OAuthHttp.clean(description)));
        return failedPage();
      }
      String code = query.get("code");
      if (code == null || code.isBlank()) {
        end("failed", "The browser came back without a code.");
        return failedPage();
      }
      try {
        signedIn(OAuthClient.exchange(httpOf(cfg), server.token(), client, code, pkce.verifier(),
            discovery.resource(), OAuthClient.audienceScope(server, scope), clock.instant()));
        return new LoopbackCallback.Page(200, "Signed in to " + name, "GateRook keeps this sign-in. You can close "
            + "this tab and go back to GateRook.", true);
      } catch (OAuthHttp.Failure | RuntimeException e) {
        end("failed", e.getMessage());
        return failedPage();
      }
    }

    /**
     * The tokens of this sign-in: its bundle (a stored server's replaces the old one, and its sessions start over),
     * then it ends signed in. Tokens that arrive after the sign-in ended otherwise (cancelled) are dropped.
     */
    synchronized void signedIn(OAuthClient.Tokens tokens) {
      if (!phase.equals("waiting")) {
        return;
      }
      learn(connectionId != null ? connectionId : "sign-in:" + id, tokens);
      SignInBundle b = SignInBundle.signedIn(Discovery.canonical(discovery.serverUrl()), discovery.resource(),
          discovery.server(), client, scope, tokens, clock.instant());
      if (connectionId != null) {
        signedInAgain(this, b);
      }
      bundle = b;
      end("signed-in", null);
    }

    /** A device sign-in polls the token endpoint from a thread of its own, until it ends one way or another. */
    void startPolling() {
      if (device != null) {
        poller = Thread.ofVirtual().name("mcp-sign-in-" + id).start(this::poll);
      }
    }

    private void poll() {
      Duration interval = device.interval();
      int trouble = 0;
      while (phase.equals("waiting")) {
        try {
          Thread.sleep(interval);
        } catch (InterruptedException e) {
          // Ended meanwhile: cancelled, replaced or expired.
          return;
        }
        if (!phase.equals("waiting")) {
          return;
        }
        if (!clock.instant().isBefore(expiresAt)) {
          endIfWaiting("expired", "Nobody entered the code in time. Start the sign-in again.");
          return;
        }
        try {
          OAuthClient.DevicePoll p = OAuthClient.pollDevice(httpOf(cfg), discovery.server().token(), client, device,
              interval, discovery.resource(), clock.instant());
          trouble = 0;
          if (!p.pending()) {
            signedIn(p.tokens());
            return;
          }
          interval = p.interval();
        } catch (OAuthHttp.Failure e) {
          if (e.error() == null && ++trouble < MAX_POLL_TROUBLE && phase.equals("waiting")) {
            // The server couldn't be reached for a moment: the code is still good, the next poll may get through.
            continue;
          }
          endIfWaiting("expired_token".equals(e.error()) ? "expired" : "failed", e.getMessage());
          return;
        } catch (RuntimeException e) {
          endIfWaiting("failed", e.getMessage());
          return;
        }
      }
    }

    /**
     * Ends the sign-in unless something else ended it first: a cancel, an expiry and a device sign-in's poller take
     * turns with the redirect and the tokens.
     */
    synchronized void endIfWaiting(String next, String why) {
      if (phase.equals("waiting")) {
        end(next, why);
      }
    }

    private LoopbackCallback.Page failedPage() {
      return new LoopbackCallback.Page(400, "Sign-in failed", message + " Go back to GateRook to try again.", true);
    }

    void end(String next, String why) {
      phase = next;
      message = why;
      endedAt = clock.instant();
      closeListener();
      stopPolling();
      if (next.equals("signed-in")) {
        // A new server's sign-in waits for Connect as long as a sign-in may take.
        expiresAt = endedAt.plus(FLOW_TTL);
      } else {
        contexts.evict(cfg.id());
      }
      publish(this);
    }

    void close() {
      closeListener();
      stopPolling();
    }

    /** The poller stops at its next look; one asleep between two polls wakes for it. */
    void stopPolling() {
      Thread t = poller;
      if (t != null && t != Thread.currentThread()) {
        t.interrupt();
      }
    }

    SignInView view() {
      Discovery.Server s = discovery.server();
      boolean waiting = phase.equals("waiting");
      String host = device != null ? device.verificationUri().getHost() : s.authorization().getHost();
      return new SignInView(id, phase, name, s.issuer(), host, client == null ? null : client.redirectUri(),
          waiting && authorizationUrl != null ? authorizationUrl.toString() : null, expiresAt, scope, message,
          waiting && device != null ? device.userCode() : null);
    }
  }

  /** A stored server signed in again: the new tokens replace the old ones, and its sessions start over with them. */
  private void signedInAgain(Flow f, SignInBundle b) {
    SignInBundle before = holder(f.connectionId).load();
    writeTx.executeWithoutResult(s -> {
      secrets.storeIssued(owner(f.connectionId), FIELD, f.name + "/oauth-sign-in", b.toJson());
      bind(f.connectionId, f.name, b, f.secretVersion);
      record(f.cfg, "MCP server " + f.name + " signed in" + (before == null ? "" : " again"),
          before == null ? "Not signed in" : before.needsSignIn() != null ? "Needs sign-in" : "Signed in",
          "Signed in with " + host(b.issuer()) + (b.scope() == null ? "" : " · " + b.scope()), null);
    });
    holders.remove(f.connectionId);
    stopSessions(f.connectionId);
    publish(f.name, "signed-in", null);
  }

  private void sweep() {
    Instant now = clock.instant();
    for (Flow f : List.copyOf(flows.values())) {
      boolean waiting = f.phase.equals("waiting");
      if (waiting && f.expiresAt != null && now.isAfter(f.expiresAt)) {
        f.endIfWaiting("expired", "Nobody finished the sign-in in " + FLOW_TTL.toMinutes() + " minutes.");
      }
      Instant ended = f.endedAt;
      boolean unused = f.phase.equals("signed-in") && f.expiresAt != null && now.isAfter(f.expiresAt);
      if (unused || (!f.phase.equals("signed-in") && ended != null && now.isAfter(ended.plusSeconds(60)))) {
        // A failed, cancelled or expired one a minute after it ended, so the window can still show why.
        flows.remove(f.id);
      }
    }
  }

  private Flow flow(String id) {
    Flow f = id == null ? null : flows.get(id);
    if (f == null) {
      throw ProblemException.notFound("Sign-in not found", "This sign-in has ended. Start a new one.");
    }
    return f;
  }

  // ── The sign-in of a stored server ──────────────────────────────────────────────────────────────────────────

  /** The bundle of one stored server in memory, and the lock its renewals take one at a time. */
  private final class Holder {
    final String connectionId;
    final ReentrantLock lock = new ReentrantLock();
    volatile SignInBundle bundle;
    volatile boolean loaded;

    Holder(String connectionId) {
      this.connectionId = connectionId;
    }

    /** The stored bundle, read once; {@code null} when there is none or it can't be read. */
    SignInBundle load() {
      if (loaded) {
        return bundle;
      }
      lock.lock();
      try {
        if (!loaded) {
          bundle = read(connectionId);
          loaded = true;
        }
        return bundle;
      } finally {
        lock.unlock();
      }
    }
  }

  private Holder holder(String connectionId) {
    return holders.computeIfAbsent(connectionId, Holder::new);
  }

  private SignInBundle read(String connectionId) {
    try (var resolver = secrets.resolver(owner(connectionId), Set.of(FIELD), new Taint())) {
      if (!resolver.isSet(FIELD)) {
        return null;
      }
      try (SecretResolver.Secret s = resolver.secret(FIELD)) {
        SignInBundle b = SignInBundle.fromJson(s.unsafeString());
        // The tokens of a sign-in kept from an earlier start are masked as fresh ones are.
        learn(connectionId, b.accessToken(), b.refreshToken());
        return b;
      }
    } catch (ConnectorException | RuntimeException e) {
      log.warn("The sign-in of an MCP server couldn't be read: {}", e.getMessage());
      return null;
    }
  }

  /** The runtime's view of a stored server's sign-in, for one session. */
  private final class StoredAuthorization implements McpServerRuntime.Authorization {
    private final ConnectionConfig cfg;
    private final Holder holder;
    private final String url;

    StoredAuthorization(ConnectionConfig cfg, Holder holder) {
      this.cfg = cfg;
      this.holder = holder;
      String u = cfg.config().path("url").asString(null);
      this.url = u == null ? null : Discovery.canonical(URI.create(u));
    }

    @Override
    public String header() throws McpServerRuntime.SignInRequired, ConnectorException {
      SignInBundle b = usable();
      if (b.expiresWithin(RENEW_MARGIN, clock.instant())) {
        if (!b.renewable()) {
          if (b.expiresWithin(Duration.ZERO, clock.instant())) {
            throw needs(b.needing("its sign-in expired and can't be renewed"));
          }
        } else if (renewDue(b, clock.instant())) {
          b = renew(b);
        }
      }
      return b.header();
    }

    @Override
    public String refused(String header, String challenge, int status) throws McpServerRuntime.SignInRequired,
        ConnectorException {
      holder.lock.lock();
      try {
        SignInBundle b = usable();
        if (!b.header().equals(header)) {
          // Renewed meanwhile by another request.
          return b.header();
        }
        if (status == 403) {
          String wanted = WwwAuthenticate.parse(challenge).scope();
          throw needs(b.needing("it wants more permissions" + (wanted == null ? "" : " (" + wanted + ")")),
              union(b.scope(), wanted));
        }
        if (!b.renewable()) {
          throw needs(b.needing("it refused its token, which can't be renewed"));
        }
        // The transport sends a request again only once: a renewed token that is refused too ends that request, and
        // the sign-in is kept unless the authorization server itself refuses to renew it.
        return renew(b).header();
      } finally {
        holder.lock.unlock();
      }
    }

    @Override
    public List<String> secrets() {
      SignInBundle b = holder.bundle;
      List<String> out = new ArrayList<>();
      if (b != null) {
        if (b.accessToken() != null) {
          out.add(b.accessToken());
        }
        if (b.refreshToken() != null) {
          out.add(b.refreshToken());
        }
        if (b.client().secret() != null) {
          out.add(b.client().secret());
        }
      }
      return out;
    }

    /** The bundle when it can be sent to this URL. */
    private SignInBundle usable() throws McpServerRuntime.SignInRequired {
      SignInBundle b = holder.load();
      if (b == null) {
        throw new McpServerRuntime.SignInRequired(cfg.name() + " isn't signed in. Sign in to it in GateRook › "
            + "Connectors › MCP servers.");
      }
      if (url == null || !url.equals(b.serverUrl())) {
        // A token is for the resource it was issued for: a changed URL never gets the old one.
        throw new McpServerRuntime.SignInRequired(cfg.name() + "'s URL changed since you signed in. Sign in to it "
            + "again in GateRook › Connectors › MCP servers.");
      }
      if (b.needsSignIn() != null || b.header() == null) {
        throw new McpServerRuntime.SignInRequired(needsMessage(cfg.name(), b));
      }
      return b;
    }

    /** A renewal, one at a time per server: whoever comes second uses the first one's tokens. */
    private SignInBundle renew(SignInBundle seen) throws McpServerRuntime.SignInRequired, ConnectorException {
      holder.lock.lock();
      try {
        SignInBundle current = holder.bundle;
        if (current != null && current != seen && current.header() != null
            && !current.expiresWithin(RENEW_MARGIN, clock.instant())) {
          return current;
        }
        OAuthClient.Tokens tokens;
        try {
          tokens = OAuthClient.refresh(httpOf(cfg), seen.tokenEndpoint(), seen.client(), seen.refreshToken(),
              seen.resource(), seen.renewalScope(), clock.instant());
        } catch (OAuthHttp.Failure e) {
          if (e.error() != null && REFUSED_GRANT.contains(e.error())) {
            throw needs(seen.needing("the server refused to renew its sign-in (" + e.error() + ")"));
          }
          throw ConnectorException.unavailable("Couldn't renew the sign-in of " + cfg.name() + ": " + e.getMessage(),
              e);
        }
        learn(cfg.id(), tokens);
        SignInBundle next = seen.renewed(tokens, clock.instant());
        holder.bundle = next;
        try {
          secrets.storeIssued(owner(cfg.id()), FIELD, cfg.name() + "/oauth-sign-in", next.toJson());
        } catch (RuntimeException e) {
          // The new tokens work from memory; after a restart the old refresh token may be refused, which asks for a
          // new sign-in then.
          log.warn("The renewed sign-in of MCP server {} wasn't stored: {}", cfg.name(), e.getMessage());
        }
        return next;
      } finally {
        holder.lock.unlock();
      }
    }

    private McpServerRuntime.SignInRequired needs(SignInBundle marked) {
      return needs(marked, marked.scope());
    }

    /** Stores that the person has to sign in again (with {@code scope} next time) and says so. */
    private McpServerRuntime.SignInRequired needs(SignInBundle marked, String scope) {
      SignInBundle next = scope == null || scope.equals(marked.scope()) ? marked : new SignInBundle(marked.serverUrl(),
          marked.resource(), marked.issuer(), marked.tokenEndpoint(), marked.revocation(), marked.client(), scope,
          null, null, null, marked.grantedScope(), marked.signedInAt(), marked.renewedAt(), marked.needsSignIn());
      holder.bundle = next;
      try {
        secrets.storeIssued(owner(cfg.id()), FIELD, cfg.name() + "/oauth-sign-in", next.toJson());
      } catch (RuntimeException e) {
        log.warn("The sign-in state of MCP server {} wasn't stored: {}", cfg.name(), e.getMessage());
      }
      publish(cfg.name(), "needs-sign-in", needsMessage(cfg.name(), next));
      return new McpServerRuntime.SignInRequired(needsMessage(cfg.name(), next));
    }
  }

  /** A finished sign-in of a draft: what the probe of Add MCP server sends, and then stores. */
  final class DraftSignIn implements McpServerRuntime.Authorization {
    private volatile SignInBundle bundle;
    private final ConnectionConfig cfg;

    DraftSignIn(SignInBundle bundle, ConnectionConfig cfg) {
      this.bundle = bundle;
      this.cfg = cfg;
    }

    SignInBundle bundle() {
      return bundle;
    }

    String issuer() {
      return bundle.issuer();
    }

    @Override
    public synchronized String header() throws McpServerRuntime.SignInRequired, ConnectorException {
      if (bundle.expiresWithin(RENEW_MARGIN, clock.instant()) && bundle.renewable()
          && renewDue(bundle, clock.instant())) {
        renew();
      }
      return bundle.header();
    }

    @Override
    public synchronized String refused(String header, String challenge, int status)
        throws McpServerRuntime.SignInRequired, ConnectorException {
      if (!header.equals(bundle.header())) {
        return bundle.header();
      }
      if (status == 403 || !bundle.renewable()) {
        throw new McpServerRuntime.SignInRequired(cfg.name() + " refused the sign-in you just made (HTTP " + status
            + "). Check the account and its permissions, and sign in again.");
      }
      renew();
      return bundle.header();
    }

    @Override
    public List<String> secrets() {
      SignInBundle b = bundle;
      List<String> out = new ArrayList<>();
      if (b.accessToken() != null) {
        out.add(b.accessToken());
      }
      if (b.refreshToken() != null) {
        out.add(b.refreshToken());
      }
      return out;
    }

    private void renew() throws McpServerRuntime.SignInRequired, ConnectorException {
      SignInBundle b = bundle;
      try {
        OAuthClient.Tokens t = OAuthClient.refresh(httpOf(cfg), b.tokenEndpoint(), b.client(), b.refreshToken(),
            b.resource(), b.renewalScope(), clock.instant());
        learn(cfg.id(), t);
        bundle = b.renewed(t, clock.instant());
      } catch (OAuthHttp.Failure e) {
        if (e.error() != null && REFUSED_GRANT.contains(e.error())) {
          throw new McpServerRuntime.SignInRequired(cfg.name() + " refused to renew the sign-in you just made. Sign "
              + "in again.");
        }
        throw ConnectorException.unavailable("Couldn't renew the sign-in of " + cfg.name() + ": " + e.getMessage(), e);
      }
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** The guarded client of {@code cfg}'s environment (its private ranges), for one step. */
  private OAuthHttp httpOf(ConnectionConfig cfg) {
    CallContext ctx = contexts.builder(Ids.next("oau_"), cfg, policies.limits(cfg.env())).timeout(HTTP_TIMEOUT)
        .taint(new Taint()).build();
    GuardedEgress guarded = GuardedEgress.of(ctx).orElseThrow(() -> new IllegalStateException(
        "No egress guard for the sign-in"));
    return new OAuthHttp(guarded.client(), HTTP_TIMEOUT, "GateRook/" + BuiltIn.VERSION);
  }

  /** GateRook's own app at the server's authorization server, where that server takes the device authorization grant. */
  private Optional<GateRookApps.App> ownApp(Discovery.Server server) {
    return server.deviceGrant() ? apps.at(server.issuer()) : Optional.empty();
  }

  /** The scopes to ask for: the person's, the 401 challenge's, the resource's, plus offline_access where offered. */
  static String scope(Discovery.Result d, JsonNode auth) {
    Set<String> out = new LinkedHashSet<>();
    String chosen = auth.path("scopes").isString() ? auth.path("scopes").asString() : null;
    if (chosen != null && !chosen.isBlank()) {
      out.addAll(Arrays.asList(chosen.trim().split(" +")));
    } else if (d.scope() != null && !d.scope().isBlank()) {
      out.addAll(Arrays.asList(d.scope().trim().split(" +")));
    } else if (d.metadata() != null && !d.metadata().scopes().isEmpty()) {
      out.addAll(d.metadata().scopes());
    }
    if (!out.isEmpty() && d.server().scopes().contains("offline_access") && !out.contains("offline_access")) {
      // A refresh token, so the sign-in lasts longer than one access token.
      out.add("offline_access");
    }
    return out.isEmpty() ? null : String.join(" ", out);
  }

  private static String union(String a, String b) {
    Set<String> out = new LinkedHashSet<>();
    for (String s : new String[] {a, b}) {
      if (s != null && !s.isBlank()) {
        out.addAll(Arrays.asList(s.trim().split(" +")));
      }
    }
    return out.isEmpty() ? null : String.join(" ", out);
  }

  /**
   * The redirect URI of a sign-in: the person's host, port and path, else {@code http://localhost:<any free
   * port>/callback} for GateRook's own client, its metadata document or a registration it makes (the form the MCP
   * authorization spec names and the AI apps' command-line clients use, so servers that accept only redirects to this
   * computer accept it), or
   * {@code http://127.0.0.1:}{@value #APP_CALLBACK_PORT}{@code /callback} for an OAuth app the person registers once
   * (RFC 8252 §8.3 prefers the address; the URI must stay the same).
   */
  static URI redirectFor(JsonNode auth, boolean app) {
    String host = auth.path("callbackHost").asString(app ? "127.0.0.1" : "localhost");
    int port = auth.path("callbackPort").isInt() ? auth.path("callbackPort").asInt() : app ? APP_CALLBACK_PORT : 0;
    String path = auth.path("callbackPath").isString() ? auth.path("callbackPath").asString() : DEFAULT_PATH;
    return URI.create("http://" + ("localhost".equals(host) ? "localhost" : "127.0.0.1") + ":" + port + path);
  }

  /**
   * The redirect URI of a sign-in with the person's OAuth app at {@code server}: {@link #redirectFor} of an app, except
   * at the Microsoft identity platform. There it is {@code http://localhost:<any free port>/callback}, or the person's
   * host, port and path: the Entra admin center takes {@code http://localhost} but not {@code http://127.0.0.1} as the
   * redirect URI of a desktop app, and Entra matches a loopback redirect URI with any port, so no fixed port can be
   * taken by another program ({@link MicrosoftIdentityPlatform}).
   */
  static URI appRedirect(JsonNode auth, Discovery.Server server) {
    return redirectFor(auth, !server.microsoftIdentityPlatform());
  }

  /**
   * The redirect URI the person registers in their OAuth app at {@code server}: {@link #appRedirect}, without its port
   * at the Microsoft identity platform, which ignores the port of a loopback redirect URI.
   */
  static String appRedirectToRegister(JsonNode auth, Discovery.Server server) {
    URI u = appRedirect(auth, server);
    return server.microsoftIdentityPlatform() ? "http://" + u.getHost() + u.getRawPath() : u.toString();
  }

  /** What binds a draft's sign-in to the server it is added as: its URL, sign-in settings and environment. */
  static String fingerprint(ConnectionConfig cfg) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("env", cfg.env().id());
    String url = cfg.config().path("url").asString(null);
    n.put("url", url == null ? null : Discovery.canonical(URI.create(url)));
    ObjectNode auth = cfg.config().path("auth").isObject() ? ((ObjectNode) cfg.config().path("auth")).deepCopy()
        : JsonNodeFactory.instance.objectNode();
    auth.remove("clientSecret");
    n.set("auth", auth);
    return n.toString();
  }

  static boolean oauth(ConnectionConfig cfg) {
    return "http".equals(cfg.config().path("transport").asString(null))
        && "oauth".equals(cfg.config().path("auth").path("type").asString(null));
  }

  /** An OAuth server that answers without a sign-in too: it connects without one until the person signs in. */
  static boolean optional(ConnectionConfig cfg) {
    return oauth(cfg) && cfg.config().path("auth").path("optional").asBoolean(false);
  }

  /**
   * The cloud a server runs on, when it takes that cloud's credentials instead of an OAuth sign-in (§7.6): {@code google}
   * for one of Google's API hosts ({@link GoogleApis#host}: HTTPS under {@code googleapis.com}), {@code aws} for an AWS
   * endpoint that names its service and region ({@code aws-mcp.us-east-1.api.aws}); otherwise {@code null}. Only the
   * server's own address counts: the authorization server its metadata names is the server's say, and any server can
   * name Google's or AWS Sign-in, which would make the review offer the person's cloud credentials to it.
   */
  static String cloud(URI url) {
    if (GoogleApis.host(url)) {
      return "google";
    }
    String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
    return AwsSigV4.fromHost(host).isPresent() ? "aws" : null;
  }

  /**
   * Whether a token about to expire is renewed now: not within {@link #MIN_RENEW_INTERVAL} of the sign-in or the last
   * renewal. Until then the token goes as it is, and a refusal of it renews it ({@code refused}).
   */
  static boolean renewDue(SignInBundle b, Instant now) {
    Instant last = b.renewedAt() != null ? b.renewedAt() : b.signedInAt();
    return last == null || !now.isBefore(last.plus(MIN_RENEW_INTERVAL));
  }

  private static String needsMessage(String name, SignInBundle b) {
    return name + " needs you to sign in again" + (b.needsSignIn() == null ? "" : ": " + b.needsSignIn()) + ". Sign "
        + "in in GateRook › Connectors › MCP servers.";
  }

  private static SecretOwner owner(String connectionId) {
    return new SecretOwner(SecretOwner.Kind.MCP_SERVER, connectionId);
  }

  private static String host(String url) {
    try {
      String h = URI.create(url).getHost();
      return h == null ? url : h;
    } catch (IllegalArgumentException e) {
      return url;
    }
  }

  /**
   * Every token GateRook is handed is masked wherever it shows up, as configured secrets are: a token the token endpoint
   * issued has a credential's shape ({@link OAuthClient#credentialShaped}), so no ordinary word is masked for it. The
   * tokens of one sign-in replace each other at every renewal: the {@value #KEEP_TOKENS} most recent of {@code source}
   * stay known, which covers the ones still valid, and a server that renews often can't make the masker's set grow.
   */
  private static void learn(String source, OAuthClient.Tokens t) {
    learn(source, t.accessToken(), t.refreshToken());
  }

  private static void learn(String source, String... tokens) {
    List<String> shaped = new ArrayList<>();
    for (String token : tokens) {
      if (OAuthClient.credentialShaped(token)) {
        shaped.add(token);
      }
    }
    SecretFingerprints.process().learnReplacing("mcp-sign-in:" + source, KEEP_TOKENS, shaped.toArray(String[]::new));
  }

  private void stopSessions(String connectionId) {
    McpServerRuntime r = runtime.getIfAvailable();
    if (r != null) {
      r.stop(connectionId);
    }
  }

  private void revokeLater(ConnectionConfig cfg, SignInBundle b) {
    if (b.revocation() == null) {
      return;
    }
    Thread.ofVirtual().name("mcp-sign-out-" + cfg.name()).start(() -> {
      try {
        OAuthHttp http = httpOf(cfg);
        if (b.refreshToken() != null) {
          OAuthClient.revoke(http, b.revocation(), b.client(), b.refreshToken(), "refresh_token");
        }
        if (b.accessToken() != null) {
          OAuthClient.revoke(http, b.revocation(), b.client(), b.accessToken(), "access_token");
        }
      } catch (RuntimeException e) {
        log.debug("Revoking the sign-in of {} failed: {}", cfg.name(), e.getMessage());
      }
    });
  }

  private void record(ConnectionConfig cfg, String subject, String from, String to, String reason) {
    SetupChangeRecorder recorder = setupChanges.getIfAvailable();
    if (recorder != null) {
      recorder.record(new SetupChange(SetupChangeRecorder.What.MCP_SERVER, subject, from, to, reason, cfg.env(), null,
          cfg.name(), null));
    }
  }

  private void publish(Flow f) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("id", f.id);
    data.put("server", f.name);
    data.put("state", f.phase);
    if (f.message != null) {
      data.put("message", f.message);
    }
    events.publish("mcp-server.sign-in", data);
  }

  private void publish(String server, String state, String message) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("server", server);
    data.put("state", state);
    if (message != null) {
      data.put("message", message);
    }
    events.publish("mcp-server.sign-in", data);
  }
}
