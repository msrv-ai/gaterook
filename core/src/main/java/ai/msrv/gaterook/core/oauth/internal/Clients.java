package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientRepository;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OAuth clients of both servers ({@code oauth_client}). A client belongs to the server that met it
 * ({@code ingress}) and is never accepted by the other one.
 *
 * <ul>
 *   <li>Tunnel: claude.ai by its Client ID Metadata Document (the default), refreshed from its URL whenever the cache
 *       ran out; a changed set of {@code redirect_uris} revokes its sign-ins. Dynamic registration only when
 *       Settings › Authorization says {@code cimd+dcr}, with https redirects on {@code allowedClientHosts}.</li>
 *   <li>Local (Cursor, Antigravity): dynamic registration with loopback redirects on any port (RFC 8252), Cursor's
 *       {@code cursor://anysphere.cursor-mcp/oauth/callback} and Antigravity's
 *       {@code https://antigravity.google/oauth-callback} only.</li>
 * </ul>
 * Registration answers {@code token_endpoint_auth_method: "none"} whatever was asked, at most 10 a minute per address
 * and 20 clients per server (at the limit, the least recently used one without a live sign-in makes room); a client
 * that never signed in is removed after 30 days. Each row is signed
 * ({@code mac}, ARCHITECTURE.md §4): a row edited in the database (a redirect pointed elsewhere) is refused.
 */
public class Clients {

  static final int MAX_DCR_CLIENTS = 20;
  static final Duration UNUSED_TTL = Duration.ofDays(30);

  /** Exact redirects the local server registers besides loopback ones (report 03 §9.4). */
  static final List<String> LOCAL_REDIRECTS = List.of("https://antigravity.google/oauth-callback",
      "cursor://anysphere.cursor-mcp/oauth/callback");

  /** A client as both servers use it. */
  public record Client(String rowId, String clientId, OAuthClientKind kind, Ingress ingress, String host, String name,
      List<String> redirectUris) {}

  /** A client that can't sign in; the message is for the error page. */
  public static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;

    Refused(String message) {
      super(message);
    }
  }

  private final OAuthClientRepository clients;
  private final CimdResolver cimd;
  private final RowMac rowMac;
  private final IntegrityAlerts alerts;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final Grants grants;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final RateWindow registrations;
  private final Map<String, Instant> lastUsed = new ConcurrentHashMap<>();

  Clients(OAuthClientRepository clients, CimdResolver cimd, RowMac rowMac, IntegrityAlerts alerts,
      ObjectProvider<SetupChangeRecorder> setupChanges, Grants grants, Clock clock,
      PlatformTransactionManager transactions) {
    this.clients = clients;
    this.cimd = cimd;
    this.rowMac = rowMac;
    this.alerts = alerts;
    this.setupChanges = setupChanges;
    this.grants = grants;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
    this.registrations = new RateWindow(10, Duration.ofMinutes(1), clock);
  }

  CimdResolver cimd() {
    return cimd;
  }

  /**
   * The client of an authorization request, for {@code server}. CIMD documents are fetched here (≤ 5 s).
   *
   * @param dcrEnabled whether dynamic registration is on for this server (always on locally)
   */
  Client forAuthorize(Issuers.Server server, String clientId, boolean dcrEnabled) throws Refused,
      InterruptedException {
    if (clientId == null || clientId.isBlank() || clientId.length() > 512) {
      throw new Refused("The request names no client.");
    }
    if (server.tunnel() && cimd.accepts(clientId)) {
      CimdResolver.ClientMetadata meta;
      try {
        meta = cimd.resolve(clientId);
      } catch (CimdResolver.CimdException e) {
        throw new Refused(e.getMessage());
      }
      return upsertCimd(meta);
    }
    if (clientId.startsWith("https://")) {
      throw new Refused("Client " + OAuthUris.hostOf(clientId) + " isn't allowed to sign in here.");
    }
    if (server.tunnel() && !dcrEnabled) {
      throw new Refused("This client isn't known here. Dynamic client registration is off.");
    }
    return registered(server.ingress(), clientId).orElseThrow(() -> new Refused("This client isn't registered "
        + "here. Add the server in your client again."));
  }

  /**
   * claude.ai as the demo world knows it: its CIMD client row as a first sign-in would have stored it, without
   * fetching the document (the demo is offline).
   */
  Client demoCimd(String clientId, String host, String name, List<String> redirectUris) throws Refused {
    return upsertCimd(new CimdResolver.ClientMetadata(clientId, host, name, List.copyOf(redirectUris),
        clock.instant()));
  }

  /** The client a token request names: one this server met before, its row intact. */
  Optional<Client> forToken(Issuers.Server server, String clientId) {
    if (clientId == null || clientId.isBlank() || clientId.length() > 512) {
      return Optional.empty();
    }
    return readTx.execute(s -> clients.findByClientId(clientId).filter(c -> c.getIngress() == server.ingress())
        .filter(this::verified).map(Clients::view));
  }

  private Optional<Client> registered(Ingress ingress, String clientId) {
    return readTx.execute(s -> clients.findByClientId(clientId).filter(c -> c.getIngress() == ingress
        && c.getKind() == OAuthClientKind.DCR).filter(this::verified).map(Clients::view));
  }

  private Client upsertCimd(CimdResolver.ClientMetadata meta) throws Refused {
    Client[] out = new Client[1];
    boolean[] conflict = new boolean[1];
    writeTx.executeWithoutResult(s -> {
      Optional<OAuthClientEntity> existing = clients.findByClientId(meta.clientId());
      OAuthClientEntity row;
      if (existing.isPresent()) {
        row = existing.get();
        if (row.getIngress() != Ingress.TUNNEL || row.getKind() != OAuthClientKind.CIMD) {
          conflict[0] = true;
          return;
        }
        boolean intact = verified(row);
        if (!intact || !row.getRedirectUris().equals(meta.redirectUris()) || !java.util.Objects.equals(
            row.getClientName(), meta.name()) || !row.getClientHost().equals(meta.host())) {
          if (!row.getRedirectUris().equals(meta.redirectUris()) || !intact) {
            // Where sign-ins return to changed (or the row was edited): what was allowed for the old ones ends.
            grants.revokeAllOfClient(row.getId(), "The client's redirect URIs changed");
          }
          row.refresh(meta.name(), meta.host(), meta.redirectUris());
          sign(row);
          clients.save(row);
          alerts.resolve("oauth_client", row.getId());
        }
      } else {
        row = new OAuthClientEntity(OAuthClientKind.CIMD, Ingress.TUNNEL, meta.clientId(), meta.name(), meta.host(),
            meta.redirectUris(), clock.instant());
        sign(row);
        clients.save(row);
      }
      out[0] = view(row);
    });
    if (conflict[0]) {
      throw new Refused("This client id is registered with the other authorization server.");
    }
    return out[0];
  }

  // ── Dynamic registration ────────────────────────────────────────────────────────────────────────────────────

  /** The answer of {@code POST /oauth/register}: 201 with the client, or 400 with an RFC 7591 error. */
  record Registration(int status, ObjectNode body) {}

  Registration register(Issuers.Server server, JsonNode request, String ip, List<String> allowedHosts) {
    if (!registrations.tryAcquire(server.ingress() + "|" + RateWindow.addressKey(ip))) {
      return error(429, "too_many_requests", "Too many registrations from this address. Try again in a minute.");
    }
    if (request == null || !request.isObject()) {
      return error(400, "invalid_client_metadata", "Send the client metadata as a JSON object.");
    }
    List<String> redirects = new ArrayList<>();
    for (JsonNode r : request.path("redirect_uris")) {
      String uri = r.asString("");
      if (uri.isBlank() || uri.length() > 512 || redirects.size() >= 5) {
        return error(400, "invalid_redirect_uri", "Register one to five redirect URIs of at most 512 characters.");
      }
      if (!allowedRedirect(server, uri, allowedHosts)) {
        return error(400, "invalid_redirect_uri", server.tunnel()
            ? "Redirect URIs must be HTTPS on an allowed host (" + String.join(", ", allowedHosts) + ")."
            : "Redirect URIs must be loopback (http://127.0.0.1:<port>/…) or a known client's callback.");
      }
      redirects.add(uri);
    }
    if (redirects.isEmpty()) {
      return error(400, "invalid_redirect_uri", "Register at least one redirect URI.");
    }
    for (JsonNode g : request.path("grant_types")) {
      String grant = g.asString("");
      if (!grant.equals("authorization_code") && !grant.equals("refresh_token")) {
        return error(400, "invalid_client_metadata", "Only the authorization_code and refresh_token grants exist "
            + "here.");
      }
    }
    String name = ConsentPages.plain(request.path("client_name").asString(""), 128);
    String host = OAuthUris.hostOf(redirects.getFirst());
    String label = name == null || name.isBlank() ? host : name;
    Registration[] out = new Registration[1];
    writeTx.executeWithoutResult(s -> {
      if (clients.countByIngressAndKind(server.ingress(), OAuthClientKind.DCR) >= MAX_DCR_CLIENTS
          && !makeRoom(server, ip)) {
        out[0] = error(400, "invalid_client_metadata", "Too many clients are registered here. Revoke unused "
            + "sign-ins in MCP Gateway, then try again.");
        return;
      }
      OAuthClientEntity row = new OAuthClientEntity(OAuthClientKind.DCR, server.ingress(), OAuthTokens.clientId(),
          label, host, redirects, clock.instant());
      sign(row);
      clients.save(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.OAUTH, "OAuth client registered · "
          + label + (server.tunnel() ? " (tunnel)" : " (local)"), null, "Registered", "From " + ip, null, null,
          null, details(row)));
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      body.put("client_id", row.getClientId());
      body.put("client_id_issued_at", row.getCreatedAt().getEpochSecond());
      body.put("client_name", label);
      ArrayNode uris = body.putArray("redirect_uris");
      redirects.forEach(uris::add);
      body.putArray("grant_types").add("authorization_code").add("refresh_token");
      body.putArray("response_types").add("code");
      body.put("token_endpoint_auth_method", "none");
      out[0] = new Registration(201, body);
    });
    return out[0];
  }

  /**
   * At {@value #MAX_DCR_CLIENTS} registrations, the one that holds no live sign-in and was used least recently (one
   * that never signed in first) makes room for a new one; its ended sign-ins go with it, their Setup changes stay.
   * Otherwise the limit would hold for good: the rows of ended sign-ins are never deleted, so 20 clients that once
   * signed in (Cursor after a reinstall, an inspector's new session) would refuse every later registration. False when
   * every registered client holds a live sign-in — then revoking one in "Signed in" makes room.
   */
  private boolean makeRoom(Issuers.Server server, String ip) {
    Optional<OAuthClientEntity> oldest = clients.findAll().stream()
        .filter(c -> c.getIngress() == server.ingress() && c.getKind() == OAuthClientKind.DCR)
        .filter(c -> !grants.hasLive(c.getId()))
        .min(java.util.Comparator.comparing((OAuthClientEntity c) -> grants.hasAny(c.getId()))
            .thenComparing(c -> c.getLastUsedAt() == null ? c.getCreatedAt() : c.getLastUsedAt()));
    if (oldest.isEmpty()) {
      return false;
    }
    OAuthClientEntity c = oldest.get();
    grants.deleteEnded(c.getId());
    lastUsed.remove(c.getId());
    clients.delete(c);
    setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.OAUTH, "OAuth client removed · "
        + (c.getClientName() == null ? c.getClientHost() : c.getClientName()) + (server.tunnel() ? " (tunnel)"
            : " (local)"), "Registered", "Removed", "Made room for a registration from " + ip + ": it held no live "
            + "sign-in", null, null, null, details(c)));
    return true;
  }

  static boolean allowedRedirect(Issuers.Server server, String uri, List<String> allowedHosts) {
    if (server.tunnel()) {
      if (!uri.startsWith("https://")) {
        return false;
      }
      return OAuthUris.canonicalResource(uri).isPresent()
          && allowedHosts.contains(OAuthUris.hostOf(uri).toLowerCase(Locale.ROOT));
    }
    return OAuthUris.loopback(uri) || LOCAL_REDIRECTS.contains(uri);
  }

  private static Registration error(int status, String code, String description) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("error", code);
    body.put("error_description", description);
    return new Registration(status, body);
  }

  // ── Use and housekeeping ────────────────────────────────────────────────────────────────────────────────────

  /** A client signed in or refreshed; written once a minute ({@link #flushLastUsed}). */
  void used(String rowId) {
    lastUsed.put(rowId, clock.instant());
  }

  void flushLastUsed() {
    if (lastUsed.isEmpty()) {
      return;
    }
    Map<String, Instant> batch = Map.copyOf(lastUsed);
    batch.keySet().forEach(lastUsed::remove);
    writeTx.executeWithoutResult(s -> batch.forEach((id, at) -> clients.findById(id).ifPresent(c -> {
      c.setLastUsedAt(at);
      clients.save(c);
    })));
  }

  /** Registered clients that never got a sign-in and weren't used for 30 days are removed. */
  int removeUnused() {
    Instant floor = clock.instant().minus(UNUSED_TTL);
    int[] removed = new int[1];
    writeTx.executeWithoutResult(s -> {
      for (OAuthClientEntity c : clients.findAll()) {
        Instant last = c.getLastUsedAt() == null ? c.getCreatedAt() : c.getLastUsedAt();
        if (c.getKind() == OAuthClientKind.DCR && last.isBefore(floor) && !grants.hasAny(c.getId())) {
          clients.delete(c);
          removed[0]++;
        }
      }
    });
    return removed[0];
  }

  // ── Integrity ───────────────────────────────────────────────────────────────────────────────────────────────

  private void sign(OAuthClientEntity row) {
    row.setMac(rowMac.sign("oauth_client", row.getId(), 0, fields(row)));
  }

  private boolean verified(OAuthClientEntity row) {
    boolean ok = rowMac.verify("oauth_client", row.getId(), 0, fields(row), row.getMac());
    if (!ok) {
      alerts.report("oauth_client", row.getId(), row.getClientName() == null ? row.getClientHost()
          : row.getClientName(), "OAuth client row");
    }
    return ok;
  }

  private JsonNode fields(OAuthClientEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("kind", row.getKind().name());
    f.put("ingress", row.getIngress().name());
    f.put("clientId", row.getClientId());
    f.put("clientName", row.getClientName());
    f.put("clientHost", row.getClientHost());
    ArrayNode uris = f.putArray("redirectUris");
    row.getRedirectUris().forEach(uris::add);
    return f;
  }

  private static ObjectNode details(OAuthClientEntity row) {
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    d.put("kind", row.getKind().name().toLowerCase(Locale.ROOT));
    d.put("ingress", row.getIngress().name());
    ArrayNode uris = d.putArray("redirectUris");
    row.getRedirectUris().forEach(uris::add);
    return d;
  }

  static Client view(OAuthClientEntity row) {
    return new Client(row.getId(), row.getClientId(), row.getKind(), row.getIngress(), row.getClientHost(),
        row.getClientName(), List.copyOf(row.getRedirectUris()));
  }
}
