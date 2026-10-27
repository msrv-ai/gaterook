package ai.msrv.gaterook.core.admin.endpoints;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Modes;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.mcp.McpGateway;
import ai.msrv.gaterook.core.playground.PlaygroundService;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Endpoints and the endpoint page (ARCHITECTURE.md §6 endpoints, endpoint-detail, kit {@code endpoints.html},
 * {@code endpoint-detail.html}): the list with its counters and risk callouts, Create from Empty / Read-only dev /
 * Incident response, Duplicate, Disable with Undo, Delete; Tools (modes per tool × connection, "Set all to…", the Read
 * only confirmation of a declared tool), Connections (preview, add, remove), the risk callout with Split and Dismiss,
 * and Settings with Check. Every change is a Setup change and accepts a {@code reason}.
 */
@RestController
@RegisterReflectionForBinding({EndpointDtos.Summary.class, EndpointDtos.ToolCounts.class,
    EndpointDtos.ConnectionCounts.class, EndpointDtos.TokenCounts.class, EndpointDtos.Risk.class,
    EndpointDtos.Ingredients.class, EndpointDtos.Source.class, EndpointsController.Detail.class,
    EndpointsController.Wired.class, EndpointsController.Settings.class, EndpointsController.Usage.class,
    EndpointsController.Revision.class, EndpointsController.Group.class, EndpointsController.ToolRow.class,
    EndpointsController.Bulked.class, EndpointsController.Skipped.class, EndpointsController.Preview.class,
    EndpointsController.PreviewTool.class, EndpointsController.Removed.class, EndpointsController.Deleted.class,
    EndpointsController.SplitPreview.class, EndpointsController.Check.class, EndpointsController.StartFrom.class})
class EndpointsController {

  record Create(String slug, String description, String startFrom, String reason) {}

  record Duplicate(String slug, String reason) {}

  record Reason(String reason) {}

  record Delete(String confirmSlug, String reason) {}

  /** {@code mode}: {@code OFF}, {@code READ_ONLY} or {@code APPROVAL}; {@code connection} null for a core tool. */
  record SetMode(String toolKey, String connection, String mode, JsonNode overrides, Long version, String reason) {}

  record ConfirmReadOnly(String toolKey, String connection, String definitionHash, String reason) {}

  /** {@code group}: a connection's name, or null for every tool; {@code mode} also {@code DEFAULT}. */
  record Bulk(String group, String mode, String reason) {}

  record ConnectionRef(String connection) {}

  record Split(List<String> move, String newSlug, List<String> clients, String reason) {}

  record SettingsUpdate(String description, String access, String instructions, JsonNode limits, JsonNode protocol,
      JsonNode approvalWait, Boolean oauthEnabled, Long version, String reason, String confirm) {}

  // ── Responses ───────────────────────────────────────────────────────────────────────────────────────────────

  /** A connection on the endpoint; {@code problem}: why its tools don't run, or {@code null}. */
  record Wired(String name, String type, String typeName, String env, String health, String problem,
      EndpointDtos.ToolCounts tools) {}

  record Usage(long callsLastMinute, int rateLimitPerToken, int rateLimitPerTool) {}

  /** A revision the endpoint serves or was called in, with the clients that used it in the last 7 days. */
  record Revision(String rev, boolean served, List<String> clients, long calls) {}

  /**
   * {@code GET /api/endpoints/{slug}/settings}.
   *
   * @param instructions        what the person wrote, or {@code null}: then clients get {@code defaultInstructions}
   * @param defaultInstructions the text the gateway writes for this endpoint (its approval tools, the approval rules,
   *                            the {@code ‹masked›} line), shown in the field while the person wrote none
   */
  record Settings(String description, String access, String instructions, int instructionsMax, JsonNode limits,
      JsonNode protocol, JsonNode approvalWait, boolean oauthEnabled, long version, Usage usage,
      List<Revision> revisions, String defaultInstructions) {}

  /**
   * One "Start from" card of New endpoint ({@code GET /api/endpoint-starts}): what the endpoint would start with
   * now ("Read-only dev · 6 dev connections · 12 tools"), counted as its list row counts after Create.
   *
   * @param connections the connections it would wire, in the order it wires them
   * @param tools       its pairs by mode, core tools included ({@code read + approval} are on)
   */
  record StartFrom(String startFrom, String env, String access, List<String> connections,
      EndpointDtos.ToolCounts tools) {}

  /** {@code GET /api/endpoints/{slug}}: header, wiring, risk and settings. */
  record Detail(EndpointDtos.Summary endpoint, List<Wired> connections, Settings settings) {}

  /**
   * One tool × connection of Tools ({@code tool-policy-list}).
   *
   * @param readOnlyAllowed whether Read only may be chosen; else {@code reason} (natively disabled in the UI)
   */
  record ToolRow(String toolKey, String name, ToolDefinition.Annotations annotations, String declaredBy, String mode,
      String storedMode, boolean readOnlyAllowed, String reason, boolean readOnlyConfirmed, String definitionHash,
      JsonNode overrides, long version) {}

  record Group(String group, String connection, String env, List<ToolRow> tools) {}

  record Skipped(String toolKey, String reason) {}

  record Bulked(List<String> changed, List<Skipped> skipped) {}

  /** {@code locked}: why a mode is fixed ("locked by the read-only endpoint"). */
  record PreviewTool(String toolKey, String tool, String mode, String locked, String declaredBy) {}

  /** What adding a connection would do; {@code refusal}: why it can't be added. */
  record Preview(String connection, String env, List<PreviewTool> tools, List<String> warnings, String refusal) {}

  record Removed(List<String> stoppedTools) {}

  record Deleted(List<String> clients, List<String> tokensRevoked, int rejectedApprovals) {}

  record SplitPreview(String newSlug, EndpointDtos.Risk before, EndpointDtos.Risk remaining, EndpointDtos.Risk moved,
      List<String> movedTools) {}

  /** {@code POST …/check}: "18 tools · 2 revisions · instructions 765 / 4,000 · p95 overhead 3 ms". */
  record Check(int tools, int revisions, int instructionsChars, int instructionsMax, Long gatewayP95Ms,
      List<String> problems, String text) {}

  private final EndpointService endpoints;
  private final EndpointTools tools;
  private final EndpointConnections wiring;
  private final TokenService tokens;
  private final ToolCatalog catalog;
  private final ConnectionConfigs connections;
  private final ApprovalService approvals;
  private final AuditQueries audit;
  private final PolicyEngine engine;
  private final PlaygroundService playground;
  private final McpGateway gateway;
  private final Clock clock;

  EndpointsController(EndpointService endpoints, EndpointTools tools, EndpointConnections wiring, TokenService tokens,
      ToolCatalog catalog, ConnectionConfigs connections, ApprovalService approvals, AuditQueries audit,
      PolicyEngine engine, PlaygroundService playground, McpGateway gateway, Clock clock) {
    this.endpoints = endpoints;
    this.tools = tools;
    this.wiring = wiring;
    this.tokens = tokens;
    this.catalog = catalog;
    this.connections = connections;
    this.approvals = approvals;
    this.audit = audit;
    this.engine = engine;
    this.playground = playground;
    this.gateway = gateway;
    this.clock = clock;
  }

  // ── List ────────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/endpoints")
  List<EndpointDtos.Summary> list() {
    Map<String, Long> waiting = approvals.pending().stream().filter(a -> a.endpointSlug() != null)
        .collect(Collectors.groupingBy(ApprovalView::endpointSlug, Collectors.counting()));
    return endpoints.list().stream().map(s -> EndpointDtos.summary(s, waiting.getOrDefault(s.slug(), 0L),
        clients(s.slug()))).toList();
  }

  /**
   * New endpoint › Start from: each start with the connections and tools it would get now. Not under
   * {@code /api/endpoints/}: any word there may be an endpoint's slug.
   */
  @GetMapping("/api/endpoint-starts")
  List<StartFrom> startFrom() {
    List<StartFrom> out = new ArrayList<>();
    for (EndpointService.StartFrom s : EndpointService.StartFrom.values()) {
      EndpointService.StartPreview p = endpoints.previewStart(s);
      out.add(new StartFrom(p.startFrom(), p.env().id(), p.access(), p.connections(),
          new EndpointDtos.ToolCounts(p.tools().read(), p.tools().approval(), p.tools().off())));
    }
    return List.copyOf(out);
  }

  @PostMapping("/api/endpoints")
  ResponseEntity<EndpointDtos.Summary> create(@RequestBody Create request) {
    EndpointService.Summary s = endpoints.create(request.slug(), request.description(),
        EndpointService.StartFrom.of(request.startFrom() == null ? "empty" : request.startFrom()),
        Reasons.check(request.reason()));
    return ResponseEntity.status(HttpStatus.CREATED).body(EndpointDtos.summary(s, 0, List.of()));
  }

  @PostMapping("/api/endpoints/{slug}/duplicate")
  ResponseEntity<EndpointDtos.Summary> duplicate(@PathVariable String slug, @RequestBody Duplicate request) {
    EndpointService.Summary s = endpoints.duplicate(slug, request.slug(), Reasons.check(request.reason()));
    return ResponseEntity.status(HttpStatus.CREATED).body(EndpointDtos.summary(s, 0, List.of()));
  }

  @PostMapping("/api/endpoints/{slug}/disable")
  EndpointDtos.Summary disable(@PathVariable String slug, @RequestBody(required = false) Reason request) {
    return summary(endpoints.disable(slug, request == null ? null : Reasons.check(request.reason())));
  }

  @PostMapping("/api/endpoints/{slug}/enable")
  EndpointDtos.Summary enable(@PathVariable String slug, @RequestBody(required = false) Reason request) {
    return summary(endpoints.enable(slug, request == null ? null : Reasons.check(request.reason())));
  }

  @DeleteMapping("/api/endpoints/{slug}")
  Deleted delete(@PathVariable String slug, @RequestBody(required = false) Delete request) {
    EndpointService.DeleteSummary d = endpoints.delete(slug, request == null ? null : request.confirmSlug(),
        request == null ? null : Reasons.check(request.reason()));
    return new Deleted(d.clients(), d.tokensRevoked(), d.rejectedApprovals());
  }

  @PostMapping("/api/endpoints/{slug}/confirm-integrity")
  EndpointDtos.Summary confirmIntegrity(@PathVariable String slug,
      @RequestBody(required = false) ConfirmIntegrity request) {
    return summary(endpoints.confirmIntegrity(slug, request == null ? null : Reasons.check(request.reason()),
        request == null ? null : request.confirm()));
  }

  /** {@code confirm}: {@code reset-invalid} resets what an ordinary change couldn't have stored. */
  record ConfirmIntegrity(String reason, String confirm) {}

  // ── Detail ──────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/endpoints/{slug}")
  Detail get(@PathVariable String slug) {
    EndpointSnapshot s = endpoints.snapshot(slug);
    List<Wired> wired = new ArrayList<>();
    for (EndpointSnapshot.Wired w : s.connections()) {
      int read = 0;
      int approval = 0;
      int off = 0;
      for (EndpointSnapshot.Pair p : s.pairs()) {
        if (p.config().id().equals(w.config().id())) {
          switch (p.mode()) {
            case READ_ONLY -> read++;
            case APPROVAL -> approval++;
            case OFF -> off++;
          }
        }
      }
      String health = connections.byId(w.config().id()).map(c -> c.health().state() == null ? "unknown"
          : c.health().state().name().toLowerCase(Locale.ROOT)).orElse("unknown");
      wired.add(new Wired(w.config().name(), w.config().type(), w.manifest().name(), w.config().env().id(), health,
          w.problem(), new EndpointDtos.ToolCounts(read, approval, off)));
    }
    return new Detail(summary(endpoints.get(slug)), List.copyOf(wired), settings(s));
  }

  @GetMapping("/api/endpoints/{slug}/settings")
  Settings getSettings(@PathVariable String slug) {
    return settings(endpoints.snapshot(slug));
  }

  @PutMapping("/api/endpoints/{slug}/settings")
  Settings putSettings(@PathVariable String slug, @RequestBody SettingsUpdate request) {
    if (request.version() == null) {
      throw ApiRequestException.unprocessable("Version missing", "Send the version you edited.", "/version");
    }
    endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(request.description(), request.access(),
        request.instructions(), request.limits(), request.protocol(), request.approvalWait(), request.oauthEnabled()),
        request.version(), Reasons.check(request.reason()));
    return settings(endpoints.snapshot(slug));
  }

  @PostMapping("/api/endpoints/{slug}/check")
  Check check(@PathVariable String slug) {
    EndpointSnapshot s = endpoints.snapshot(slug);
    ToolCatalog.Catalog published = catalog.forEndpoint(s);
    int revisions = s.settings().path("protocol").path("revisions").size();
    JsonNode init = playground.initialize(slug);
    int chars = init.path("instructions").asString("").length();
    Instant now = clock.instant();
    Long p95 = audit.gatewayP95(slug, now.minus(Duration.ofHours(24)), now);
    String text = published.tools().size() + " tools · " + revisions + (revisions == 1 ? " revision" : " revisions")
        + " · instructions " + String.format(Locale.ROOT, "%,d", chars) + " / "
        + String.format(Locale.ROOT, "%,d", EndpointService.MAX_INSTRUCTIONS)
        + (p95 == null ? "" : " · p95 overhead " + p95 + " ms");
    List<String> problems = new ArrayList<>(published.problems());
    if (s.blocked()) {
      problems.add(0, s.blockedReason());
    }
    return new Check(published.tools().size(), revisions, chars, EndpointService.MAX_INSTRUCTIONS, p95,
        List.copyOf(problems), text);
  }

  // ── Tools ───────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/endpoints/{slug}/tools")
  List<Group> tools(@PathVariable String slug) {
    return tools.list(slug).stream().map(g -> new Group(g.group(), g.connection(), g.env(), g.tools().stream()
        .map(t -> new ToolRow(t.toolKey(), t.name(), t.annotations(), t.declaredBy(), Modes.id(t.mode()),
            Modes.id(t.storedMode()), t.readOnlyAllowed(), t.reason(), t.readOnlyConfirmed(), t.definitionHash(),
            t.overrides(), t.version())).toList())).toList();
  }

  @PutMapping("/api/endpoints/{slug}/tools")
  List<Group> setMode(@PathVariable String slug, @RequestBody SetMode request) {
    if (request.version() == null) {
      throw ApiRequestException.unprocessable("Version missing", "Send the version of the tool you changed.",
          "/version");
    }
    tools.setMode(slug, request.toolKey(), request.connection(), Modes.parse(request.mode(), "/mode"),
        request.overrides(), request.version(), Reasons.check(request.reason()));
    return tools(slug);
  }

  @PostMapping("/api/endpoints/{slug}/tools/confirm-read-only")
  List<Group> confirmReadOnly(@PathVariable String slug, @RequestBody ConfirmReadOnly request) {
    tools.confirmReadOnly(slug, request.toolKey(), request.connection(), request.definitionHash(),
        Reasons.check(request.reason()));
    return tools(slug);
  }

  @PostMapping("/api/endpoints/{slug}/tools/bulk")
  Bulked bulk(@PathVariable String slug, @RequestBody Bulk request) {
    String mode = request.mode() == null ? "" : request.mode().strip().toUpperCase(Locale.ROOT);
    if (!List.of("OFF", "READ_ONLY", "APPROVAL", "DEFAULT").contains(mode)) {
      throw ApiRequestException.unprocessable("Unknown mode", "Set all to OFF, READ_ONLY, APPROVAL or DEFAULT.",
          "/mode");
    }
    EndpointTools.BulkResult r = tools.bulk(slug, request.group() == null || request.group().isBlank() ? null
        : request.group(), mode, Reasons.check(request.reason()));
    return new Bulked(r.changed(), r.skipped().stream().map(x -> new Skipped(x.toolKey(), x.reason())).toList());
  }

  // ── Connections ─────────────────────────────────────────────────────────────────────────────────────────────

  @PostMapping("/api/endpoints/{slug}/connections/preview")
  Preview previewConnection(@PathVariable String slug, @RequestBody ConnectionRef request) {
    EndpointConnections.Preview p = wiring.preview(slug, request.connection());
    return new Preview(p.connection(), p.env() == null ? null : p.env().id(), p.tools().stream()
        .map(t -> new PreviewTool(t.toolKey(), t.tool(), Modes.id(t.mode()), t.locked(), t.declaredBy())).toList(),
        p.warnings(), p.refusal());
  }

  @PostMapping("/api/endpoints/{slug}/connections/{name}")
  Detail addConnection(@PathVariable String slug, @PathVariable String name,
      @RequestBody(required = false) Reason request) {
    wiring.add(slug, name, request == null ? null : Reasons.check(request.reason()));
    return get(slug);
  }

  @DeleteMapping("/api/endpoints/{slug}/connections/{name}")
  Removed removeConnection(@PathVariable String slug, @PathVariable String name,
      @RequestBody(required = false) Reason request) {
    return new Removed(wiring.remove(slug, name, request == null ? null : Reasons.check(request.reason())));
  }

  // ── Risk and split ──────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/endpoints/{slug}/risk")
  EndpointDtos.Risk risk(@PathVariable String slug) {
    return EndpointDtos.risk(endpoints.risk(slug));
  }

  @PostMapping("/api/endpoints/{slug}/risk/dismiss")
  EndpointDtos.Risk dismissRisk(@PathVariable String slug, @RequestBody(required = false) Reason request) {
    return EndpointDtos.risk(endpoints.dismissRisk(slug, request == null ? null : Reasons.check(request.reason())));
  }

  @PostMapping("/api/endpoints/{slug}/risk/restore")
  EndpointDtos.Risk restoreRisk(@PathVariable String slug, @RequestBody(required = false) Reason request) {
    return EndpointDtos.risk(endpoints.restoreRisk(slug, request == null ? null : Reasons.check(request.reason())));
  }

  @PostMapping("/api/endpoints/{slug}/split/preview")
  SplitPreview splitPreview(@PathVariable String slug, @RequestBody Split request) {
    EndpointService.SplitPreview p = endpoints.previewSplit(slug, moving(request), request.newSlug());
    return new SplitPreview(p.newSlug(), EndpointDtos.risk(p.before()), EndpointDtos.risk(p.remaining()),
        EndpointDtos.risk(p.moved()), p.movedTools());
  }

  @PostMapping("/api/endpoints/{slug}/split")
  ResponseEntity<EndpointDtos.Summary> split(@PathVariable String slug, @RequestBody Split request) {
    EndpointService.Summary s = endpoints.split(slug, moving(request), request.newSlug(),
        Reasons.check(request.reason()));
    return ResponseEntity.status(HttpStatus.CREATED).body(summary(s));
  }

  // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────────

  private EndpointDtos.Summary summary(EndpointService.Summary s) {
    long waiting = approvals.pending().stream().filter(a -> s.slug().equals(a.endpointSlug())).count();
    return EndpointDtos.summary(s, waiting, clients(s.slug()));
  }

  /** Clients with live access through a grant (issued on the fly), by family. */
  private List<String> clients(String slug) {
    return tokens.list(slug).stream().filter(t -> "grant".equals(t.kind()) && "active".equals(t.state()))
        .map(TokenService.TokenRow::client).filter(java.util.Objects::nonNull).distinct().sorted().toList();
  }

  private Settings settings(EndpointSnapshot s) {
    Instant now = clock.instant();
    PolicyEngine.Rates rates = engine.rates(s.env());
    List<Revision> revisions = new ArrayList<>();
    List<AuditQueries.Revision> used = audit.revisions(s.slug(), now.minus(Duration.ofDays(7)));
    JsonNode served = s.settings().path("protocol").path("revisions");
    for (JsonNode rev : served) {
      String r = rev.asString();
      // The legacy era also answers the older legacy revisions a client may negotiate.
      List<AuditQueries.Revision> of = used.stream().filter(u -> r.equals(u.protocolVersion())
          || ("2025-11-25".equals(r) && List.of("2025-06-18", "2025-03-26").contains(u.protocolVersion()))).toList();
      revisions.add(new Revision(r, true, of.stream().flatMap(u -> u.clients().stream()).distinct().sorted().toList(),
          of.stream().mapToLong(AuditQueries.Revision::calls).sum()));
    }
    return new Settings(s.description(), s.readOnly() ? "read-only" : "standard", s.instructions(),
        EndpointService.MAX_INSTRUCTIONS, s.settings().path("limits"), s.settings().path("protocol"),
        s.settings().path("approvalWait"), s.oauthEnabled(), s.version(),
        new Usage(audit.received(s.slug(), now.minus(Duration.ofMinutes(1))), rates.perToken(), rates.perTool()),
        List.copyOf(revisions), gateway.defaultInstructions(s));
  }

  private static List<String> moving(Split request) {
    if (request.move() == null || request.move().isEmpty()) {
      throw ApiRequestException.unprocessable("Nothing to move", "Choose the connections to move.", "/move");
    }
    if (request.newSlug() == null || request.newSlug().isBlank()) {
      throw ApiRequestException.unprocessable("Name missing", "Name the new endpoint.", "/newSlug");
    }
    return List.copyOf(request.move());
  }
}
