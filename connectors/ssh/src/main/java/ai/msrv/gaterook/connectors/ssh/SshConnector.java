package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The SSH connector (report 06, ARCHITECTURE.md §3): runbooks — fixed commands with typed parameters, saved as
 * {@code ssh-runbook} templates — through {@code ssh_run}, and any shell line through {@code ssh_exec}, which the
 * policy must allow and a person always approves.
 *
 * <p>What makes it safe: the host key must be one the person trusted before any credential is resolved; a parameter
 * reaches the command line only as a checked enum value, a number, a fixed literal or a single-quoted word; the
 * approver reads the exact line the host gets, and that stored line is what runs after approval; a command stops at
 * its deadline with TERM then KILL on the host, not just a closed channel.
 */
public final class SshConnector implements Connector {

  public static final String TYPE = "ssh";

  /**
   * Time kept back from the call's deadline: TERM, the two seconds before KILL and the close must fit before the
   * core gives up on the call, or the host would keep running what nobody waits for.
   */
  static final Duration STOP_MARGIN = SshExec.KILL_AFTER.plusMillis(500);

  /** A command needs at least this much time; with less it isn't started. */
  private static final Duration MIN_BUDGET = Duration.ofSeconds(1);

  private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

  /** How long a call waits for one of its connection's parallel slots, as the core's bulkhead does. */
  private static final Duration SLOT_WAIT = Duration.ofMillis(200);

  /** The policy's maximum of Parallel calls per connection: the core's bulkhead lets that many through. */
  private static final int MAX_PARALLEL = 16;

  private final ConnectorManifest manifest;
  private final RunbookKind runbookKind;
  private final SshClients clients;
  private final SshSessions sessions;
  private final Runbooks runbooks = new Runbooks();
  private final SshProbe probe;
  /**
   * Parallel calls per connection ({@code maxConcurrentCalls} of the SSH policy, prod 2) as {@code classify} last read
   * it: {@code execute} gets no policy, and the core's bulkhead knows no connection's own limit.
   */
  private final Map<String, Integer> parallel = new ConcurrentHashMap<>();
  private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();

  public SshConnector() {
    // Before any MINA class picks its providers (A23: BouncyCastle is not MINA's JCE provider).
    MinaSecurity.install();
    SchemaResources schemas = SchemaResources.of(SshConnector.class, "ssh");
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("SSH")
        .icon("terminal")
        .group("Remote access")
        .description("Runbooks with typed parameters. Any other command needs its own permission and approval.")
        .configSchema(schemas.load("config"))
        .policySchema(schemas.load("policy"))
        .tools(SshTools.definitions(schemas))
        .build();
    this.runbookKind = new RunbookKind(schemas.load("runbook.definition"));
    this.clients = new SshClients(BuiltIn.VERSION);
    this.sessions = new SshSessions(clients);
    this.probe = new SshProbe(sessions, runbooks);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  /**
   * The longest a command may run under these limits: the policy's command timeout, within the deadline the core
   * gives a call of a command tool ({@link Limits#commandCallTimeout}: the client's wait, or ten minutes after
   * approval) less the time its stop takes.
   */
  static Duration commandBudget(Limits limits, boolean approved) {
    Duration command = commandTimeout(limits);
    if (limits.toolTimeout() == null) {
      return command;
    }
    Duration budget = min(command, limits.commandCallTimeout(approved).minus(STOP_MARGIN));
    return budget.compareTo(MIN_BUDGET) < 0 ? MIN_BUDGET : budget;
  }

  /** Both SSH tools run a command: their calls last as long as its timeout needs (ARCHITECTURE.md §3, SSH). */
  @Override
  public boolean runsCommand(String tool) {
    return SshTools.RUN.equals(tool) || SshTools.EXEC.equals(tool);
  }

  private static Duration commandTimeout(Limits limits) {
    return limits == null || limits.commandTimeout() == null ? DEFAULT_COMMAND_TIMEOUT : limits.commandTimeout();
  }

  @Override
  public List<ToolDefinition> tools(EndpointView endpoint) {
    for (ConnectionConfig cfg : endpoint.connections()) {
      runbooks.offer(cfg.id(), endpoint.contributions());
    }
    return SshTools.forEndpoint(manifest.tools(), endpoint, runbooks);
  }

  @Override
  public List<TemplateKind> templateKinds() {
    return List.of(runbookKind);
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    parallel.put(cfg.id(), Math.max(1, Math.min(MAX_PARALLEL, policy.integer("maxConcurrentCalls",
        defaultParallel(cfg)))));
    SshSettings s;
    try {
      s = SshSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("config-invalid"), e.getMessage(), "Fix the connection in Setup.");
    }
    Classification method = methodAllowed(s, cfg, policy);
    if (method != null) {
      return method;
    }
    return switch (call.tool()) {
      case SshTools.RUN -> classifyRun(call, cfg, policy);
      case SshTools.EXEC -> classifyExec(call, cfg, s, policy);
      default -> Classification.deny(policy.rule("unknown-tool"), "SSH has no tool " + call.tool() + ".", null);
    };
  }

  /** {@code allowedCredentialMethods} covers the bastion's sign-in too. */
  private static Classification methodAllowed(SshSettings s, ConnectionConfig cfg, PolicyView policy) {
    List<String> allowed = policy.strings("allowedCredentialMethods");
    if (allowed.isEmpty()) {
      return null;
    }
    List<SshSettings.Credentials> used = new ArrayList<>(List.of(s.credentials()));
    if (s.jump() != null) {
      used.add(s.jump().credentials());
    }
    for (SshSettings.Credentials c : used) {
      if (!allowed.contains(c.method().id)) {
        List<String> labels = new ArrayList<>();
        for (String id : allowed) {
          SshSettings.Method m = SshSettings.Method.of(id);
          if (m != null) {
            labels.add(m.label);
          }
        }
        return Classification.deny(policy.rule("credential-method"), "Sign-in with a " + c.method().label
            + " isn't allowed on " + cfg.env().id() + ".", "Switch " + cfg.name() + " to "
            + String.join(" or ", labels) + " in Setup.");
      }
    }
    return null;
  }

  private Classification classifyRun(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    String name = call.string("runbook");
    Optional<Runbooks.Entry> found = name == null ? Optional.empty() : runbooks.find(cfg.id(), name);
    if (found.isEmpty()) {
      return Classification.deny(policy.rule("runbook-unknown"), "No runbook " + name + " on " + cfg.name() + ".",
          available(cfg));
    }
    Runbooks.Entry entry = found.get();
    if (entry.ambiguous()) {
      return Classification.deny(policy.rule("runbook-ambiguous"), "Two runbooks of " + cfg.name() + " are named "
          + name + ", so neither runs.", "Rename one of them in Templates.");
    }
    Runbook rb = entry.runbook();
    try {
      rb.values(call.arguments().get("params"));
    } catch (Runbook.InvalidParams e) {
      return Classification.deny(policy.rule("param-invalid"), e.getMessage(), rb.signature());
    }
    ObjectNode facts = JsonNodeFactory.instance.objectNode();
    facts.put("runbook", rb.name());
    facts.put("templateId", entry.templateId());
    facts.put("templateVersion", entry.version());
    facts.put("readOnly", rb.readOnly());
    if (rb.readOnly()) {
      return new Classification(Classification.Kind.READ, null, null, null, facts);
    }
    return Classification.mutation("Runbook " + rb.name() + " can change " + cfg.name() + ".", facts);
  }

  private static Classification classifyExec(ToolCall call, ConnectionConfig cfg, SshSettings s, PolicyView policy) {
    if (s.runbooksOnly()) {
      return Classification.deny(policy.rule("runbooks-only"), cfg.name() + " runs only runbooks.",
          "Use ssh_run, or ask for a runbook for this command.");
    }
    if (!policy.flag("allowExec", false)) {
      return Classification.deny(policy.rule("exec-not-allowed"), "Shell commands aren't allowed on "
          + cfg.env().id() + ".", "Use ssh_run with a runbook, or turn on Allow any shell command in Policies › "
          + cfg.env().id() + " › SSH.");
    }
    String problem = commandProblem(call.string("command"));
    if (problem != null) {
      return Classification.deny(policy.rule("command-invalid"), problem, null);
    }
    ObjectNode facts = JsonNodeFactory.instance.objectNode();
    facts.put("freeText", true);
    return Classification.mutation("A shell command on " + cfg.name() + " can change anything its user may.",
        facts);
  }

  /** A line the approver can read: no NUL or control characters but newline and tab, at most 8,000 characters. */
  static String commandProblem(String command) {
    if (command == null || command.isBlank()) {
      return "command is empty.";
    }
    if (command.length() > 8000) {
      return "command is longer than 8,000 characters.";
    }
    for (int i = 0; i < command.length(); i++) {
      char ch = command.charAt(i);
      if ((ch < 0x20 && ch != '\n' && ch != '\t') || ch == 0x7f) {
        return "command has a control character at position " + (i + 1) + ". Approvers must see every byte "
            + "that runs.";
      }
    }
    return null;
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    SshSettings s;
    try {
      s = SshSettings.of(cfg);
    } catch (ConnectorException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    String target = cfg.name() + " · " + s.targetLabel();
    if (SshTools.EXEC.equals(call.tool())) {
      String command = call.string("command");
      String wire = SshActions.execWire(command, s);
      return SshActions.execPreview(command, wire, target, cfg, s).withPayload(SshActions.execPayload(wire));
    }
    String name = call.string("runbook");
    Runbooks.Entry entry = runbooks.find(cfg.id(), name).filter(e -> !e.ambiguous())
        .orElseThrow(() -> new IllegalArgumentException("No runbook " + name + " on " + cfg.name()));
    Runbook rb = entry.runbook();
    Map<String, JsonNode> values;
    try {
      values = rb.values(call.arguments().get("params"));
    } catch (Runbook.InvalidParams e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    String wire = rb.render(values, s.advanced().remoteDeadline(), SshActions.remoteSeconds(rb));
    return SshActions.runbookPreview(rb, values, wire, target, cfg, s)
        .withPayload(SshActions.runbookPayload(entry, values, wire));
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return probe.test(cfg, secrets, ctx);
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return probe.health(cfg, ctx);
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    SshSettings s = SshSettings.of(cfg);
    Command command = ctx.approved() ? approvedCommand(call, cfg, s, ctx) : readCommand(call, cfg, s, ctx);
    String keyId = "gw:" + cfg.name() + ":" + ctx.approvalId().orElse(ctx.callId());
    Duration openWithin = ctx.remaining().minus(STOP_MARGIN).minus(MIN_BUDGET);
    if (openWithin.compareTo(Duration.ofMillis(500)) < 0) {
      throw ConnectorException.timeout("No time left to run " + command.label() + ". Raise Tool timeout in Limits.");
    }
    Semaphore slot = slot(cfg);
    try {
      if (!slot.tryAcquire(SLOT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw ConnectorException.unavailable(cfg.name() + " is busy: " + parallel(cfg) + " calls are running "
            + "(Parallel calls per connection in Policies). Try again in a moment.", null);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled before " + command.label() + " started.");
    }
    // A change gets a session of its own: its certificate names the approval, and nothing else shares it.
    boolean fresh = ctx.approved();
    SshExec.Result r = null;
    try {
      for (int attempt = 0; r == null; attempt++) {
        try (SshSessions.Lease lease = sessions.acquire(cfg, s, secrets, openWithin, fresh, keyId)) {
          // A pooled session signed in for an earlier call: this call resolved no password, yet must mask its echo.
          lease.opened.taint(ctx);
          r = runOn(lease, command, cfg, ctx);
        } catch (SshExec.ChannelRefused e) {
          // The host has as many channels open as it allows (MaxSessions); nothing ran: once more, on a new session.
          if (attempt > 0) {
            throw ConnectorException.unavailable(cfg.name() + " refused another command channel. Lower Parallel "
                + "calls per connection or raise MaxSessions on the host.", e);
          }
          fresh = true;
        }
      }
    } finally {
      slot.release();
    }
    if (r.brokenOff()) {
      sessions.forget(cfg.id());
      String what = "The connection to " + s.target().address() + " broke while " + command.label() + " ran";
      if (ctx.approved() && !command.readOnly()) {
        throw ConnectorException.outcomeUnknown(what + ". It may have finished on the host: check before you "
            + "run it again.", null);
      }
      throw ConnectorException.unavailable(what + ".", null);
    }
    return SshResults.of(cfg, command.runbook(), command.wire(), r, ctx.limits().maxOutputBytes());
  }

  /** What runs, and for how long at most. */
  private record Command(String runbook, String wire, boolean readOnly, Duration limit) {
    String label() {
      return runbook == null ? SshTools.EXEC : runbook;
    }
  }

  private SshExec.Result runOn(SshSessions.Lease lease, Command command, ConnectionConfig cfg, ExecContext ctx)
      throws ConnectorException, SshExec.ChannelRefused {
    try {
      if (!lease.acquireChannel(ctx.remaining().minus(STOP_MARGIN).minus(MIN_BUDGET))) {
        throw ConnectorException.timeout(cfg.name() + " has every channel of its session busy. Try again.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled before " + command.label() + " started.");
    }
    try {
      Duration budget = min(command.limit(), ctx.remaining().minus(STOP_MARGIN));
      if (budget.compareTo(MIN_BUDGET) < 0) {
        throw ConnectorException.timeout("No time left to run " + command.label() + " after signing in. Raise "
            + "Tool timeout in Limits.");
      }
      return SshExec.run(lease.session(), command.wire(), budget, ctx.limits().maxOutputBytes(),
          ctx.limits().maxStderrBytes(), ctx);
    } catch (SshExec.ChannelRefused | ConnectorException e) {
      lease.discard();
      throw e;
    } finally {
      lease.releaseChannel();
    }
  }

  /** Without approval only a read-only runbook runs (defence in depth: classify said READ). */
  private Command readCommand(ToolCall call, ConnectionConfig cfg, SshSettings s, ExecContext ctx)
      throws ConnectorException {
    if (!SshTools.RUN.equals(call.tool())) {
      throw ConnectorException.denied(call.tool() + " runs only after a person approves it.");
    }
    String name = call.string("runbook");
    Runbooks.Entry entry = runbooks.find(cfg.id(), name).filter(e -> !e.ambiguous())
        .orElseThrow(() -> ConnectorException.badInput("No runbook " + name + " on " + cfg.name() + ". "
            + available(cfg)));
    Runbook rb = entry.runbook();
    if (!rb.readOnly()) {
      throw ConnectorException.denied("Runbook " + name + " changes state: it runs only after a person approves it.");
    }
    Map<String, JsonNode> values;
    try {
      values = rb.values(call.arguments().get("params"));
    } catch (Runbook.InvalidParams e) {
      throw ConnectorException.badInput(e.getMessage());
    }
    String wire = rb.render(values, s.advanced().remoteDeadline(), SshActions.remoteSeconds(rb));
    return new Command(rb.name(), wire, true, limit(rb, ctx));
  }

  /**
   * After approval the stored line runs, not the arguments, but only while it is still what the runbook renders to:
   * a runbook edited, renamed or removed since, or a connection whose remote deadline changed, refuses it.
   */
  private Command approvedCommand(ToolCall call, ConnectionConfig cfg, SshSettings s, ExecContext ctx)
      throws ConnectorException {
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied(
        "The approved request has no stored command. Not executed."));
    String wire = payload.path("wire").asString("");
    if (!call.tool().equals(payload.path("tool").asString("")) || wire.isEmpty()) {
      throw ConnectorException.denied("The approved request doesn't match this call. Not executed.");
    }
    if (SshTools.EXEC.equals(call.tool())) {
      if (s.runbooksOnly()) {
        throw ConnectorException.denied(cfg.name() + " runs only runbooks now. Not executed.");
      }
      String problem = commandProblem(wire);
      if (problem != null) {
        throw ConnectorException.denied(problem + " Not executed.");
      }
      return new Command(null, wire, false, commandTimeout(ctx.limits()));
    }
    String name = payload.path("runbook").asString("");
    String changed = "Runbook " + name + " changed after approval. Not executed.";
    Runbooks.Entry entry = runbooks.find(cfg.id(), name).filter(e -> !e.ambiguous())
        .orElseThrow(() -> ConnectorException.denied(changed));
    if (!entry.templateId().equals(payload.path("templateId").asString(""))
        || entry.version() != payload.path("version").asInt(-1)) {
      throw ConnectorException.denied(changed);
    }
    Runbook rb = entry.runbook();
    String rendered;
    try {
      rendered = rb.render(rb.values(payload.path("params")), s.advanced().remoteDeadline(),
          SshActions.remoteSeconds(rb));
    } catch (Runbook.InvalidParams e) {
      throw ConnectorException.denied(changed);
    }
    if (!rendered.equals(wire)) {
      throw ConnectorException.denied(changed);
    }
    return new Command(rb.name(), wire, rb.readOnly(), limit(rb, ctx));
  }

  private static Duration limit(Runbook rb, ExecContext ctx) {
    Duration policy = commandTimeout(ctx.limits());
    return rb.timeoutSeconds() > 0 ? min(policy, Duration.ofSeconds(rb.timeoutSeconds())) : policy;
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    if (object != null && !object.isBlank()) {
      Runbooks.Entry e = runbooks.find(cfg.id(), object).orElseThrow(() -> ConnectorException.badInput(
          "No runbook " + object + " on " + cfg.name() + ". " + available(cfg)));
      return Optional.of(runbookJson(e));
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    try {
      out.put("target", SshSettings.of(cfg).targetLabel());
    } catch (ConnectorException ignored) {
      // describe_connection still lists the runbooks of an incomplete connection.
    }
    JsonNode m = cfg.metadata();
    if (m != null && m.isObject()) {
      // What the last Test connection found; no I/O here.
      for (String key : List.of("serverVersion", "os", "user", "shell", "sudo")) {
        if (m.path(key).isString()) {
          out.put(key, m.get(key).asString());
        }
      }
    }
    ArrayNode list = out.putArray("runbooks");
    for (Runbooks.Entry e : runbooks.of(cfg.id())) {
      list.add(runbookJson(e));
    }
    return Optional.of(out);
  }

  @Override
  public List<ResourceDescriptor> resources(ConnectionConfig cfg) {
    String base = runbooksUri(cfg);
    return List.of(
        new ResourceDescriptor(base, cfg.name() + " runbooks", "application/json",
            "The runbooks of " + cfg.name() + " with their parameters."),
        new ResourceDescriptor(base + "/{runbook}", cfg.name() + " runbook", "application/json",
            "One runbook of " + cfg.name() + ": its command, parameters and whether it needs approval."));
  }

  @Override
  public ToolResult readResource(String uri, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    String base = runbooksUri(cfg);
    if (uri.equals(base)) {
      ArrayNode list = JsonNodeFactory.instance.arrayNode();
      List<String> lines = new ArrayList<>();
      for (Runbooks.Entry e : runbooks.of(cfg.id())) {
        list.add(runbookJson(e));
        lines.add(line(e.runbook()));
      }
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      body.set("runbooks", list);
      return ToolResult.json(body, lines.isEmpty() ? cfg.name() + " has no runbooks yet." : String.join("\n", lines));
    }
    if (uri.startsWith(base + "/")) {
      String name = uri.substring(base.length() + 1);
      Runbooks.Entry e = runbooks.find(cfg.id(), name).orElseThrow(() -> ConnectorException.badInput(
          "No runbook " + name + " on " + cfg.name() + ". " + available(cfg)));
      return ToolResult.json(runbookJson(e), line(e.runbook()) + "\n" + e.runbook().command());
    }
    throw ConnectorException.badInput("No resource " + uri);
  }

  private static String runbooksUri(ConnectionConfig cfg) {
    return "mcpgw://connections/" + cfg.name() + "/runbooks";
  }

  private static ObjectNode runbookJson(Runbooks.Entry e) {
    Runbook rb = e.runbook();
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    o.put("name", rb.name());
    o.put("signature", rb.signature());
    if (rb.description() != null) {
      o.put("description", rb.description());
    }
    o.put("readOnly", rb.readOnly());
    o.put("needsApproval", !rb.readOnly());
    o.put("command", rb.command());
    if (rb.workdir() != null) {
      o.put("workdir", rb.workdir());
    }
    if (rb.timeoutSeconds() > 0) {
      o.put("timeoutSeconds", rb.timeoutSeconds());
    }
    o.set("params", rb.paramsSchema());
    if (e.ambiguous()) {
      o.put("ambiguous", true);
    }
    return o;
  }

  private static String line(Runbook rb) {
    return rb.signature() + " — " + (rb.readOnly() ? "read only" : "needs approval")
        + (rb.description() == null ? "" : ": " + rb.description());
  }

  private String available(ConnectionConfig cfg) {
    List<String> names = new ArrayList<>();
    for (Runbooks.Entry e : runbooks.of(cfg.id())) {
      if (!e.ambiguous()) {
        names.add(e.runbook().signature());
      }
    }
    return names.isEmpty() ? cfg.name() + " has no runbooks yet." : "Runbooks: " + String.join(", ", names) + ".";
  }

  /** The core's bulkhead lets the policy's maximum through; each connection's own limit is {@link #slot}'s. */
  @Override
  public int maxConcurrency() {
    return MAX_PARALLEL;
  }

  private static int defaultParallel(ConnectionConfig cfg) {
    return cfg.env() == Env.PROD ? 2 : 4;
  }

  private int parallel(ConnectionConfig cfg) {
    return parallel.getOrDefault(cfg.id(), defaultParallel(cfg));
  }

  /** The slots of a connection under its current limit; a changed limit starts a new set. */
  private Semaphore slot(ConnectionConfig cfg) {
    int limit = parallel(cfg);
    return slots.computeIfAbsent(cfg.id() + ":" + limit, k -> new Semaphore(limit, true));
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    sessions.forget(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    sessions.forget(connectionId);
    runbooks.forget(connectionId);
    parallel.remove(connectionId);
    slots.keySet().removeIf(k -> k.startsWith(connectionId + ":"));
  }

  @Override
  public void close() {
    sessions.close();
    clients.close();
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
