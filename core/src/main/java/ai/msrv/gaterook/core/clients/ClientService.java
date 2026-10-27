package ai.msrv.gaterook.core.clients;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.clients.ClientViews.Backup;
import ai.msrv.gaterook.core.clients.ClientViews.BridgeRemoved;
import ai.msrv.gaterook.core.clients.ClientViews.Card;
import ai.msrv.gaterook.core.clients.ClientViews.Check;
import ai.msrv.gaterook.core.clients.ClientViews.Clients;
import ai.msrv.gaterook.core.clients.ClientViews.Connected;
import ai.msrv.gaterook.core.clients.ClientViews.Disconnected;
import ai.msrv.gaterook.core.clients.ClientViews.FileChange;
import ai.msrv.gaterook.core.clients.ClientViews.Link;
import ai.msrv.gaterook.core.clients.ClientViews.Manual;
import ai.msrv.gaterook.core.clients.ClientViews.Method;
import ai.msrv.gaterook.core.clients.ClientViews.OtherEndpoint;
import ai.msrv.gaterook.core.clients.ClientViews.Preview;
import ai.msrv.gaterook.core.clients.ClientViews.Restored;
import ai.msrv.gaterook.core.clients.ClientViews.Snippet;
import ai.msrv.gaterook.core.clients.ClientViews.Step;
import ai.msrv.gaterook.core.clients.internal.BridgeSelfTest;
import ai.msrv.gaterook.core.clients.internal.BridgeShimInstaller;
import ai.msrv.gaterook.core.clients.internal.BrokerProbe;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.Codex;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.Detection;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.Entry;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.EntryValues;
import ai.msrv.gaterook.core.clients.internal.ClientEnvironment;
import ai.msrv.gaterook.core.clients.internal.ConfigFileWriter;
import ai.msrv.gaterook.core.clients.internal.ConfigFileWriter.Snapshot;
import ai.msrv.gaterook.core.clients.internal.DiffService;
import ai.msrv.gaterook.core.clients.internal.JsonEntryEditor;
import ai.msrv.gaterook.core.clients.internal.McpbBuilder;
import ai.msrv.gaterook.core.clients.internal.TomlBlockEditor;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientKind;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientLinkEntity;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientLinkRepository;
import ai.msrv.gaterook.core.clients.internal.persistence.LinkMethod;
import ai.msrv.gaterook.core.endpoints.ClientGrants;
import ai.msrv.gaterook.core.endpoints.ClientGrants.Grant;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connecting AI clients in one click (ARCHITECTURE.md A7, §6 clients, §7.2; report 03): find the clients on this
 * computer, show the exact change to a client's config before anything is written, back the file up, write one entry,
 * and take it back on Disconnect — by a reverse edit, so that what the person changed since stays. Claude Desktop gets
 * an extension ({@code .mcpb}) per endpoint, Codex a headers helper, Cursor and Antigravity a token, a sign-in through
 * the browser or the bridge. Claude Desktop and Codex never hold a token: their bridges get 15-minute ones from the
 * broker for the client's grant.
 *
 * <p>A preview is held for ten minutes; Confirm writes only if the file still has the bytes the preview read (else 409:
 * check again). Every Connect, Disconnect and Restore is a Setup change; previews, diffs and Setup changes show
 * {@code ‹new token›}, never a token.
 */
@Service
public class ClientService {

  /** How long a previewed change can be confirmed. */
  public static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
  private static final int MAX_PREVIEWS = 64;
  private static final Set<Integer> TOKEN_DAYS = Set.of(1, 7, 30, 90);
  private static final Pattern NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
  private static final Pattern NAMED_TOKEN = Pattern.compile("gwt_[A-Za-z0-9_-]{43}");
  private static final Pattern ANY_TOKEN = Pattern.compile("gw[tbor]_[A-Za-z0-9_-]{43}");
  private static final Pattern PORT = Pattern.compile("^http://127\\.0\\.0\\.1:(\\d+)/mcp/([a-z0-9-]+)$");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger log = LoggerFactory.getLogger(ClientService.class);

  /** What Connect needs besides the endpoint. */
  public record ConnectRequest(String endpoint, String method, String grantLabel, TokenOptions token,
      CodexOptions codex) {}

  /** {@code method: token}: the named token's name and lifetime (1, 7, 30 or 90 days; 7 by default). */
  public record TokenOptions(String name, Integer ttlDays) {}

  /** Codex: {@code default_tools_approval_mode} ({@code writes} or {@code prompt}) and {@code tool_timeout_sec}. */
  public record CodexOptions(String approvalMode, Integer toolTimeoutSec) {}

  private enum PlanKind { CONNECT, DISCONNECT, RESTORE }

  /** A previewed change, held until Confirm or ten minutes. */
  private record Plan(String id, PlanKind kind, ClientKind client, String slug, String endpointId, Env env,
      LinkMethod method, String grantId, String grantLabel, String entryName, Snapshot snapshot, String content,
      String linkId, TokenOptions token, McpbBuilder.Spec mcpb, String backupId, Instant expiresAt) {}

  private final ClientLinkRepository links;
  private final ClientGrants grants;
  private final TokenService tokens;
  private final EndpointService endpoints;
  private final ToolCatalog catalog;
  private final LocalListener listener;
  private final ClientEnvironment env;
  private final ConfigFileWriter writer;
  private final EventBus events;
  private final SetupChangeRecorder setupChanges;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final Map<String, Plan> plans = new ConcurrentHashMap<>();
  private final Map<ClientKind, Detection> detections = new EnumMap<>(ClientKind.class);
  private volatile Instant scannedAt;

  public ClientService(ClientLinkRepository links, ClientGrants grants, TokenService tokens,
      EndpointService endpoints, ToolCatalog catalog, LocalListener listener, ClientEnvironment env,
      EventBus events, SetupChangeRecorder setupChanges, Clock clock, PlatformTransactionManager transactions) {
    this.links = links;
    this.grants = grants;
    this.tokens = tokens;
    this.endpoints = endpoints;
    this.catalog = catalog;
    this.listener = listener;
    this.env = env;
    this.writer = new ConfigFileWriter(env.backups(), clock);
    this.events = events;
    this.setupChanges = setupChanges;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  public ClientEnvironment environment() {
    return env;
  }

  // ── The Clients page ────────────────────────────────────────────────────────────────────────────────────────

  /** {@code GET /api/clients}: the cards, from the last scan (the first call scans). */
  public Clients clients() {
    if (scannedAt == null) {
      return scan();
    }
    return new Clients(scannedAt, cards());
  }

  /** {@code POST /api/clients/scan}: looks for the clients on this computer again. */
  public synchronized Clients scan() {
    for (ClientAdapter a : ClientAdapter.all()) {
      detections.put(a.kind(), a.detect(env));
    }
    scannedAt = clock.instant();
    return new Clients(scannedAt, cards());
  }

  private List<Card> cards() {
    List<ClientLinkEntity> active = activeLinks();
    Map<String, EndpointService.Summary> byId = endpointsById();
    List<Card> out = new ArrayList<>();
    for (ClientAdapter a : ClientAdapter.all()) {
      Detection d;
      synchronized (this) {
        d = detections.get(a.kind());
      }
      if (d == null) {
        d = a.detect(env);
      }
      List<Link> mine = active.stream().filter(l -> l.getClient() == a.kind()).map(l -> link(l, byId)).toList();
      out.add(card(a, d, mine));
    }
    return out;
  }

  private Card card(ClientAdapter a, Detection d, List<Link> mine) {
    String status;
    String text;
    Optional<Link> problem = mine.stream().filter(l -> Set.of("removed-outside", "needs-repair", "revoked", "error")
        .contains(l.status())).findFirst();
    if (problem.isPresent()) {
      status = "error";
      text = problem.get().statusText();
    } else if (mine.stream().anyMatch(l -> l.status().equals("connected"))) {
      status = "connected";
      text = "Connected";
    } else if (!mine.isEmpty()) {
      status = "needs-restart";
      text = restartHint(a.kind(), mine.getFirst().method());
    } else if (d.installed()) {
      status = "detected";
      text = "Detected, not connected";
    } else {
      status = "not-installed";
      text = "Not installed";
    }
    List<String> warnings = new ArrayList<>();
    if (a.kind() == ClientKind.CODEX && Codex.tooOld(d.version())) {
      warnings.add("Update Codex: versions before " + Codex.HELPER_SINCE + " ignore http_headers_helper and connect "
          + "without a token.");
    }
    List<Method> methods = a.methods().stream().map(m -> method(a.kind(), m)).toList();
    return new Card(a.kind().id(), a.displayName(), status, text, d.configPath().toString(),
        env.display(d.configPath()), d.installed(), d.evidence(), d.version(), List.copyOf(warnings), methods, mine);
  }

  private static Method method(ClientKind client, LinkMethod m) {
    return switch (m) {
      case MCPB -> new Method("mcpb", "Bridge extension (.mcpb)", true, null);
      case CONFIG_BRIDGE -> new Method("config-bridge", client == ClientKind.CLAUDE_DESKTOP
          ? "Bridge entry in claude_desktop_config.json" : "Through the bridge", true, null);
      case HELPER -> new Method("helper", "gw-bridge headers (http_headers_helper)", true, null);
      case TOKEN -> new Method("token", "Token in the file", false, "Use the bridge or sign-in for prod: a token in "
          + "a file outlives the incident.");
      case OAUTH -> new Method("oauth", "Sign in through the browser", false, "Sign-in through the browser isn't "
          + "offered for prod endpoints.");
    };
  }

  private static String restartHint(ClientKind client, String method) {
    return switch (client) {
      case CLAUDE_DESKTOP -> "mcpb".equals(method) ? "Install the extension in Claude Desktop"
          : "Quit Claude Desktop from the tray icon, then open it again";
      case CODEX -> "Restart Codex: Settings › MCP servers › Restart";
      case CURSOR -> "Turn the server on in Cursor: Settings › MCP";
      case ANTIGRAVITY -> "Refresh in Antigravity: Installed MCP Servers";
    };
  }

  private List<ClientLinkEntity> activeLinks() {
    return readTx.execute(s -> links.findByRemovedAtIsNullOrderByCreatedAtAsc());
  }

  private Link link(ClientLinkEntity l) {
    return link(l, endpointsById());
  }

  /** One link with its live status: its grant or token, its entry in the file, its port. */
  private Link link(ClientLinkEntity l, Map<String, EndpointService.Summary> byId) {
    EndpointService.Summary e = byId.get(l.getEndpointId());
    String slug = e == null ? "?" : e.slug();
    Optional<Grant> grant = Optional.ofNullable(l.getGrantId()).flatMap(grants::find);
    Optional<TokenService.TokenRow> token = l.getTokenId() == null || e == null ? Optional.empty()
        : tokens.list(slug).stream().filter(t -> t.id().equals(l.getTokenId())).findFirst();
    Instant usedAt = grant.map(Grant::lastTokenAt).orElse(token.map(TokenService.TokenRow::lastUsedAt).orElse(null));
    String[] state = state(l, slug, grant, token, usedAt);
    Path config = l.getConfigPath() == null ? null : Path.of(l.getConfigPath());
    String mcpb = l.getMethod() == LinkMethod.MCPB ? config == null ? null : config.toString() : null;
    return new Link(l.getId(), l.getClient().id(), slug, e == null ? null : e.env().id(),
        KebabCase.of(l.getMethod()), l.getEntryName(), l.getConfigPath(), config == null ? null : env.display(config),
        grant.map(Grant::prefix).orElse(null), grant.map(Grant::label).orElse(null),
        token.map(TokenService.TokenRow::name).orElse(null), state[0], state[1], l.getCreatedAt(), usedAt, mcpb, null,
        null);
  }

  /** {@code [status, text]} of a link. */
  private String[] state(ClientLinkEntity l, String slug, Optional<Grant> grant,
      Optional<TokenService.TokenRow> token, Instant usedAt) {
    if (l.getGrantId() != null) {
      if (grant.isEmpty() || !grant.get().verified()) {
        return new String[] {"error", "Its access was changed outside GateRook: disconnect and connect again"};
      }
      if (grant.get().revokedAt() != null) {
        return new String[] {"revoked", "Access revoked: Disconnect removes the entry"};
      }
    }
    if (l.getTokenId() != null) {
      if (token.isEmpty() || !"active".equals(token.get().state())) {
        return new String[] {"revoked", "Token " + token.map(TokenService.TokenRow::name).orElse("") + " is "
            + token.map(TokenService.TokenRow::state).orElse("gone") + ": connect again to renew it"};
      }
    }
    if (l.getConfigPath() != null) {
      Path file = Path.of(l.getConfigPath());
      if (l.getMethod() == LinkMethod.MCPB) {
        if (!Files.isRegularFile(file)) {
          return new String[] {"removed-outside", "The extension file was removed outside the app"};
        }
      } else {
        try {
          Snapshot s = ConfigFileWriter.read(file);
          Optional<JsonNode> entry = s.exists() ? entryOf(l, s.content()) : Optional.empty();
          if (entry.isEmpty()) {
            return new String[] {"removed-outside", "Removed outside the app"};
          }
          if (!ours(entry.get(), slug, l.getGrantId())) {
            return new String[] {"needs-repair", "The entry " + l.getEntryName() + " was changed outside the app"};
          }
          Integer port = portOf(entry.get());
          if (port != null && port != listener.port()) {
            return new String[] {"needs-repair", "Needs repair: the entry points at port " + port + ", the gateway "
                + "listens on " + listener.port()};
          }
        } catch (ConfigEditException ex) {
          return new String[] {"error", ex.getMessage()};
        }
      }
    }
    if (usedAt != null && !usedAt.isBefore(l.getCreatedAt())) {
      return new String[] {"connected", "Connected"};
    }
    return new String[] {"needs-restart", restartHint(l.getClient(), KebabCase.of(l.getMethod()))};
  }

  private Optional<JsonNode> entryOf(ClientLinkEntity l, String content) {
    return l.getClient() == ClientKind.CODEX ? TomlBlockEditor.table(content, l.getEntryName())
        : JsonEntryEditor.entry(content, "mcpServers", l.getEntryName());
  }

  /** Our entry: it points at this endpoint ({@code /mcp/<slug>}) or names our grant. */
  private static boolean ours(JsonNode entry, String slug, String grantId) {
    String text = entry.toString();
    return text.contains("/mcp/" + slug + "\"") || (grantId != null && text.contains(grantId));
  }

  private static Integer portOf(JsonNode entry) {
    for (String key : List.of("url", "serverUrl")) {
      Matcher m = PORT.matcher(entry.path(key).asString(""));
      if (m.matches()) {
        return Integer.parseInt(m.group(1));
      }
    }
    return null;
  }

  // ── Connect ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/clients/{client}/connect/preview}: what Connect would write; nothing is written. */
  public Preview previewConnect(String clientId, ConnectRequest r) {
    ClientKind client = client(clientId);
    ClientAdapter a = ClientAdapter.of(client);
    if (r == null || r.endpoint() == null) {
      throw ProblemException.unprocessable("Choose an endpoint", "Pick the endpoint " + a.displayName() + " gets.",
          "/endpoint");
    }
    EndpointService.Summary e = endpoint(r.endpoint());
    LinkMethod method = r.method() == null ? a.methods().getFirst() : methodOf(r.method());
    if (!a.methods().contains(method)) {
      throw ProblemException.unprocessable("Not offered for " + a.displayName(), a.displayName() + " can't be "
          + "connected with " + r.method() + ".", "/method");
    }
    if (e.env() == Env.PROD && (method == LinkMethod.TOKEN || method == LinkMethod.OAUTH)) {
      throw ProblemException.unprocessable("Not on a prod endpoint", method == LinkMethod.TOKEN
          ? "Use the bridge or sign-in for prod: a token in a file outlives the incident."
          : "Sign-in through the browser isn't offered for prod endpoints: use the bridge.", "/method");
    }
    Optional<ClientLinkEntity> existing = readTx.execute(s -> links.findByClientAndRemovedAtIsNull(client).stream()
        .filter(l -> l.getEndpointId().equals(e.id())).findFirst());
    boolean repair = existing.isPresent();
    String grantId = null;
    String label = null;
    if (method != LinkMethod.TOKEN && method != LinkMethod.OAUTH) {
      Optional<Grant> current = existing.map(ClientLinkEntity::getGrantId).flatMap(grants::find)
          .filter(Grant::active);
      grantId = current.map(Grant::id).orElse(Ids.next("gr_"));
      label = current.map(Grant::label).orElse(label(r.grantLabel(), client.id()));
    }
    TokenOptions token = null;
    if (method == LinkMethod.TOKEN) {
      String name = r.token() == null || r.token().name() == null ? client.id() + "-" + env.shortHostname()
          : r.token().name();
      int days = r.token() == null || r.token().ttlDays() == null ? 7 : r.token().ttlDays();
      if (!NAME.matcher(name).matches() || name.length() > 40) {
        throw ProblemException.unprocessable("Invalid token name", "Lowercase letters, digits and hyphens, like "
            + "cursor-laptop.", "/token/name");
      }
      if (!TOKEN_DAYS.contains(days)) {
        throw ProblemException.unprocessable("Invalid lifetime", "A token for a client lives 1, 7, 30 or 90 days.",
            "/token/ttlDays");
      }
      token = new TokenOptions(name, days);
    }
    String approvalMode = r.codex() == null || r.codex().approvalMode() == null ? "writes" : r.codex().approvalMode();
    int toolTimeout = r.codex() == null || r.codex().toolTimeoutSec() == null ? 60 : r.codex().toolTimeoutSec();
    if (!Set.of("writes", "prompt").contains(approvalMode)) {
      throw ProblemException.unprocessable("Invalid approval mode", "Codex asks before write tools (writes) or "
          + "before every tool (prompt).", "/codex/approvalMode");
    }
    if (toolTimeout < 30 || toolTimeout > 600) {
      throw ProblemException.unprocessable("Invalid tool timeout", "30 to 600 seconds.", "/codex/toolTimeoutSec");
    }
    String url = listener.origin() + "/mcp/" + e.slug();
    List<String> notes = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (e.env() == Env.PROD) {
      warnings.add("Endpoint " + e.slug() + " reaches prod. Connect it only to the app you use for incidents; its "
          + "first token in each session waits until you allow it here.");
    }
    if (method == LinkMethod.MCPB) {
      return previewMcpb(a, e, grantId, label, repair, existing, notes, warnings);
    }
    Path file = a.configPath(env);
    Snapshot snapshot = ConfigFileWriter.read(file);
    String before = snapshot.exists() ? snapshot.content() : "";
    String name = existing.map(ClientLinkEntity::getEntryName).orElseGet(() -> entryName(client, before, e.slug()));
    EntryValues values = new EntryValues(e.slug(), url, grantId, method == LinkMethod.TOKEN
        ? ClientAdapter.TOKEN_PLACEHOLDER : null, approvalMode, toolTimeout);
    Entry entry = a.entry(env, method, name, values);
    String after;
    try {
      after = edit(client, before, entry, repair);
    } catch (ConfigEditException ex) {
      throw ex.fragment() != null ? ex : ex.withFragment(fragment(entry));
    }
    String modeChange = null;
    if (a.tokenInFile(method)) {
      notes.add("Token " + token.name() + " · expires in " + token.ttlDays() + (token.ttlDays() == 1 ? " day" : " days")
          + " · written in plain text to " + env.display(file) + " · Revoke it on the Tokens tab of the endpoint");
      modeChange = modeChange(snapshot);
    }
    notes.addAll(methodNotes(client, method, e));
    if (client == ClientKind.CODEX) {
      Detection d = detections.get(ClientKind.CODEX);
      if (d != null && Codex.tooOld(d.version())) {
        warnings.add("Codex " + d.version() + " ignores http_headers_helper: update it to " + Codex.HELPER_SINCE
            + " or later first.");
      }
    }
    String addToCursor = client == ClientKind.CURSOR && e.env() != Env.PROD ? addToCursorLink(name, url) : null;
    FileChange change = new FileChange(file.toString(), env.display(file), snapshot.realPath().toString(),
        snapshot.exists(), "config", diff(before.isEmpty() && !snapshot.exists() ? null : before, after),
        snapshot.exists() ? env.display(snapshot.realPath()) + ".bak-" + java.time.LocalDate.now(clock.withZone(
            java.time.ZoneId.systemDefault())) : null, modeChange);
    Plan plan = new Plan(Ids.uuid(), PlanKind.CONNECT, client, e.slug(), e.id(), e.env(), method, grantId, label, name,
        snapshot, after, existing.map(ClientLinkEntity::getId).orElse(null), token, null, null,
        clock.instant().plus(PREVIEW_TTL));
    keep(plan);
    return new Preview(plan.id(), client.id(), e.slug(), e.env().id(), KebabCase.of(method), repair, List.of(change),
        List.copyOf(notes), List.copyOf(warnings), addToCursor, plan.expiresAt());
  }

  private Preview previewMcpb(ClientAdapter a, EndpointService.Summary e, String grantId, String label, boolean repair,
      Optional<ClientLinkEntity> existing, List<String> notes, List<String> warnings) {
    Path target = env.mcpbDirectory().resolve(McpbBuilder.fileName(e.slug()));
    McpbBuilder.Spec spec = new McpbBuilder.Spec(e.slug(), e.env(), grantId, CoreBuild.VERSION, tools(e.slug()),
        env.brokerInfo(), env.os().nodePlatform());
    String manifest = McpbBuilder.manifestText(spec);
    notes.add("New file " + env.display(target) + " (manifest.json + server/gw-bridge.mjs)");
    notes.add("Then Claude Desktop opens its install dialog for \"" + McpbBuilder.displayName(e.slug(), e.env())
        + "\": click Install");
    notes.add("Not changed: claude_desktop_config.json · no token is stored anywhere");
    FileChange change = new FileChange(target.toString(), env.display(target), target.toString(),
        Files.exists(target), "mcpb", diff(null, manifest), null, null);
    Plan plan = new Plan(Ids.uuid(), PlanKind.CONNECT, a.kind(), e.slug(), e.id(), e.env(), LinkMethod.MCPB, grantId,
        label, null, null, null, existing.map(ClientLinkEntity::getId).orElse(null), null, spec, null,
        clock.instant().plus(PREVIEW_TTL));
    keep(plan);
    return new Preview(plan.id(), a.kind().id(), e.slug(), e.env().id(), "mcpb", repair, List.of(change),
        List.copyOf(notes), List.copyOf(warnings), null, plan.expiresAt());
  }

  private List<String> methodNotes(ClientKind client, LinkMethod method, EndpointService.Summary e) {
    List<String> notes = new ArrayList<>();
    switch (method) {
      case HELPER -> {
        notes.add("Codex asks before write tools too. No token is written; the helper gets a 15-minute token from "
            + "this app.");
        notes.add("Restart Codex: Settings › MCP servers › Restart");
      }
      case CONFIG_BRIDGE -> {
        notes.add("No token is written: the bridge gets a 15-minute token from this app.");
        notes.add(restartHint(client, "config-bridge"));
      }
      case OAUTH -> {
        notes.add(ClientAdapter.of(client).displayName() + " opens your browser and you allow it in this app. No "
            + "token in the file.");
        if (!endpoints.snapshot(e.slug()).oauthEnabled()) {
          notes.add("Turns on OAuth sign-in for endpoint " + e.slug() + ".");
        }
      }
      case TOKEN -> notes.add(restartHint(client, "token"));
      default -> { }
    }
    return notes;
  }

  /** {@code POST /api/clients/{client}/connect {previewId}}: writes what the preview showed. */
  public Connected connect(String clientId, String previewId) {
    ClientKind client = client(clientId);
    Plan plan = take(previewId, PlanKind.CONNECT, client);
    ClientAdapter a = ClientAdapter.of(client);
    stillAsPreviewed(plan);
    if (plan.method() == LinkMethod.MCPB) {
      return connectMcpb(a, plan);
    }
    TokenService.Issued issued = null;
    String content = plan.content();
    if (plan.method() == LinkMethod.TOKEN) {
      issued = tokens.issue(plan.slug(), plan.token().name(), plan.token().ttlDays(), "Connect " + a.displayName());
      try {
        // Once the token lives, the endpoint can't cross the prod line (EndpointConnections refuses it): a look
        // after issuing closes the moment between the check above and the token.
        stillAsPreviewed(plan);
      } catch (ProblemException e) {
        revokeQuietly(issued);
        throw e;
      }
      content = withToken(plan.snapshot().exists() ? plan.snapshot().content() : "", content, issued.token());
    }
    ConfigFileWriter.Written written;
    try {
      written = writer.write(plan.snapshot(), content, client.id(), a.tokenInFile(plan.method()));
    } catch (ConfigFileWriter.StaleFileException ex) {
      revokeQuietly(issued);
      throw ProblemException.conflict("The file changed", ex.getMessage());
    } catch (IOException | RuntimeException ex) {
      revokeQuietly(issued);
      throw ProblemException.conflict("Connect failed", "Can't write " + env.display(plan.snapshot().path()) + ": "
          + ex.getMessage());
    }
    if (plan.method() == LinkMethod.OAUTH) {
      enableOauth(plan.slug());
    }
    String tokenId = issued == null ? null : issued.id();
    try {
      ClientLinkEntity saved = writeTx.execute(s -> record(plan, written.realPath().toString(), plan.entryName(),
          written.sha256(), written.backup() == null ? null : written.backup().toString(), tokenId));
      List<String> notes = new ArrayList<>();
      notes.add(restartHint(client, KebabCase.of(plan.method())));
      publish(saved);
      return new Connected(link(saved), null, List.copyOf(notes));
    } catch (RuntimeException ex) {
      // The database refused: the file goes back to what it was, so it never names an access that doesn't exist.
      rollbackFile(plan, written);
      revokeQuietly(issued);
      throw ex;
    }
  }

  /**
   * Confirm writes for the endpoint the preview showed, on the side of prod the preview showed. What prod doesn't get
   * (a token in a file, sign-in through the browser) is decided at preview, and an endpoint can reach prod while a
   * preview waits (an empty endpoint's first prod connection): then the person checks the change again (409), nothing
   * is issued or written. The same for an endpoint deleted and made again under the same slug.
   */
  private void stillAsPreviewed(Plan plan) {
    EndpointService.Summary now = endpoint(plan.slug());
    if (!now.id().equals(plan.endpointId()) || now.env() != plan.env()) {
      // Titles the window shows as they are (a conflict titled "…changed" reads as "the file changed").
      throw now.env() == Env.PROD && plan.env() != Env.PROD
          ? ProblemException.conflict("Not on a prod endpoint", "Endpoint " + plan.slug() + " reaches prod now: "
              + "check the change again.")
          : ProblemException.conflict("Check the change again", "Endpoint " + plan.slug() + " isn't the one the "
              + "preview showed any more.");
    }
  }

  private Connected connectMcpb(ClientAdapter a, Plan plan) {
    Path target = env.mcpbDirectory().resolve(McpbBuilder.fileName(plan.slug()));
    try {
      McpbBuilder.write(target, plan.mcpb());
    } catch (IOException ex) {
      throw ProblemException.conflict("Connect failed", "Can't write " + env.display(target) + ": " + ex.getMessage());
    }
    try {
      ClientLinkEntity saved = writeTx.execute(s -> record(plan, target.toString(), null,
          ConfigFileWriter.sha256(readBytes(target)), null, null));
      publish(saved);
      return new Connected(link(saved), target.toString(), List.of("Claude Desktop opens its install dialog for \""
          + McpbBuilder.displayName(plan.slug(), plan.env()) + "\": click Install"));
    } catch (RuntimeException ex) {
      deleteQuietly(target);
      throw ex;
    }
  }

  /** The link (new, or the repaired one) and its grant, in one transaction with the Setup change. */
  private ClientLinkEntity record(Plan plan, String configPath, String entryName, String sha, String backup,
      String tokenId) {
    ClientAdapter a = ClientAdapter.of(plan.client());
    EndpointService.Summary e = endpoint(plan.slug());
    if (plan.grantId() != null && grants.find(plan.grantId()).isEmpty()) {
      grants.create(plan.grantId(), plan.client().id(), plan.slug(), plan.grantLabel(), "Connect " + a.displayName());
    }
    ClientLinkEntity link = plan.linkId() == null ? null : links.findById(plan.linkId()).orElse(null);
    if (link != null && link.getMethod() != plan.method()) {
      // Another way of signing in is another entry: the old one ends here, its access with it.
      link.setStatus("removed");
      link.removed(clock.instant());
      links.save(link);
      revokeOld(link, plan.grantId());
      link = null;
    }
    if (link == null) {
      link = new ClientLinkEntity(plan.client(), e.id(), plan.method(), "active", clock.instant());
    } else if (link.getTokenId() != null && tokenId != null && !link.getTokenId().equals(tokenId)) {
      tokens.revoke(link.getTokenId(), "replaced by a new token on Connect");
    }
    link.written(configPath, entryName, sha, backup);
    link.setGrantId(plan.grantId());
    link.setTokenId(tokenId);
    ClientLinkEntity saved = links.save(link);
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    details.put("method", KebabCase.of(plan.method()));
    details.put("file", env.display(Path.of(configPath)));
    if (plan.grantLabel() != null) {
      details.put("access", plan.grantLabel());
    }
    setupChanges.record(new SetupChange(SetupChangeRecorder.What.CLIENT, a.displayName() + " → " + plan.slug(),
        plan.linkId() == null ? "Not connected" : "Needs repair", "Connected", null, plan.env(), plan.slug(), null,
        details));
    return saved;
  }

  private void revokeOld(ClientLinkEntity link, String keepGrant) {
    if (link.getGrantId() != null && !link.getGrantId().equals(keepGrant)) {
      grants.find(link.getGrantId()).filter(g -> g.revokedAt() == null)
          .ifPresent(g -> grants.revoke(g.id(), "replaced by another way of connecting"));
    }
    if (link.getTokenId() != null) {
      tokens.revoke(link.getTokenId(), "replaced by another way of connecting");
    }
  }

  private void enableOauth(String slug) {
    EndpointSnapshot s = endpoints.snapshot(slug);
    if (!s.oauthEnabled()) {
      endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(null, null, null, null, null, null, true),
          endpoints.get(slug).version(), "Sign-in through the browser for a client");
    }
  }

  // ── Disconnect ──────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/clients/links/{id}/disconnect/preview}: the reverse edit; nothing is written. */
  public Preview previewDisconnect(String linkId) {
    ClientLinkEntity l = activeLink(linkId);
    String slug = slugOf(l);
    Env envOf = endpointEnv(l);
    List<String> notes = new ArrayList<>();
    List<FileChange> files = new ArrayList<>();
    Snapshot snapshot = null;
    String after = null;
    if (l.getMethod() == LinkMethod.MCPB) {
      Path file = Path.of(l.getConfigPath());
      files.add(new FileChange(file.toString(), env.display(file), file.toString(), Files.exists(file), "mcpb",
          diff(Files.exists(file) ? manifestOf(file) : null, null), null, null));
      notes.add("Claude Desktop keeps the extension until you remove it there: Settings › Extensions. Its access is "
          + "revoked now, so it stops working on its next call.");
    } else if (l.getConfigPath() != null) {
      snapshot = ConfigFileWriter.read(Path.of(l.getConfigPath()));
      String before = snapshot.exists() ? snapshot.content() : null;
      Optional<JsonNode> entry = before == null ? Optional.empty() : entryOf(l, before);
      if (entry.isPresent() && ours(entry.get(), slug, l.getGrantId())) {
        after = remove(l, before);
        files.add(new FileChange(l.getConfigPath(), env.display(snapshot.path()), snapshot.realPath().toString(),
            true, "config", diff(before, after), env.display(snapshot.realPath()) + ".bak-" + java.time.LocalDate.now(
                clock.withZone(java.time.ZoneId.systemDefault())), null));
      } else if (entry.isPresent()) {
        notes.add("The entry " + l.getEntryName() + " was changed outside the app, so it stays; remove it by hand if "
            + "you don't need it.");
      } else {
        notes.add("The entry is already gone from " + env.display(Path.of(l.getConfigPath())) + ".");
      }
    }
    notes.add(l.getTokenId() != null ? "The token stops working on its next call." : l.getGrantId() != null
        ? "The access is revoked: the current token stops on its next call, and no new one is issued." : "The "
        + "client's sign-in is no longer offered.");
    Plan plan = new Plan(Ids.uuid(), PlanKind.DISCONNECT, l.getClient(), slug, l.getEndpointId(), envOf,
        l.getMethod(), l.getGrantId(), null, l.getEntryName(), snapshot, after, l.getId(), null, null, null,
        clock.instant().plus(PREVIEW_TTL));
    keep(plan);
    return new Preview(plan.id(), l.getClient().id(), slug, envOf == null ? null : envOf.id(),
        KebabCase.of(l.getMethod()), false, List.copyOf(files), List.copyOf(notes), List.of(), null, plan.expiresAt());
  }

  /** {@code POST /api/clients/links/{id}/disconnect {previewId}}. */
  public Disconnected disconnect(String linkId, String previewId) {
    Plan plan = take(previewId, PlanKind.DISCONNECT, null);
    if (!linkId.equals(plan.linkId())) {
      throw ProblemException.conflict("Another preview", "This preview is for another entry: check the change again.");
    }
    return disconnect(activeLink(linkId), plan.snapshot(), plan.content(), "Disconnect");
  }

  private Disconnected disconnect(ClientLinkEntity l, Snapshot snapshot, String after, String reason) {
    List<String> notes = new ArrayList<>();
    boolean removed = false;
    if (l.getMethod() == LinkMethod.MCPB) {
      removed = deleteExtension(l.getConfigPath());
      notes.add("Remove the extension in Claude Desktop: Settings › Extensions.");
    } else if (snapshot != null && after != null) {
      try {
        writer.write(snapshot, after, l.getClient().id(), ConfigFileWriter.holdsToken(after));
        removed = true;
      } catch (ConfigFileWriter.StaleFileException ex) {
        throw ProblemException.conflict("The file changed", ex.getMessage());
      } catch (IOException ex) {
        throw ProblemException.conflict("Disconnect failed", "Can't write " + env.display(snapshot.path()) + ": "
            + ex.getMessage());
      }
    }
    String slug = slugOf(l);
    Env envOf = endpointEnv(l);
    int rejected = 0;
    if (l.getGrantId() != null) {
      Optional<Grant> g = grants.find(l.getGrantId());
      if (g.isPresent() && g.get().revokedAt() == null) {
        rejected += grants.revoke(l.getGrantId(), reason);
      }
    }
    if (l.getTokenId() != null) {
      rejected += tokens.revoke(l.getTokenId(), reason);
    }
    boolean entryRemoved = removed;
    writeTx.executeWithoutResult(s -> {
      ClientLinkEntity row = links.findById(l.getId()).orElseThrow();
      row.setStatus("removed");
      row.removed(clock.instant());
      links.save(row);
      ObjectNode details = JsonNodeFactory.instance.objectNode();
      details.put("method", KebabCase.of(l.getMethod()));
      details.put("entryRemoved", entryRemoved);
      setupChanges.record(new SetupChange(SetupChangeRecorder.What.CLIENT, ClientAdapter.of(l.getClient())
          .displayName() + " → " + slug, "Connected", "Disconnected", null, envOf, slug, null, details));
    });
    if (l.getTokenId() != null) {
      purgeBackupsHolding(l.getClient(), l.getTokenId());
    }
    publishRemoved(l, slug);
    return new Disconnected(l.getId(), removed, rejected, List.copyOf(notes));
  }

  /** {@code POST /api/clients/claude-desktop/remove-bridge}: every access of Claude Desktop ends, files go. */
  public BridgeRemoved removeBridge() {
    List<String> revoked = new ArrayList<>();
    List<String> files = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    int rejected = 0;
    for (ClientLinkEntity l : readTx.execute(s -> links.findByClientAndRemovedAtIsNull(ClientKind.CLAUDE_DESKTOP))) {
      Snapshot snapshot = null;
      String after = null;
      if (l.getMethod() == LinkMethod.CONFIG_BRIDGE && l.getConfigPath() != null) {
        snapshot = ConfigFileWriter.read(Path.of(l.getConfigPath()));
        Optional<JsonNode> entry = snapshot.exists() ? entryOf(l, snapshot.content()) : Optional.empty();
        if (entry.isPresent() && ours(entry.get(), slugOf(l), l.getGrantId())) {
          after = remove(l, snapshot.content());
        }
      }
      grants.find(l.getGrantId() == null ? "" : l.getGrantId()).ifPresent(g -> revoked.add(g.label() + " → "
          + g.endpointSlug()));
      Disconnected d = disconnect(l, snapshot, after, "Remove bridge");
      rejected += d.rejectedApprovals();
      if (d.entryRemoved()) {
        files.add(l.getMethod() == LinkMethod.MCPB ? env.display(Path.of(l.getConfigPath()))
            : env.display(Path.of(l.getConfigPath())) + " · " + l.getEntryName());
      }
    }
    // Grants of Claude Desktop without a link (a Connect whose link went away) end as well.
    for (Grant g : grants.forClient(ClientKind.CLAUDE_DESKTOP.id())) {
      if (g.revokedAt() == null) {
        rejected += grants.revoke(g.id(), "Remove bridge");
        revoked.add(g.label() + " → " + g.endpointSlug());
      }
    }
    notes.add("Remove the extensions in Claude Desktop: Settings › Extensions. They stopped working already.");
    return new BridgeRemoved(List.copyOf(revoked), List.copyOf(files), rejected, List.copyOf(notes));
  }

  // ── Check ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/clients/links/{id}/check}: config, broker and grant, the bridge itself, what the client gets. */
  public Check check(String linkId) {
    ClientLinkEntity l = activeLink(linkId);
    String slug = slugOf(l);
    List<Step> steps = new ArrayList<>();
    Optional<JsonNode> entry = Optional.empty();
    // 1. The config.
    long t0 = System.nanoTime();
    if (l.getMethod() == LinkMethod.MCPB) {
      Path file = Path.of(l.getConfigPath());
      steps.add(Files.isRegularFile(file) ? step("config", "Extension file", "ok", env.display(file), t0)
          : step("config", "Extension file", "error", env.display(file) + " is gone: connect again", t0));
    } else if (l.getConfigPath() != null) {
      try {
        Path file = Path.of(l.getConfigPath());
        Snapshot s = ConfigFileWriter.read(file);
        entry = s.exists() ? entryOf(l, s.content()) : Optional.empty();
        if (entry.isEmpty()) {
          steps.add(step("config", "Config entry", "error", "Removed outside the app: " + env.display(file)
              + " has no " + l.getEntryName(), t0));
        } else {
          List<String> notes = new ArrayList<>();
          if (Files.isSymbolicLink(file)) {
            notes.add("a symlink to " + s.realPath());
          }
          if (!Files.isWritable(s.realPath())) {
            notes.add("not writable");
          }
          Integer port = portOf(entry.get());
          boolean disabled = entry.get().path("enabled").isBoolean() && !entry.get().path("enabled").asBoolean();
          String state = disabled || (port != null && port != listener.port()) ? "warning" : "ok";
          String text = env.display(file) + " · " + l.getEntryName()
              + (port != null && port != listener.port() ? " · points at port " + port + ", needs repair" : "")
              + (disabled ? " · enabled = false in the client" : "")
              + (notes.isEmpty() ? "" : " · " + String.join(", ", notes));
          steps.add(step("config", "Config entry", state, text, t0));
        }
      } catch (ConfigEditException ex) {
        steps.add(step("config", "Config entry", "error", ex.getMessage(), t0));
      }
    }
    if (l.getMethod() == LinkMethod.HELPER || l.getMethod() == LinkMethod.CONFIG_BRIDGE) {
      boolean shim = BridgeShimInstaller.ready(env);
      steps.add(step("shim", "Bridge launcher", shim ? "ok" : "error", shim ? env.display(env.shim())
          : env.display(env.shim()) + " is missing: restart GateRook", System.nanoTime()));
    }
    // 2. Broker and access.
    t0 = System.nanoTime();
    boolean usesBroker = l.getGrantId() != null;
    boolean brokerUp = false;
    boolean awaitsPerson = false;
    if (usesBroker) {
      BrokerProbe.Result b = BrokerProbe.ping(env.brokerInfo(), Duration.ofSeconds(2));
      brokerUp = b.running();
      steps.add(step("broker", "Broker", b.running() ? "ok" : "error", b.text(), t0));
      t0 = System.nanoTime();
      Optional<Grant> g = grants.find(l.getGrantId());
      if (g.isEmpty() || !g.get().active()) {
        steps.add(step("grant", "Access", "error", g.isEmpty() ? "The access is gone: connect again"
            : g.get().revokedAt() != null ? "Access " + g.get().label() + " was revoked" : "Access "
            + g.get().label() + " was changed outside GateRook", t0));
      } else {
        awaitsPerson = grants.awaitsConfirmation(g.get());
        String text = "Access " + g.get().label() + " (" + g.get().prefix() + ") is active"
            + (awaitsPerson ? "; its first token in this session waits for you to allow it" : "");
        steps.add(step("grant", "Access", "ok", text, t0));
      }
    } else if (l.getTokenId() != null) {
      Optional<TokenService.TokenRow> token = tokens.list(slug).stream().filter(t -> t.id().equals(l.getTokenId()))
          .findFirst();
      String state = token.map(TokenService.TokenRow::state).orElse("gone");
      steps.add(step("token", "Token", "active".equals(state) ? "ok" : "error", token.map(t -> "Token " + t.name()
          + " · " + state + (t.expiresAt() != null ? " · expires " + t.expiresAt() : "")).orElse("The token is gone: "
          + "connect again"), t0));
    }
    // 3. The bridge itself, run exactly as the config says — when the config says what GateRook wrote. A command
    // changed outside the app is never run, and the helper's token goes to this core only, not to the entry's url.
    // A prod access whose first token waits for the person isn't run either: its broker would ask "Codex wants to use
    // incident-prod" for what is this Check, and an Allow given to it would hold for the client all session.
    t0 = System.nanoTime();
    String ourUrl = listener.origin() + "/mcp/" + slug;
    boolean runs = l.getMethod() == LinkMethod.HELPER || l.getMethod() == LinkMethod.CONFIG_BRIDGE;
    if (runs && awaitsPerson) {
      steps.add(step("bridge", l.getMethod() == LinkMethod.HELPER ? "Helper" : "Bridge", "skipped",
          "Not run: the first token of this prod access waits for you to allow it when "
          + ClientAdapter.of(l.getClient()).displayName() + " asks, and Check doesn't ask in its place.", t0));
    } else if (l.getMethod() == LinkMethod.HELPER && entry.isPresent() && brokerUp) {
      String helper = entry.get().path("http_headers_helper").asString("");
      if (!helper.equals(ourHelper(l, slug))) {
        steps.add(step("bridge", "Helper", "error", notOurs(l), t0));
      } else {
        BridgeSelfTest.Outcome o = BridgeSelfTest.helper(env, helper, ourUrl);
        steps.add(new Step("bridge", "Helper", o.ok() ? "ok" : "error", o.text(), o.ms()));
      }
    } else if (l.getMethod() == LinkMethod.CONFIG_BRIDGE && entry.isPresent() && brokerUp) {
      List<String> argv = BridgeSelfTest.argv(entry.get());
      if (!argv.equals(ourBridgeArgv(l, slug, ourUrl))) {
        steps.add(step("bridge", "Bridge", "error", notOurs(l), t0));
      } else {
        BridgeSelfTest.Outcome o = BridgeSelfTest.stdio(env, argv);
        steps.add(new Step("bridge", "Bridge", o.ok() ? "ok" : "error", o.text(), o.ms()));
      }
    } else if (usesBroker) {
      steps.add(step("bridge", "Bridge", "skipped", l.getMethod() == LinkMethod.MCPB
          ? "Claude Desktop runs the extension's bridge itself" : "Needs the broker and the entry", t0));
    }
    // 4. What the client gets.
    t0 = System.nanoTime();
    EndpointService.Summary e = endpoint(slug);
    steps.add(step("tools", "Tools", "ok", ClientAdapter.of(l.getClient()).displayName() + " can call "
        + (e.tools().read() + e.tools().approval()) + " tools of " + slug + ": " + e.tools().read() + " read only, "
        + e.tools().approval() + " with your approval" + (e.env() == Env.PROD ? " · reaches a prod endpoint" : ""),
        t0));
    if (l.getClient() == ClientKind.CODEX) {
      Detection d = detections.get(ClientKind.CODEX);
      if (d != null && d.version() != null) {
        steps.add(step("version", "Codex", Codex.tooOld(d.version()) ? "warning" : "ok", "Codex " + d.version()
            + (Codex.tooOld(d.version()) ? ": update to " + Codex.HELPER_SINCE + " or later, older versions ignore "
            + "the helper" : ""), System.nanoTime()));
      }
    }
    boolean failed = steps.stream().anyMatch(s -> s.state().equals("error"));
    boolean warned = steps.stream().anyMatch(s -> s.state().equals("warning"));
    return new Check(l.getId(), failed ? "Something needs you" : warned ? "Works, with warnings" : "All good",
        List.copyOf(steps));
  }

  private static Step step(String id, String title, String state, String text, long startNanos) {
    return new Step(id, title, state, text, (System.nanoTime() - startNanos) / 1_000_000);
  }

  /** The {@code http_headers_helper} GateRook writes for this Codex link. */
  private String ourHelper(ClientLinkEntity l, String slug) {
    return env.helperCommand("headers", "--endpoint", slug, "--grant", l.getGrantId());
  }

  /** {@code command} and {@code args} of the stdio entry GateRook writes for this link. */
  private List<String> ourBridgeArgv(ClientLinkEntity l, String slug, String url) {
    Entry ours = ClientAdapter.of(l.getClient()).entry(env, LinkMethod.CONFIG_BRIDGE, l.getEntryName(),
        new EntryValues(slug, url, l.getGrantId(), null, "writes", 60));
    return BridgeSelfTest.argv(ours.json());
  }

  private static String notOurs(ClientLinkEntity l) {
    return "The command of " + l.getEntryName() + " isn't the one GateRook wrote, so Check doesn't run it: connect "
        + "again to repair the entry, or remove it if you changed it on purpose.";
  }

  // ── Backups and restore ─────────────────────────────────────────────────────────────────────────────────────

  /** {@code GET /api/clients/{client}/backups}: the app's copies, newest first. */
  public List<Backup> backups(String clientId) {
    ClientKind client = client(clientId);
    List<Backup> out = new ArrayList<>();
    for (Path p : writer.backups(client.id())) {
      try {
        out.add(new Backup(p.getFileName().toString(), p.getFileName().toString(), Files.getLastModifiedTime(p)
            .toInstant(), Files.size(p)));
      } catch (IOException ignored) {
        // Gone meanwhile.
      }
    }
    return out;
  }

  /** {@code POST /api/clients/{client}/restore/preview {backupId}}: the current file → the copy. */
  public Preview previewRestore(String clientId, String backupId) {
    ClientKind client = client(clientId);
    Path copy = backupFile(client, backupId);
    String content;
    try {
      content = Files.readString(copy, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw ProblemException.notFound("Backup not found", "There is no backup " + backupId + ".");
    }
    Path file = ClientAdapter.of(client).configPath(env);
    Snapshot snapshot = ConfigFileWriter.read(file);
    FileChange change = new FileChange(file.toString(), env.display(file), snapshot.realPath().toString(),
        snapshot.exists(), "restore", view(DiffService.diff(masked(snapshot.exists() ? snapshot.content() : null),
            masked(content))),
        snapshot.exists() ? env.display(snapshot.realPath()) + ".bak-" + java.time.LocalDate.now(clock.withZone(
            java.time.ZoneId.systemDefault())) : null, null);
    Plan plan = new Plan(Ids.uuid(), PlanKind.RESTORE, client, null, null, null, null, null, null, null, snapshot,
        content, null, null, null, backupId, clock.instant().plus(PREVIEW_TTL));
    keep(plan);
    List<String> notes = List.of("Entries GateRook wrote after this copy are gone after the restore; their "
        + "access stays until you disconnect them.");
    return new Preview(plan.id(), client.id(), null, null, null, false, List.of(change), notes, List.of(), null,
        plan.expiresAt());
  }

  /** {@code POST /api/clients/{client}/restore {previewId}}. */
  public Restored restore(String clientId, String previewId) {
    ClientKind client = client(clientId);
    Plan plan = take(previewId, PlanKind.RESTORE, client);
    try {
      writer.write(plan.snapshot(), plan.content(), client.id(), ConfigFileWriter.holdsToken(plan.content()));
    } catch (ConfigFileWriter.StaleFileException ex) {
      throw ProblemException.conflict("The file changed", ex.getMessage());
    } catch (IOException ex) {
      throw ProblemException.conflict("Restore failed", "Can't write " + env.display(plan.snapshot().path()) + ": "
          + ex.getMessage());
    }
    writeTx.executeWithoutResult(s -> setupChanges.record(new SetupChange(SetupChangeRecorder.What.CLIENT,
        ClientAdapter.of(client).displayName() + " config", null, "Restored " + plan.backupId(), null, null, null,
        null, null)));
    return new Restored(client.id(), plan.snapshot().path().toString(), plan.backupId());
  }

  private Path backupFile(ClientKind client, String backupId) {
    if (backupId == null || !backupId.matches("[0-9A-Za-z._-]{1,200}") || backupId.contains("..")) {
      throw ProblemException.notFound("Backup not found", "There is no backup " + backupId + ".");
    }
    Path p = writer.backupsOf(client.id()).resolve(backupId);
    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
      throw ProblemException.notFound("Backup not found", "There is no backup " + backupId + ".");
    }
    return p;
  }

  /**
   * Deletes the copies of {@code client}'s config (the app's and the {@code .bak-} files next to it) that still hold
   * the now revoked named token {@code tokenId}: a backup must not outlive the token's revocation (ARCHITECTURE.md
   * §7.2).
   */
  public void purgeBackupsHolding(ClientKind client, String tokenId) {
    List<Path> candidates = new ArrayList<>(writer.backups(client.id()));
    Path config = ClientAdapter.of(client).configPath(env);
    Path dir = config.toAbsolutePath().getParent();
    if (dir != null && Files.isDirectory(dir)) {
      try (Stream<Path> s = Files.list(dir)) {
        s.filter(p -> p.getFileName().toString().startsWith(config.getFileName() + ".bak-")).forEach(candidates::add);
      } catch (IOException ignored) {
        // Nothing to purge there.
      }
    }
    for (Path p : candidates) {
      try {
        if (Files.size(p) > ConfigFileWriter.MAX_BYTES) {
          continue;
        }
        Matcher m = NAMED_TOKEN.matcher(Files.readString(p, StandardCharsets.UTF_8));
        while (m.find()) {
          if (tokens.namedTokenIdOf(m.group()).filter(tokenId::equals).isPresent()) {
            Files.deleteIfExists(p);
            log.info("Deleted {}: it held the revoked token of a {} entry", env.display(p), client.id());
            break;
          }
        }
      } catch (IOException | RuntimeException ignored) {
        // Unreadable copies stay; they are owner-only.
      }
    }
  }

  /** The links whose named token is {@code tokenId}. */
  List<ClientLinkEntity> linksWithToken(String tokenId) {
    return readTx.execute(s -> links.findByTokenId(tokenId));
  }

  // ── Manual setup, the extension file ────────────────────────────────────────────────────────────────────────

  /** {@code GET /api/clients/manual?endpoint=}: MCP Inspector and other apps, {@code ‹token›} in place of a token. */
  public Manual manual(String slug) {
    EndpointService.Summary e = endpoint(slug);
    String url = listener.origin() + "/mcp/" + e.slug();
    String name = "gateway-" + e.slug();
    ObjectNode server = JSON.createObjectNode();
    server.put("type", "streamable-http");
    server.put("url", url);
    server.putObject("headers").put("Authorization", "Bearer ‹token›");
    ObjectNode config = JSON.createObjectNode();
    config.putObject("mcpServers").set(name, server);
    String json = JsonEntryEditor.pretty("x", config).substring(5) + "\n";
    List<Snippet> snippets = List.of(
        new Snippet("inspector-config", "gw-inspector.json (chmod 600: it holds the token)", "json", json),
        new Snippet("inspector-ui", "Start MCP Inspector on " + e.slug(), "sh",
            "npx -y @modelcontextprotocol/inspector@2.12.0 --config gw-inspector.json --server " + name),
        new Snippet("inspector-cli", "List the tools from a terminal", "sh",
            "npx -y @modelcontextprotocol/inspector@2.12.0 --cli --config gw-inspector.json --server " + name
                + " --method tools/list"),
        new Snippet("toml", "An app that reads TOML", "toml", "[mcp_servers." + name + "]\nurl = "
            + TomlBlockEditor.basic(url) + "\nhttp_headers = { Authorization = \"Bearer ‹token›\" }\n"));
    List<OtherEndpoint> others = endpoints.list().stream().filter(o -> !o.slug().equals(e.slug()))
        .map(o -> new OtherEndpoint(o.slug(), listener.origin() + "/mcp/" + o.slug())).toList();
    return new Manual(e.slug(), url, "streamable-http", others, snippets);
  }

  /**
   * {@code GET /internal/clients/links/{id}/mcpb}: the extension Electron opens in Claude Desktop — a real path under
   * {@code ~/.gaterook/clients/claude-desktop/} ending in {@code .mcpb}, nothing else.
   */
  public Path mcpbPath(String linkId) {
    ClientLinkEntity l = activeLink(linkId);
    if (l.getMethod() != LinkMethod.MCPB || l.getConfigPath() == null) {
      throw ProblemException.notFound("No extension", "This entry has no extension file.");
    }
    try {
      Path real = Path.of(l.getConfigPath()).toRealPath();
      Path dir = env.mcpbDirectory().toRealPath();
      if (!real.startsWith(dir) || !real.getFileName().toString().endsWith(".mcpb")) {
        throw ProblemException.notFound("No extension", "The extension isn't where GateRook builds them.");
      }
      return real;
    } catch (IOException e) {
      throw ProblemException.notFound("No extension", "The extension file is gone: connect again.");
    }
  }

  // ── Endpoint removal (EndpointRemovalParticipant through ClientLinks) ───────────────────────────────────────

  /**
   * The endpoint goes: its links end in the same transaction (the database would cascade them; Hibernate's context
   * must not hold them), its extensions are deleted, and the entries in client configs are named so the person can
   * remove them. Returns one line per client.
   */
  List<String> endpointRemoving(String endpointId, String slug) {
    List<String> out = new ArrayList<>();
    for (ClientLinkEntity l : links.findByEndpointIdAndRemovedAtIsNull(endpointId)) {
      String name = ClientAdapter.of(l.getClient()).displayName();
      if (l.getMethod() == LinkMethod.MCPB) {
        deleteExtension(l.getConfigPath());
        out.add(name + " (remove the extension \"GateRook · " + slug + "\" in Claude Desktop)");
      } else if (l.getConfigPath() != null) {
        out.add(name + " (its entry " + l.getEntryName() + " in " + env.display(Path.of(l.getConfigPath()))
            + " stops working)");
      } else {
        out.add(name);
      }
    }
    // Removed ones too: every row that points at the endpoint goes before it.
    links.deleteAll(links.findAll().stream().filter(l -> l.getEndpointId().equals(endpointId)).toList());
    links.flush();
    return out;
  }

  /** Overview's "Connect a client" and the Clients node (platform.SetupProgress). */
  ai.msrv.gaterook.core.platform.SetupProgress.Progress progress() {
    List<ClientLinkEntity> active = activeLinks();
    Map<String, EndpointService.Summary> byId = endpointsById();
    List<Link> views = active.stream().map(l -> link(l, byId)).toList();
    Set<String> clients = new java.util.LinkedHashSet<>();
    views.forEach(v -> clients.add(ClientAdapter.of(client(v.client())).displayName()));
    boolean attention = views.stream().anyMatch(v -> !Set.of("connected", "needs-restart").contains(v.status()));
    boolean namedTokens = endpoints.list().stream().anyMatch(e -> e.tokensActive() > 0);
    return new ai.msrv.gaterook.core.platform.SetupProgress.Progress(clients.size(), !clients.isEmpty() || namedTokens,
        attention, clients.isEmpty() ? null : String.join(", ", clients));
  }

  // ── The demo world ──────────────────────────────────────────────────────────────────────────────────────────

  /** One client entry of the demo world: what the window's Connect would make, with a first token taken. */
  public record DemoLink(String client, String endpoint, String method, String grantLabel) {}

  /** The grant a demo link got: the demo's calls and requests come from it. */
  public record DemoGrant(String client, String endpoint, String grantId, String label) {}

  /**
   * The kit's clients (design/BLUEPRINT.md §9) for {@code GW_DEMO=1}: the clients of {@code detected} get their config
   * folder, {@code files} are written relative to the demo user home, and every link is connected through preview and
   * Confirm as the window does it, then takes its first bridge token (a prod grant allowed first), so the page shows
   * it connected. Only into a sandboxed home: never the developer's own Claude, Codex or Cursor.
   */
  public List<DemoGrant> seedDemo(List<String> detected, Map<String, String> files, List<DemoLink> demoLinks)
      throws IOException {
    if (!env.sandboxed()) {
      throw new IllegalStateException("The demo's clients go into a demo user home only");
    }
    Path home = env.userHome().toAbsolutePath().normalize();
    for (String id : detected) {
      Files.createDirectories(ClientAdapter.of(client(id)).configPath(env).getParent());
    }
    for (Map.Entry<String, String> f : files.entrySet()) {
      Path target = home.resolve(f.getKey()).normalize();
      if (!target.startsWith(home) || target.equals(home)) {
        throw new IllegalArgumentException("A demo file outside the demo home: " + f.getKey());
      }
      Files.createDirectories(target.getParent());
      Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
    }
    List<DemoGrant> out = new ArrayList<>();
    for (DemoLink d : demoLinks) {
      Preview p = previewConnect(d.client(), new ConnectRequest(d.endpoint(), d.method(), d.grantLabel(), null, null));
      Connected c = connect(d.client(), p.previewId());
      String grantId = readTx.execute(s -> links.findById(c.link().id()).map(ClientLinkEntity::getGrantId)
          .orElse(null));
      if (grantId == null) {
        continue;
      }
      Grant g = grants.find(grantId).orElseThrow();
      if (grants.awaitsConfirmation(g)) {
        grants.confirm(grantId, true);
      }
      ClientGrants.TokenAnswer first = grants.issue(new ClientGrants.TokenRequest(d.client(), d.endpoint(), grantId,
          null, null));
      if (!first.ok()) {
        log.warn("The demo's {} on {} got no first token: {}", d.client(), d.endpoint(), first.error());
      }
      out.add(new DemoGrant(d.client(), d.endpoint(), grantId, g.label()));
    }
    scan();
    return List.copyOf(out);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private String edit(ClientKind client, String before, Entry entry, boolean repair) {
    if (client == ClientKind.CODEX) {
      String base = repair ? TomlBlockEditor.remove(before, entry.name()) : before;
      return TomlBlockEditor.append(base, entry.name(), entry.tomlBlock());
    }
    return JsonEntryEditor.upsert(before, entry.rootKey(), entry.name(), entry.json());
  }

  private String remove(ClientLinkEntity l, String before) {
    return l.getClient() == ClientKind.CODEX ? TomlBlockEditor.remove(before, l.getEntryName())
        : JsonEntryEditor.remove(before, "mcpServers", l.getEntryName());
  }

  private static String fragment(Entry entry) {
    return entry.tomlBlock() != null ? entry.tomlBlock() : JsonEntryEditor.pretty(entry.name(), entry.json());
  }

  /** {@code gateway-<slug>}, or {@code gateway-<slug>-2}, … when the name belongs to an entry that isn't ours. */
  private static String entryName(ClientKind client, String content, String slug) {
    Set<String> taken = new java.util.HashSet<>(client == ClientKind.CODEX ? TomlBlockEditor.names(content)
        : JsonEntryEditor.names(content, "mcpServers"));
    String base = "gateway-" + slug;
    String name = base;
    for (int i = 2; taken.contains(name); i++) {
      name = base + "-" + i;
    }
    if (name.length() > 48) {
      throw ProblemException.unprocessable("Name too long", "The endpoint's slug is too long for a client entry.",
          "/endpoint");
    }
    return name;
  }

  private String label(String requested, String client) {
    String label = requested == null || requested.isBlank() ? client + "-" + env.shortHostname() : requested;
    if (label.length() > 40 || !NAME.matcher(label).matches()) {
      throw ProblemException.unprocessable("Invalid access name", "Lowercase letters, digits and hyphens, like "
          + "codex-laptop.", "/grantLabel");
    }
    return label;
  }

  private static String modeChange(Snapshot s) {
    if (!s.exists()) {
      return "New file, readable only by you (0600) because it holds a token";
    }
    Set<PosixFilePermission> mode = s.permissions();
    if (mode != null && !mode.equals(PosixFilePermissions.fromString("rw-------"))) {
      return "Permissions change from " + PosixFilePermissions.toString(mode) + " to rw------- because it now holds "
          + "a token";
    }
    return null;
  }

  /**
   * {@code after} with {@code token} in place of {@code ‹new token›} inside the text our edit wrote only (between the
   * parts {@code before} and {@code after} share at either end): a placeholder the person's own file already held —
   * pasted from an "add it by hand" fragment — stays as it is.
   */
  static String withToken(String before, String after, String token) {
    int max = Math.min(before.length(), after.length());
    int head = 0;
    while (head < max && before.charAt(head) == after.charAt(head)) {
      head++;
    }
    int tail = 0;
    while (tail < max - head && before.charAt(before.length() - 1 - tail) == after.charAt(after.length() - 1 - tail)) {
      tail++;
    }
    return after.substring(0, head)
        + after.substring(head, after.length() - tail).replace(ClientAdapter.TOKEN_PLACEHOLDER, token)
        + after.substring(after.length() - tail);
  }

  /** {@code cursor://anysphere.cursor-deeplink/mcp/install?name=…&config=…}: the URL only, never a token. */
  static String addToCursorLink(String name, String url) {
    String config = "{\"url\":" + JSON.writeValueAsString(JsonNodeFactory.instance.stringNode(url)) + "}";
    String b64 = Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8));
    return "cursor://anysphere.cursor-deeplink/mcp/install?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
        + "&config=" + URLEncoder.encode(b64, StandardCharsets.UTF_8);
  }

  private List<McpbBuilder.Tool> tools(String slug) {
    return catalog.forEndpoint(endpoints.snapshot(slug)).tools().stream()
        .map(t -> new McpbBuilder.Tool(t.name(), t.title() == null || t.title().isBlank() ? t.description()
            : t.title())).toList();
  }

  /** Our own edits touch one region: drawn as it happened, the way the kit shows "What will change". */
  private static ClientViews.Diff diff(String before, String after) {
    return view(DiffService.splice(masked(before), masked(after)));
  }

  /**
   * A config text as a diff may show it: a token of ours that the file holds (a Cursor entry, a backup) becomes its
   * prefix, {@code gwt_K9xP…}, as on the Tokens tab; no answer of the admin API carries a token (§9).
   */
  static String masked(String text) {
    return text == null ? null : ANY_TOKEN.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
        m.group().substring(0, 8) + "…"));
  }

  private static ClientViews.Diff view(DiffService.Diff d) {
    return new ClientViews.Diff(d.added(), d.removed(), d.hunks().stream().map(h -> new ClientViews.Hunk(h.header(),
        h.lines().stream().map(l -> new ClientViews.Line(l.type(), l.oldNo(), l.newNo(), l.text())).toList()))
        .toList());
  }

  private static String manifestOf(Path mcpb) {
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(mcpb.toFile())) {
      java.util.zip.ZipEntry m = zip.getEntry("manifest.json");
      return m == null ? "" : new String(zip.getInputStream(m).readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  private static byte[] readBytes(Path p) {
    try {
      return Files.readAllBytes(p);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Deletes an extension GateRook built — {@code gaterook-<slug>.mcpb} in {@code clients/claude-desktop/}, not a
   * link — and nothing else: {@code client_link} rows carry no MAC, so a path changed in the database must not make
   * Disconnect delete another file of the account.
   */
  private boolean deleteExtension(String path) {
    if (path == null) {
      return false;
    }
    Path p = Path.of(path).toAbsolutePath().normalize();
    if (!env.mcpbDirectory().toAbsolutePath().normalize().equals(p.getParent())
        || !p.getFileName().toString().matches("gaterook-[a-z0-9-]+\\.mcpb") || Files.isSymbolicLink(p)) {
      log.warn("Didn't delete {}: it isn't an extension GateRook built", env.display(p));
      return false;
    }
    return deleteQuietly(p);
  }

  private static boolean deleteQuietly(Path p) {
    try {
      return Files.deleteIfExists(p);
    } catch (IOException e) {
      return false;
    }
  }

  private void rollbackFile(Plan plan, ConfigFileWriter.Written written) {
    try {
      Snapshot now = ConfigFileWriter.read(plan.snapshot().path());
      if (now.sha256().equals(written.sha256())) {
        if (plan.snapshot().exists()) {
          writer.write(now, plan.snapshot().content(), plan.client().id(), ConfigFileWriter.holdsToken(
              plan.snapshot().content()));
        } else {
          Files.deleteIfExists(written.realPath());
        }
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Couldn't put {} back after a failed Connect: {}", plan.snapshot().path(), e.getMessage());
    }
  }

  private void revokeQuietly(TokenService.Issued issued) {
    if (issued != null) {
      try {
        tokens.revoke(issued.id(), "Connect failed");
      } catch (RuntimeException e) {
        log.warn("Couldn't revoke token {} after a failed Connect", issued.name(), e);
      }
    }
  }

  private void keep(Plan plan) {
    Instant now = clock.instant();
    plans.values().removeIf(p -> p.expiresAt().isBefore(now));
    if (plans.size() >= MAX_PREVIEWS) {
      plans.values().stream().min(java.util.Comparator.comparing(Plan::expiresAt))
          .ifPresent(p -> plans.remove(p.id()));
    }
    plans.put(plan.id(), plan);
  }

  private Plan take(String previewId, PlanKind kind, ClientKind client) {
    Plan plan = previewId == null ? null : plans.remove(previewId);
    if (plan == null || plan.kind() != kind || (client != null && plan.client() != client)
        || plan.expiresAt().isBefore(clock.instant())) {
      throw ProblemException.notFound("Preview expired", "Check the change again: previews are kept for 10 minutes "
          + "and confirmed once.");
    }
    return plan;
  }

  private ClientLinkEntity activeLink(String linkId) {
    return readTx.execute(s -> links.findById(linkId == null ? "" : linkId).filter(l -> l.getRemovedAt() == null))
        .orElseThrow(() -> ProblemException.notFound("Not connected", "There is no such client entry."));
  }

  private EndpointService.Summary endpoint(String slug) {
    return endpoints.get(slug);
  }

  private EndpointService.Summary endpointById(String id) {
    return endpointsById().get(id);
  }

  private Map<String, EndpointService.Summary> endpointsById() {
    Map<String, EndpointService.Summary> out = new LinkedHashMap<>();
    endpoints.list().forEach(e -> out.put(e.id(), e));
    return out;
  }

  private String slugOf(ClientLinkEntity l) {
    EndpointService.Summary e = endpointById(l.getEndpointId());
    return e == null ? "?" : e.slug();
  }

  private Env endpointEnv(ClientLinkEntity l) {
    EndpointService.Summary e = endpointById(l.getEndpointId());
    return e == null ? null : e.env();
  }

  static ClientKind client(String id) {
    for (ClientKind k : ClientKind.values()) {
      if (k.id().equals(id)) {
        return k;
      }
    }
    throw ProblemException.notFound("Unknown client", "GateRook connects claude-desktop, codex, cursor and "
        + "antigravity.");
  }

  private static LinkMethod methodOf(String id) {
    for (LinkMethod m : LinkMethod.values()) {
      if (KebabCase.of(m).equals(id)) {
        return m;
      }
    }
    throw ProblemException.unprocessable("Unknown method", "mcpb, config-bridge, helper, token or oauth.", "/method");
  }

  private void publish(ClientLinkEntity l) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("client", l.getClient().id());
    data.put("link", l.getId());
    data.put("status", "connected");
    events.publish("clients.drift", data);
  }

  private void publishRemoved(ClientLinkEntity l, String slug) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("client", l.getClient().id());
    data.put("link", l.getId());
    data.put("endpoint", slug);
    data.put("status", "removed");
    events.publish("clients.drift", data);
  }

  /** Statuses by link, for the drift watcher. */
  Map<String, String> statuses() {
    Map<String, String> out = new LinkedHashMap<>();
    Map<String, EndpointService.Summary> byId = endpointsById();
    for (ClientLinkEntity l : activeLinks()) {
      Link v = link(l, byId);
      out.put(v.id(), v.client() + " " + v.status());
    }
    return out;
  }

  /** The config files of the live links, for the drift watcher. */
  List<Path> watchedFiles() {
    List<Path> out = new ArrayList<>();
    for (ClientLinkEntity l : activeLinks()) {
      if (l.getConfigPath() != null) {
        out.add(Path.of(l.getConfigPath()));
      }
    }
    for (ClientAdapter a : ClientAdapter.all()) {
      out.add(a.configPath(env));
    }
    return out;
  }

  /**
   * Whether each client's config file or its folder is there (client id → there), for the drift watcher: a cheap look
   * that notices a client installed or first opened while the app runs, without running {@code codex --version}.
   */
  Map<String, Boolean> configsPresent() {
    Map<String, Boolean> out = new LinkedHashMap<>();
    for (ClientAdapter a : ClientAdapter.all()) {
      Path config = a.configPath(env);
      Path folder = config.toAbsolutePath().getParent();
      out.put(a.kind().id(), Files.exists(config) || folder != null && Files.isDirectory(folder));
    }
    return out;
  }

  /** Kebab case of an enum ({@code CONFIG_BRIDGE} → {@code config-bridge}). */
  static final class KebabCase {
    private KebabCase() {}

    static String of(Enum<?> e) {
      return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }
}
