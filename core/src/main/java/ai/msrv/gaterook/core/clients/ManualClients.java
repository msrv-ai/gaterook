package ai.msrv.gaterook.core.clients;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.clients.ClientViews.Card;
import ai.msrv.gaterook.core.clients.ClientViews.Disconnected;
import ai.msrv.gaterook.core.clients.ClientViews.Link;
import ai.msrv.gaterook.core.clients.internal.persistence.ManualClientEntity;
import ai.msrv.gaterook.core.clients.internal.persistence.ManualClientRepository;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent app registrations whose MCP configuration is owned by the person, with a token per app. */
@Service
class ManualClients {

  record Added(Card client, TokenService.Issued token) {}

  private static final String PREFIX = "manual-";
  private final ManualClientRepository apps;
  private final EndpointService endpoints;
  private final TokenService tokens;
  private final SetupChangeRecorder changes;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  ManualClients(ManualClientRepository apps, EndpointService endpoints, TokenService tokens,
      SetupChangeRecorder changes, Clock clock, PlatformTransactionManager transactions) {
    this.apps = apps;
    this.endpoints = endpoints;
    this.tokens = tokens;
    this.changes = changes;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  List<Card> cards() {
    Map<String, EndpointService.Summary> byId = endpoints.list().stream()
        .collect(Collectors.toMap(EndpointService.Summary::id, Function.identity()));
    return readTx.execute(s -> apps.findAllByOrderByCreatedAtAsc().stream()
        .filter(a -> byId.containsKey(a.getEndpointId())).map(a -> card(a, byId.get(a.getEndpointId()))).toList());
  }

  Added add(String name, String endpoint, Integer ttlDays) {
    String trimmed = name == null ? "" : name.strip();
    if (trimmed.isEmpty() || trimmed.length() > 80 || trimmed.codePoints().anyMatch(Character::isISOControl)) {
      throw ProblemException.unprocessable("Invalid app name", "Enter an app name of 1 to 80 characters, without "
          + "control characters.", "/name");
    }
    if (endpoint == null || endpoint.isBlank()) {
      throw ProblemException.unprocessable("Choose an endpoint", "Choose the endpoint this app can access.",
          "/endpoint");
    }
    return writeTx.execute(s -> {
      EndpointService.Summary e = endpoints.get(endpoint);
      requireUsable(e);
      ManualClientEntity app = new ManualClientEntity(trimmed, e.id(), clock.instant());
      TokenService.Issued token = tokens.issue(e.slug(), tokenName(app), ttlDays, "Add manual app " + trimmed);
      app.setTokenId(token.id());
      apps.save(app);
      changes.record(new SetupChange(SetupChangeRecorder.What.CLIENT, trimmed + " → " + e.slug(), null,
          "Awaiting manual MCP connection", null, e.env(), e.slug(), null, null));
      return new Added(card(app, e), token);
    });
  }

  Added renew(String id, Integer ttlDays) {
    return writeTx.execute(s -> {
      ManualClientEntity app = app(id);
      EndpointService.Summary e = endpoint(app);
      requireUsable(e);
      TokenService.Issued token = tokens.issue(e.slug(), tokenName(app), ttlDays,
          "Replace token for manual app " + app.getName());
      tokens.revoke(app.getTokenId(), "Replace token for manual app " + app.getName());
      app.setTokenId(token.id());
      apps.save(app);
      return new Added(card(app, e), token);
    });
  }

  Disconnected remove(String id) {
    return writeTx.execute(s -> {
      ManualClientEntity app = app(id);
      EndpointService.Summary e = endpoint(app);
      int rejected = tokens.revoke(app.getTokenId(), "Remove manual app " + app.getName());
      apps.delete(app);
      changes.record(new SetupChange(SetupChangeRecorder.What.CLIENT, app.getName() + " → " + e.slug(),
          "Manual app", "Removed; token revoked", null, e.env(), e.slug(), null, null));
      return new Disconnected(id, false, rejected, List.of("The app's token was revoked. Remove its MCP "
          + "configuration in " + app.getName() + "."), null);
    });
  }

  /** A registration that went with its endpoint: its client id ({@code manual-…}) and the app's name. */
  record Removed(String id, String name) {}

  /**
   * Runs inside endpoint deletion's transaction, before the endpoint and its tokens are removed: the registrations go,
   * each with a Setup change as Remove makes it. The token goes with the endpoint; the app's own configuration is the
   * person's to remove.
   */
  List<Removed> endpointRemoving(String endpointId, String slug, Env env, String reason) {
    List<ManualClientEntity> removed = apps.findByEndpointId(endpointId);
    List<Removed> out = removed.stream().map(a -> new Removed(PREFIX + a.getId(), a.getName())).toList();
    for (ManualClientEntity app : removed) {
      changes.record(new SetupChange(SetupChangeRecorder.What.CLIENT, app.getName() + " → " + slug, "Manual app",
          "Removed; token revoked", reason, env, slug, null, null));
    }
    apps.deleteAll(removed);
    apps.flush();
    return out;
  }

  private Card card(ManualClientEntity app, EndpointService.Summary e) {
    TokenService.TokenRow token = tokens.list(e.slug()).stream().filter(t -> t.id().equals(app.getTokenId()))
        .findFirst().orElse(null);
    boolean active = token != null && token.state().equals("active");
    String status = !active ? "error" : token.lastUsedAt() == null ? "pending" : "connected";
    String text = !active ? "Token " + (token == null ? "unavailable" : token.state()) + ": create a new token"
        : status.equals("pending") ? "Awaiting connection" : "Connected";
    String id = PREFIX + app.getId();
    Link link = new Link(id, id, e.slug(), e.env().id(), "manual", null, null, null,
        token == null ? null : token.prefix(), null,
        token == null ? tokenName(app) : token.name(), status, text,
        token == null ? app.getCreatedAt() : token.createdAt(), token == null ? null : token.lastUsedAt(), null,
        null, null, null, null);
    return new Card(id, app.getName(), status, text, null, null, false, List.of(), null, List.of(), List.of(),
        List.of(link), true);
  }

  private ManualClientEntity app(String id) {
    if (id == null || !id.startsWith(PREFIX)) {
      throw notFound();
    }
    return apps.findById(id.substring(PREFIX.length())).orElseThrow(ManualClients::notFound);
  }

  private EndpointService.Summary endpoint(ManualClientEntity app) {
    return endpoints.list().stream().filter(e -> e.id().equals(app.getEndpointId())).findFirst()
        .orElseThrow(ManualClients::notFound);
  }

  private static String tokenName(ManualClientEntity app) {
    String name = app.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-|-$", "");
    if (name.isEmpty()) name = "app";
    name = name.substring(0, Math.min(name.length(), 27)).replaceAll("-$", "");
    return name + "-" + app.getId().replace("-", "").substring(0, 12);
  }

  private static void requireUsable(EndpointService.Summary endpoint) {
    if (endpoint.blockedReason() != null) {
      throw ProblemException.conflict("Changed outside GateRook", endpoint.slug() + ": " + endpoint.blockedReason());
    }
    if (endpoint.status().equals("disabled")) {
      throw ProblemException.unprocessable("Endpoint is disabled", "Enable endpoint " + endpoint.slug()
          + " before setting up this app.", "/endpoint");
    }
  }

  private static ProblemException notFound() {
    return ProblemException.notFound("Manual app not found", "This manual app no longer exists.");
  }
}
