package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.oauth.OAuthService.ClientUse;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two authorization servers of the core (ARCHITECTURE.md A5, §7.3, report 02 §7): the tunnel's for claude.ai
 * (issuer {@code https://<tunnel host>}) and the local one for Cursor and Antigravity (issuer
 * {@code http://127.0.0.1:<port>}). They share this code and nothing else: clients, codes, grants and tokens carry
 * their server's ingress, and each server accepts only its own — a token of the local server is 401 through the
 * tunnel and the other way round.
 *
 * <p>Authorization requests are checked in the order of report 02 §7.1: (a) the client, (b) its exact redirect URI —
 * either failing shows an error page and never redirects; then (c) {@code response_type=code}, (d) PKCE S256,
 * (e) exactly one {@code resource}, the endpoint's URL, (f) the scopes ({@code mcp}, {@code offline_access}; others
 * ignored), (g) the limits — errors of (c)–(e) go back to the client with {@code state} and {@code iss}. A valid one
 * waits for the person here. The limit per browser address counts every request, and before (a): the sign-in page
 * is open to the internet, and (a) may fetch a metadata document.
 */
public class OAuthServer {

  static final Set<String> SCOPES = Set.of("mcp", "offline_access");
  static final String COOKIE = "gw_ar";

  private static final Logger log = LoggerFactory.getLogger(OAuthServer.class);

  private final Issuers issuers;
  private final Clients clients;
  private final Grants grants;
  private final Consents consents;
  private final AuthorizationCodes codes;
  private final EndpointSnapshotService snapshots;
  private final SettingsService settings;
  private final TunnelPublication tunnel;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  OAuthServer(Issuers issuers, Clients clients, Grants grants, Consents consents, AuthorizationCodes codes,
      EndpointSnapshotService snapshots, SettingsService settings, TunnelPublication tunnel,
      ObjectProvider<SetupChangeRecorder> setupChanges, Clock clock, PlatformTransactionManager transactions) {
    this.issuers = issuers;
    this.clients = clients;
    this.grants = grants;
    this.consents = consents;
    this.codes = codes;
    this.snapshots = snapshots;
    this.settings = settings;
    this.tunnel = tunnel;
    this.setupChanges = setupChanges;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    consents.onExpiry(p -> error(p.server, p.redirectUri, p.state, "access_denied",
        "Nobody allowed it on this computer in time"), this::expired);
  }

  Issuers issuers() {
    return issuers;
  }

  // ── Metadata ────────────────────────────────────────────────────────────────────────────────────────────────

  /** RFC 9728 metadata of {@code slug}'s MCP URL, when {@code server} signs in to it. */
  Optional<ObjectNode> protectedResource(Issuers.Server server, String slug) {
    if (endpointFor(server, slug).isEmpty()) {
      return Optional.empty();
    }
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("resource", issuers.resourceFor(server, slug));
    n.putArray("authorization_servers").add(server.issuer());
    n.putArray("scopes_supported").add("mcp");
    n.putArray("bearer_methods_supported").add("header");
    n.put("resource_name", "GateRook · " + slug);
    return Optional.of(n);
  }

  /** RFC 8414 metadata. claude.ai picks CIMD only when both of its fields below are there. */
  ObjectNode authorizationServer(Issuers.Server server) {
    String issuer = server.issuer();
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("issuer", issuer);
    n.put("authorization_endpoint", issuer + "/oauth/authorize");
    n.put("token_endpoint", issuer + "/oauth/token");
    n.put("revocation_endpoint", issuer + "/oauth/revoke");
    if (dcrEnabled(server)) {
      n.put("registration_endpoint", issuer + "/oauth/register");
    }
    n.putArray("response_types_supported").add("code");
    n.putArray("response_modes_supported").add("query");
    n.putArray("grant_types_supported").add("authorization_code").add("refresh_token");
    n.putArray("token_endpoint_auth_methods_supported").add("none");
    n.putArray("revocation_endpoint_auth_methods_supported").add("none");
    n.putArray("code_challenge_methods_supported").add("S256");
    n.putArray("scopes_supported").add("mcp").add("offline_access");
    if (server.tunnel()) {
      n.put("client_id_metadata_document_supported", true);
    }
    n.put("authorization_response_iss_parameter_supported", true);
    return n;
  }

  /** Registration is always open locally (Cursor, Antigravity), through the tunnel only with {@code cimd+dcr}. */
  boolean dcrEnabled(Issuers.Server server) {
    return !server.tunnel() || "cimd+dcr".equals(settings.value("authorization", "clientRegistration")
        .asString("cimd"));
  }

  /**
   * The endpoint {@code server} signs in to by {@code slug}: on the tunnel the published one; locally one with
   * OAuth on (Endpoint › Settings, Clients › Connect with sign-in), never prod. Both: enabled and intact.
   */
  Optional<EndpointSnapshot> endpointFor(Issuers.Server server, String slug) {
    if (server.tunnel() && !slug.equals(server.publication().endpointSlug())) {
      return Optional.empty();
    }
    return snapshots.bySlug(slug).filter(e -> e.enabled() && !e.blocked() && e.env() != Env.PROD)
        .filter(e -> server.tunnel() || e.oauthEnabled());
  }

  // ── Authorize ───────────────────────────────────────────────────────────────────────────────────────────────

  /** What {@code /oauth/authorize} answers: a page (with the cookie of a new sign-in) or a redirect. */
  record Answer(int status, String html, String location, String cookie) {

    static Answer page(int status, String html) {
      return new Answer(status, html, null, null);
    }

    static Answer redirect(String location) {
      return new Answer(302, null, location, null);
    }
  }

  Answer authorize(Issuers.Server server, Map<String, String> p, String browserIp) throws InterruptedException {
    // (g) for the browser's address first: every attempt counts, valid or not, so a stranger can't make this computer
    // fetch metadata documents (or open dialogs) without limit.
    if (!consents.admitBrowser(browserIp)) {
      return Answer.page(429, ConsentPages.error("Another sign-in is waiting on this computer", "Answer it in "
          + "GateRook first, or wait a few minutes and start again."));
    }
    // (a) the client and (b) its redirect: without both, nothing goes back to a URL the request chose.
    Clients.Client client;
    try {
      client = clients.forAuthorize(server, p.get("client_id"), dcrEnabled(server));
    } catch (Clients.Refused e) {
      return Answer.page(400, ConsentPages.error("This sign-in can't continue", e.getMessage()));
    }
    String redirect = p.get("redirect_uri");
    if (redirect == null || client.redirectUris().stream().noneMatch(r -> OAuthUris.redirectMatches(r, redirect))) {
      return Answer.page(400, ConsentPages.error("This sign-in can't continue", "The redirect URI isn't one "
          + client.host() + " registered."));
    }
    String state = p.get("state");
    if (!"code".equals(p.get("response_type"))) {
      return Answer.redirect(error(server, redirect, state, "unsupported_response_type", "Only response_type=code "
          + "is supported."));
    }
    String challenge = p.get("code_challenge");
    if (!"S256".equals(p.get("code_challenge_method")) || !OAuthUris.validChallenge(challenge)) {
      return Answer.redirect(error(server, redirect, state, "invalid_request", "PKCE with S256 is required."));
    }
    String resource = p.get("resource");
    Optional<String> slug = resource == null ? Optional.empty() : issuers.slugOf(server, resource);
    Optional<EndpointSnapshot> endpoint = slug.flatMap(s -> endpointFor(server, s));
    if (endpoint.isEmpty()) {
      return Answer.redirect(error(server, redirect, state, "invalid_target", resource == null
          ? "Send the endpoint's URL as resource." : "This resource can't be signed in to here."));
    }
    String scope = scope(p.get("scope"));
    String rid = ai.msrv.gaterook.core.platform.Ids.next("ar_");
    String cookie = OAuthTokens.random("");
    String code = OAuthTokens.userCode();
    Consents.Pending pending = new Consents.Pending(rid, code, OAuthTokens.hash(cookie), server, client, redirect,
        state, challenge, scope, endpoint.get().id(), slug.get(), issuers.storedResource(server, slug.get()),
        browserIp, clock.instant());
    // (g) for the endpoint: an unknown browser can't flood this computer with dialogs.
    if (!consents.tryOpen(pending)) {
      return Answer.page(429, ConsentPages.error("Another sign-in is waiting on this computer", "Answer it in "
          + "GateRook first, or wait a few minutes and start again."));
    }
    log.info("Sign-in {} of {} for {} waits for the person (code {})", rid, client.host(), slug.get(), code);
    String html = ConsentPages.waiting(rid, code, client.host(), OAuthUris.hostOf(redirect), slug.get(),
        Consents.TIMEOUT.toSeconds());
    return new Answer(200, html, null, cookie);
  }

  private static String scope(String requested) {
    StringBuilder out = new StringBuilder("mcp");
    if (requested != null) {
      for (String s : requested.split(" ")) {
        if (s.equals("offline_access") && out.indexOf("offline_access") < 0) {
          out.append(" offline_access");
        }
      }
    }
    return out.toString();
  }

  /** {@code GET /oauth/authorize/status?rid=}: the browser's answer, once, and only to the browser with the cookie. */
  Optional<ObjectNode> status(Issuers.Server server, String rid, String cookie) {
    Optional<Consents.Pending> p = rid == null ? Optional.empty() : consents.get(rid);
    if (p.isEmpty() || cookie == null || p.get().server.ingress() != server.ingress()
        || !MessageDigest.isEqual(OAuthTokens.hash(cookie).getBytes(StandardCharsets.US_ASCII),
            p.get().cookieHash.getBytes(StandardCharsets.US_ASCII))) {
      return Optional.empty();
    }
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    if (p.get().waiting()) {
      n.put("status", "pending");
      n.put("expiresIn", Math.max(0, Duration.between(clock.instant(), p.get().expiresAt).toSeconds()));
      return Optional.of(n);
    }
    Optional<String> location = p.get().take();
    if (location.isEmpty()) {
      return Optional.empty();
    }
    n.put("status", "done");
    n.put("location", location.get());
    return Optional.of(n);
  }

  // ── Consent decisions (the app) ─────────────────────────────────────────────────────────────────────────────

  /** A waiting sign-in for the admin API. */
  public record Waiting(String id, String code, String clientHost, String clientName, String clientKind,
      String redirectHost, String endpointId, String slug, String browserIp, Ingress ingress, Instant expiresAt) {}

  public List<Waiting> waiting() {
    return consents.waiting().stream().map(p -> new Waiting(p.id, p.code, p.client.host(), p.client.name(),
        p.client.kind().name().toLowerCase(Locale.ROOT), OAuthUris.hostOf(p.redirectUri), p.endpointId, p.slug,
        p.browserIp, p.server.ingress(), p.expiresAt)).toList();
  }

  public void decide(String id, boolean allow, String reason) {
    Consents.Pending p = consents.get(id).orElseThrow(() -> ProblemException.notFound("Sign-in not found",
        "There is no sign-in " + id + " waiting."));
    // One answer acts on a sign-in: a second one (a double click, two windows) is 409 before it makes a grant that
    // would replace the first one's.
    if (!p.expiresAt.isAfter(clock.instant()) || !p.claim()) {
      throw ProblemException.conflict("Already answered", "This sign-in was answered or expired already.");
    }
    String why = "Code " + p.code + (p.browserIp == null ? "" : " · from " + p.browserIp)
        + (reason == null || reason.isBlank() ? "" : " · " + reason);
    if (!allow) {
      if (!p.decide("denied", error(p.server, p.redirectUri, p.state, "access_denied", "Denied on this computer"),
          clock.instant())) {
        throw ProblemException.conflict("Already answered", "This sign-in was answered or expired already.");
      }
      record(p, "Denied", why);
      consents.resolved(p, "denied");
      return;
    }
    if (endpointFor(p.server, p.slug).filter(e -> e.id().equals(p.endpointId)).isEmpty()
        || p.server.tunnel() && !currentTunnelResource(p)) {
      p.decide("denied", error(p.server, p.redirectUri, p.state, "access_denied", "The endpoint can't be signed in "
          + "to any more"), clock.instant());
      consents.resolved(p, "denied");
      throw ProblemException.conflict("Endpoint unavailable", p.slug + " can't be signed in to any more: it was "
          + "changed, disabled or unpublished meanwhile.");
    }
    String grantId = grants.create(p.client, p.endpointId, p.server.ingress(), p.resource, p.scope, why);
    String code = codes.issue(grantId, p.client.clientId(), p.redirectUri, p.resource, p.challenge,
        p.server.ingress());
    clients.used(p.client.rowId());
    String location = OAuthUris.withQuery(p.redirectUri, "code", code, "state", p.state, "iss", p.server.issuer());
    if (!p.decide("allowed", location, clock.instant())) {
      grants.revoke(grantId, "The sign-in expired while it was being allowed");
      throw ProblemException.conflict("Already answered", "This sign-in expired meanwhile.");
    }
    consents.resolved(p, "allowed");
  }

  /** A tunnel sign-in is allowed only while the tunnel still serves the URL it was asked for. */
  private boolean currentTunnelResource(Consents.Pending p) {
    return tunnel.current().map(TunnelPublication.Publication::resource).map(p.resource::equals).orElse(false);
  }

  /** Nobody answered within 5 minutes: a refusal (its redirect is set already), recorded like one. */
  private void expired(Consents.Pending p) {
    record(p, "Expired", "Code " + p.code + " · nobody allowed it within 5 minutes");
  }

  private void record(Consents.Pending p, String outcome, String why) {
    Optional<EndpointSnapshot> e = snapshots.get(p.endpointId);
    writeTx.executeWithoutResult(s -> setupChanges.getObject().record(new SetupChange(
        SetupChangeRecorder.What.OAUTH, "Sign-in · " + (p.client.kind() == OAuthClientKind.CIMD
            || p.client.name() == null ? p.client.host() : p.client.name()) + " → " + p.slug, null, outcome, why,
        e.map(EndpointSnapshot::env).orElse(null), p.slug, null, null)));
  }

  private static String error(Issuers.Server server, String redirect, String state, String error, String description) {
    return OAuthUris.withQuery(redirect, "error", error, "error_description", description, "state", state, "iss",
        server.issuer());
  }

  // ── Token ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** The token endpoint's answer: status and JSON body (always {@code no-store}). */
  record TokenAnswer(int status, ObjectNode body) {}

  TokenAnswer token(Issuers.Server server, Map<String, String> p, boolean basicAuth) {
    if (basicAuth) {
      return tokenError(401, "invalid_client", "Public clients send no credentials.");
    }
    String grantType = p.get("grant_type");
    if ("authorization_code".equals(grantType)) {
      return exchangeCode(server, p);
    }
    if ("refresh_token".equals(grantType)) {
      Optional<Clients.Client> client = clients.forToken(server, p.get("client_id"));
      if (client.isEmpty()) {
        return tokenError(400, "invalid_grant", "Refresh token is no longer valid");
      }
      Grants.Refresh r = grants.refresh(p.get("refresh_token"), p.get("client_id"), p.get("resource"),
          server.ingress());
      if (r.issued() == null) {
        if (r.refusal() == Grants.RefreshRefusal.REUSED) {
          log.warn("A used refresh token of {} was presented again: its sign-in is revoked", client.get().host());
        }
        return tokenError(400, "invalid_grant", "Refresh token is no longer valid");
      }
      clients.used(client.get().rowId());
      return new TokenAnswer(200, tokens(r.issued()));
    }
    if (grantType == null) {
      return tokenError(400, "invalid_request", "grant_type is missing.");
    }
    return tokenError(400, "unsupported_grant_type", "Only authorization_code and refresh_token.");
  }

  private TokenAnswer exchangeCode(Issuers.Server server, Map<String, String> p) {
    Optional<AuthorizationCodes.Redeemed> redeemed = codes.redeem(p.get("code"));
    if (redeemed.isEmpty()) {
      return tokenError(400, "invalid_grant", "The authorization code is unknown or expired.");
    }
    AuthorizationCodes.Code c = redeemed.get().code();
    if (redeemed.get().replayed()) {
      grants.revokeForCodeReplay(c.grantId());
      log.warn("An authorization code was presented twice: its sign-in {} is revoked", c.grantId());
      return tokenError(400, "invalid_grant", "The authorization code was already used.");
    }
    if (c.ingress() != server.ingress() || !c.clientId().equals(p.get("client_id"))) {
      return tokenError(400, "invalid_grant", "The authorization code was issued to another client.");
    }
    String redirect = p.get("redirect_uri");
    if (redirect != null && !redirect.equals(c.redirectUri())) {
      return tokenError(400, "invalid_grant", "redirect_uri doesn't match the authorization request.");
    }
    String resource = p.get("resource");
    if (resource != null && !OAuthUris.canonicalResource(resource).map(c.resource()::equals).orElse(false)) {
      return tokenError(400, "invalid_target", "resource doesn't match the authorization request.");
    }
    if (!OAuthUris.pkceS256(p.get("code_verifier"), c.challenge())) {
      return tokenError(400, "invalid_grant", "code_verifier doesn't match the code challenge.");
    }
    Optional<Grants.Live> grant = grants.live(c.grantId());
    if (grant.isEmpty()) {
      return tokenError(400, "invalid_grant", "The sign-in was revoked.");
    }
    return new TokenAnswer(200, tokens(grants.issue(c.grantId())));
  }

  private static ObjectNode tokens(Grants.Issued issued) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("access_token", issued.accessToken());
    n.put("token_type", "Bearer");
    n.put("expires_in", issued.expiresIn());
    n.put("refresh_token", issued.refreshToken());
    n.put("scope", issued.scope());
    return n;
  }

  private static TokenAnswer tokenError(int status, String error, String description) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("error", error);
    n.put("error_description", description);
    return new TokenAnswer(status, n);
  }

  // ── Register and revoke ─────────────────────────────────────────────────────────────────────────────────────

  Clients.Registration register(Issuers.Server server, JsonNode body, String ip) {
    if (!dcrEnabled(server)) {
      return null;
    }
    return clients.register(server, body, ip, clients.cimd().allowedHosts());
  }

  void revoke(Issuers.Server server, String token, String clientId) {
    grants.revokeToken(token, clientId, server.ingress());
  }

  /**
   * The demo world's claude.ai sign-in (BLUEPRINT §9): its client and a grant for the tunnel's URL of the endpoint as
   * of {@code at} (the kit's Setup changes don't list it). Never called outside {@code GW_DEMO}.
   */
  public String seedDemoGrant(String clientId, String clientHost, String clientName, List<String> redirectUris,
      String endpointId, String resource, Instant at, String why) {
    try {
      Clients.Client client = clients.demoCimd(clientId, clientHost, clientName, redirectUris);
      return grants.create(client, endpointId, Ingress.TUNNEL, resource, "mcp offline_access", why, at, false);
    } catch (Clients.Refused e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  // ── "Signed in" ─────────────────────────────────────────────────────────────────────────────────────────────

  public List<Grants.Row> grantRows() {
    return grants.list();
  }

  /** Current local sign-ins that have completed token exchange, including use before any audited tool call. */
  public List<ClientUse> localClientUses() {
    Instant now = clock.instant();
    return grants.list().stream().filter(g -> g.ingress() == Ingress.LOCAL && "active".equals(g.state())
        && g.revokedAt() == null && g.expiresAt().isAfter(now) && g.lastUsedAt() != null)
        .flatMap(g -> grants.live(g.id()).flatMap(live -> clients.forToken(
            new Issuers.Server(Ingress.LOCAL, "", null), live.clientId()))
            .flatMap(client -> snapshots.get(g.endpointId()).filter(e -> e.enabled() && !e.blocked()
                && e.oauthEnabled() && e.env() != Env.PROD && issuers.localResource(e.slug()).equals(g.resource()))
                .map(e -> new ClientUse(g.id(), clientHint(client.host(), client.name()), e.slug(), e.env().id(),
                    g.createdAt(), g.lastUsedAt()))).stream())
        .filter(use -> use.client() != null).toList();
  }

  public void revokeGrant(String id, String reason) {
    grants.revoke(id, reason == null || reason.isBlank() ? "Revoked in GateRook" : reason);
  }

  // ── Authentication of MCP calls ─────────────────────────────────────────────────────────────────────────────

  /**
   * The caller behind an OAuth access token: the grant must be live, of this request's ingress and for this endpoint,
   * which isn't prod; through the tunnel its resource must be the tunnel's current URL of the endpoint (a quick
   * tunnel's new hostname leaves older sign-ins behind), locally {@code http://127.0.0.1:<port>/mcp/<slug>} of an
   * endpoint that still signs in with OAuth.
   */
  public Optional<Caller> authenticate(Authenticator.Request request) {
    Optional<Grants.Live> live = grants.access(request.bearerToken());
    if (live.isEmpty() || live.get().ingress() != request.ingress()) {
      return Optional.empty();
    }
    Grants.Live g = live.get();
    Optional<EndpointSnapshot> endpoint = snapshots.get(g.endpointId());
    if (endpoint.isEmpty() || !endpoint.get().slug().equals(request.endpointSlug())) {
      return Optional.empty();
    }
    String expected;
    if (request.ingress() == Ingress.TUNNEL) {
      Optional<TunnelPublication.Publication> published = tunnel.current();
      // A prod endpoint is never published (§9 "Stricter production rules"): should one turn prod while the tunnel still serves
      // it, its calls stop here at once, not when the tunnel's endpoint listener has stopped the agent.
      if (published.isEmpty() || !published.get().endpointSlug().equals(request.endpointSlug())
          || endpoint.get().env() == Env.PROD) {
        return Optional.empty();
      }
      expected = published.get().resource();
    } else {
      if (!endpoint.get().oauthEnabled() || endpoint.get().env() == Env.PROD) {
        return Optional.empty();
      }
      expected = issuers.localResource(request.endpointSlug());
    }
    if (!expected.equals(g.resource())) {
      return Optional.empty();
    }
    grants.used(g.grantId(), request.endpointSlug());
    clients.used(g.clientRowId());
    // The trusted label: a metadata document client is its host (HTTPS vouches for it, not its self-declared name);
    // a registered one is the name it registered with, as "Signed in" shows it.
    String label = g.clientId().startsWith("https://") || g.clientName() == null ? g.clientHost() : g.clientName();
    return Optional.of(new Caller(Caller.Kind.OAUTH, g.grantId(), label, clientHint(g), request.ingress(),
        request.remoteAddress()));
  }

  private static String clientHint(Grants.Live g) {
    return clientHint(g.clientHost(), g.clientName());
  }

  private static String clientHint(String clientHost, String clientName) {
    String host = clientHost == null ? "" : clientHost.toLowerCase(Locale.ROOT);
    String name = clientName == null ? "" : clientName.toLowerCase(Locale.ROOT);
    if (host.equals("claude.ai") || host.endsWith(".claude.ai")) {
      return "claude-ai";
    }
    if (name.contains("cursor") || host.startsWith("cursor")) {
      return "cursor";
    }
    if (name.contains("antigravity") || host.contains("antigravity")) {
      return "antigravity";
    }
    return null;
  }

  public boolean revoked(String grantId) {
    return grants.revoked(grantId);
  }

  List<String> liveAccess(String endpointId) {
    return grants.liveAccess(endpointId);
  }

  void endpointRemoving(String endpointId, String slug) {
    grants.endpointRemoving(endpointId, slug);
  }

  int addressChanged(String host) {
    return grants.addressChanged(host);
  }

  /** Writes the batched {@code last_used_at} of grants and clients, and drops clients unused for 30 days. */
  void housekeeping(boolean removeUnused) {
    grants.flushLastUsed();
    clients.flushLastUsed();
    if (removeUnused) {
      int n = clients.removeUnused();
      if (n > 0) {
        log.info("Removed {} registered OAuth client(s) unused for 30 days", n);
      }
    }
  }
}
