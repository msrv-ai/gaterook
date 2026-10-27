package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** No I/O: the manifest, the tools of an endpoint, classify, the approver's text and what execute refuses. */
class SshConnectorTest {

  /** A port nothing listens on: any attempt to connect would fail the test loudly. */
  private static final int NO_SSH = 1;

  private static final String LOGS = """
      [{"name":"service","type":"enum","values":["api","worker"],"required":true},
       {"name":"lines","type":"int","min":1,"max":500,"default":100}]""";

  private SshConnector connector;
  private ConnectionConfig dev;

  @BeforeEach
  void setUp() {
    connector = new SshConnector();
    dev = TestConfigs.connection("c1", "dev-vm", Env.DEV, 3, TestConfigs.password(NO_SSH, null));
    TestConfigs.offer(connector, TestCtx.devLimits(), List.of(dev), TestConfigs.contributions("c1",
        TestConfigs.runbook("compose-ps", "docker compose ps", true, null),
        TestConfigs.runbook("compose-logs", "docker compose logs --tail {lines} {service}", true, LOGS),
        TestConfigs.runbook("restart-api", "docker compose restart api", false, null)));
  }

  @AfterEach
  void tearDown() {
    connector.close();
  }

  private static PolicyView policy(Env env, String section) {
    return new PolicyView(env, env.id() + ".ssh", section == null ? null : TestConfigs.json(section),
        TestCtx.devLimits());
  }

  private static ToolCall run(String runbook, String params) {
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("runbook", runbook);
    if (params != null) {
      args.set("params", TestConfigs.json(params));
    }
    return new ToolCall("tc_1", SshTools.RUN, "dev-vm", args, null);
  }

  private static ToolCall exec(String command) {
    return new ToolCall("tc_2", SshTools.EXEC, "dev-vm", JsonNodeFactory.instance.objectNode().put("command", command),
        null);
  }

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("ssh");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("SSH");
    assertThat(m.group()).isEqualTo("Remote access");
    assertThat(m.icon()).isEqualTo("terminal");
    assertThat(m.configSchema().at("/properties/runbooksOnly/x-env-default/prod").asBoolean()).isTrue();
    assertThat(m.policySchema()).isNotNull();
    assertThat(connector.templateKinds()).extracting(TemplateKind::kind).containsExactly("ssh-runbook");
  }

  @Test
  void publishesTheToolsOfTheKit() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name).containsExactly("ssh_run", "ssh_exec");
    assertTool(tools.get(0), "ssh_run", Nature.MIXED, Mode.READ_ONLY, Mode.READ_ONLY);
    assertTool(tools.get(1), "ssh_exec", Nature.MUTATION, Mode.OFF, Mode.OFF);
  }

  private static void assertTool(ToolDefinition t, String name, Nature nature, Mode dev, Mode prod) {
    assertThat(t.name()).isEqualTo(name);
    assertThat(t.nature()).as(name).isEqualTo(nature);
    assertThat(t.defaultMode()).as(name + " dev/stage mode").isEqualTo(dev);
    assertThat(t.prodDefaultMode()).as(name + " prod mode").isEqualTo(prod);
    assertThat(t.outputSchema()).as(name + " outputSchema").isNull();
    assertThat(t.inputSchema().at("/properties/connection/type").asString()).as(name).isEqualTo("string");
    assertThat(t.inputSchema().at("/required").toString()).as(name).contains("\"connection\"");
    assertThat(t.annotations().readOnlyHint()).as(name + " readOnlyHint").isEqualTo(nature == Nature.READ);
  }

  @Test
  void theEndpointsSshRunListsEachConnectionsRunbooksWithTheirParameters() {
    ConnectionConfig other = TestConfigs.connection("c2", "stage-vm", Env.DEV, 1, TestConfigs.password(NO_SSH, null));
    List<EndpointView.Contribution> contributions = new java.util.ArrayList<>(TestConfigs.contributions("c1",
        TestConfigs.runbook("compose-logs", "docker compose logs --tail {lines} {service}", true, LOGS)));
    EndpointView view = new EndpointView("dev-tools", Env.DEV, List.of(dev, other),
        Map.of("c1", TestCtx.devLimits(), "c2", TestCtx.devLimits()), contributions);

    ToolDefinition run = connector.tools(view).get(0);
    JsonNode schema = run.inputSchema();

    assertThat(schema.at("/properties/runbook/enum").toString()).isEqualTo("[\"compose-logs\"]");
    assertThat(schema.at("/allOf").toString())
        .contains("{\"if\":{\"properties\":{\"connection\":{\"const\":\"dev-vm\"}},\"required\":[\"connection\"]},"
            + "\"then\":{\"properties\":{\"runbook\":{\"enum\":[\"compose-logs\"]}}}}")
        .contains("{\"const\":\"stage-vm\"}},\"required\":[\"connection\"]},\"then\":{\"properties\":{\"runbook\":"
            + "{\"not\":{}}}}}")
        .contains("\"service\":{\"enum\":[\"api\",\"worker\"]}")
        .contains("\"required\":[\"params\"]");
    assertThat(run.description()).contains("dev-vm: compose-logs(service: api|worker, lines?: 1–500) — read only.")
        .contains("stage-vm: no runbooks yet.")
        .contains("A run stops after 30 s; output is cut at 256 KB.");
  }

  /** The command timeout is reached (REMAINING §4): the core's deadline of a command tool leaves room for it. */
  @Test
  void theCommandTimeoutIsTheBudgetWithinWhatAClientWaitsAndAfterApproval() {
    Limits dev = TestCtx.limits(Duration.ofSeconds(30), Duration.ofSeconds(10), 1, 1);
    assertThat(SshConnector.commandBudget(dev, false)).isEqualTo(Duration.ofSeconds(30));
    assertThat(SshConnector.commandBudget(dev, true)).isEqualTo(Duration.ofSeconds(30));
    assertThat(SshConnector.commandBudget(TestCtx.limits(Duration.ofSeconds(5), Duration.ofSeconds(60), 1, 1), false))
        .isEqualTo(Duration.ofSeconds(5));
    Limits slow = TestCtx.limits(Duration.ofSeconds(600), Duration.ofSeconds(5), 1, 1);
    assertThat(SshConnector.commandBudget(slow, false)).isEqualTo(Duration.ofMillis(52_500));
    assertThat(SshConnector.commandBudget(slow, true)).isEqualTo(Duration.ofMillis(597_500));
    assertThat(connector.runsCommand(SshTools.RUN)).isTrue();
    assertThat(connector.runsCommand(SshTools.EXEC)).isTrue();
    assertThat(connector.runsCommand("describe_connection")).isFalse();
  }

  @Test
  void aReadOnlyRunbookIsReadAndOneThatChangesStateIsAMutation() {
    Classification ps = connector.classify(run("compose-ps", null), dev, policy(Env.DEV, null));
    Classification logs = connector.classify(run("compose-logs", "{\"service\":\"api\"}"), dev, policy(Env.DEV, null));
    Classification restart = connector.classify(run("restart-api", null), dev, policy(Env.DEV, null));

    assertThat(ps.kind()).isEqualTo(Classification.Kind.READ);
    assertThat(logs.kind()).isEqualTo(Classification.Kind.READ);
    assertThat(restart.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(restart.reason()).isEqualTo("Runbook restart-api can change dev-vm.");
    assertThat(restart.facts().path("templateVersion").asInt()).isEqualTo(1);
  }

  @Test
  void unknownRunbooksAndBadParametersAreDenied() {
    Classification unknown = connector.classify(run("rm-everything", null), dev, policy(Env.DEV, null));
    Classification bad = connector.classify(run("compose-logs", "{\"service\":\"db; id\"}"), dev,
        policy(Env.DEV, null));
    Classification extra = connector.classify(run("compose-ps", "{\"all\":true}"), dev, policy(Env.DEV, null));

    assertThat(unknown.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(unknown.ruleId()).isEqualTo("dev.ssh.runbook-unknown");
    assertThat(unknown.hint()).isEqualTo("Runbooks: compose-logs(service: api|worker, lines?: 1–500), compose-ps(), "
        + "restart-api().");
    assertThat(bad.ruleId()).isEqualTo("dev.ssh.param-invalid");
    assertThat(bad.reason()).isEqualTo("service must be one of api, worker");
    assertThat(extra.ruleId()).isEqualTo("dev.ssh.param-invalid");
  }

  @Test
  void twoRunbooksWithOneNameRunNeither() {
    TestConfigs.offer(connector, TestCtx.devLimits(), List.of(dev), TestConfigs.contributions("c1",
        TestConfigs.runbook("compose-ps", "docker compose ps", true, null),
        TestConfigs.runbook("compose-ps", "docker compose ps -a", true, null)));

    Classification c = connector.classify(run("compose-ps", null), dev, policy(Env.DEV, null));

    assertThat(c.ruleId()).isEqualTo("dev.ssh.runbook-ambiguous");
  }

  @Test
  void sshExecNeedsThePolicyAndIsAlwaysAMutation() {
    Classification off = connector.classify(exec("uptime"), dev, policy(Env.DEV, null));
    Classification on = connector.classify(exec("uptime"), dev, policy(Env.DEV, "{\"allowExec\":true}"));
    Classification nul = connector.classify(exec("id\u0000rm"), dev, policy(Env.DEV, "{\"allowExec\":true}"));
    Classification escape = connector.classify(exec("echo \u001b[2J"), dev, policy(Env.DEV, "{\"allowExec\":true}"));

    assertThat(off.ruleId()).isEqualTo("dev.ssh.exec-not-allowed");
    assertThat(on.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(on.facts().path("freeText").asBoolean()).isTrue();
    assertThat(nul.ruleId()).isEqualTo("dev.ssh.command-invalid");
    assertThat(escape.ruleId()).isEqualTo("dev.ssh.command-invalid");
  }

  @Test
  void prodConnectionsRunOnlyRunbooksUnlessTheSwitchIsOff() {
    ConnectionConfig prod = TestConfigs.connection("c9", "prod-vm", Env.PROD, 1, TestConfigs.password(NO_SSH, null));
    ConnectionConfig prodExec = TestConfigs.connection("c9", "prod-vm", Env.PROD, 2,
        TestConfigs.with(TestConfigs.password(NO_SSH, null), c -> c.put("runbooksOnly", false)));
    PolicyView allow = policy(Env.PROD, "{\"allowExec\":true}");

    assertThat(connector.classify(exec("uptime"), prod, allow).ruleId()).isEqualTo("prod.ssh.runbooks-only");
    assertThat(connector.classify(exec("uptime"), prodExec, allow).kind()).isEqualTo(Classification.Kind.MUTATION);
  }

  @Test
  void theSignInMethodMustBeAllowedForTheHostAndTheBastion() {
    PolicyView keysOnly = policy(Env.PROD, "{\"allowedCredentialMethods\":[\"vaultSshCa\",\"privateKey\"]}");
    ConnectionConfig viaBastion = TestConfigs.connection("c1", "dev-vm", Env.DEV, 4,
        TestConfigs.with(TestConfigs.password(NO_SSH, null), c -> {
          c.putObject("credentials").put("method", "privateKey");
          c.putObject("jump").put("host", "bastion.internal").putObject("credentials").put("method", "password");
        }));

    Classification host = connector.classify(run("compose-ps", null), dev, keysOnly);
    Classification bastion = connector.classify(run("compose-ps", null), viaBastion, keysOnly);

    assertThat(host.ruleId()).isEqualTo("prod.ssh.credential-method");
    assertThat(host.reason()).isEqualTo("Sign-in with a password isn't allowed on dev.");
    assertThat(host.hint()).isEqualTo("Switch dev-vm to Vault SSH CA certificate or private key in Setup.");
    assertThat(bastion.ruleId()).isEqualTo("prod.ssh.credential-method");
  }

  @Test
  void anInvalidConfigIsDenied() {
    ConnectionConfig broken = TestConfigs.connection("c1", "dev-vm", Env.DEV, 5,
        TestConfigs.with(TestConfigs.password(NO_SSH, null), c -> ((ObjectNode) c.get("connection"))
            .put("host", "evil host")));

    assertThat(connector.classify(run("compose-ps", null), broken, policy(Env.DEV, null)).ruleId())
        .isEqualTo("dev.ssh.config-invalid");
  }

  @Test
  void theApproverReadsTheExactLineAndThePayloadCarriesIt() {
    ActionPreview restart = connector.describeAction(run("restart-api", null), dev);
    ActionPreview logs = connector.describeAction(run("compose-logs", "{\"service\":\"worker\",\"lines\":20}"), dev);
    ActionPreview cmd = connector.describeAction(exec("systemctl restart nginx | sh"), dev);

    assertThat(restart.kind()).isEqualTo(ActionPreview.Kind.RUNBOOK);
    assertThat(restart.text()).isEqualTo("docker compose restart api");
    assertThat(restart.target()).isEqualTo("dev-vm · deploy@127.0.0.1:1");
    assertThat(restart.payload().path("wire").asString()).isEqualTo(restart.text());
    assertThat(restart.payload().path("templateId").asString()).startsWith("tpl_restart-api_");
    assertThat(logs.text()).isEqualTo("docker compose logs --tail '20' 'worker'");
    assertThat(logs.fields()).contains(new ActionPreview.Field("Parameters", "service=worker, lines=20"),
        new ActionPreview.Field("Runs as", "deploy · password"));
    assertThat(cmd.kind()).isEqualTo(ActionPreview.Kind.COMMAND);
    assertThat(cmd.text()).isEqualTo("systemctl restart nginx | sh");
    assertThat(cmd.warnings()).contains("Pipes into a shell");
    assertThat(cmd.payload().toString()).isEqualTo("{\"tool\":\"ssh_exec\",\"wire\":\"systemctl restart nginx | sh\"}");
  }

  @Test
  void theRemoteDeadlineIsPartOfWhatTheApproverReads() {
    ConnectionConfig remote = TestConfigs.connection("c1", "dev-vm", Env.DEV, 6,
        TestConfigs.with(TestConfigs.password(NO_SSH, null), c -> c.putObject("advanced").put("remoteDeadline", true)));

    assertThat(connector.describeAction(run("restart-api", null), remote).text())
        .isEqualTo("timeout -k 5 300 sh -c 'docker compose restart api'");
    assertThat(connector.describeAction(exec("echo 'hi'"), remote).text())
        .isEqualTo("timeout -k 5 300 sh -c 'echo '\"'\"'hi'\"'\"''");
  }

  @Test
  void withoutApprovalOnlyReadOnlyRunbooksExecute() {
    // Defence in depth (Connector SPI): even if the core called execute for a mutation, nothing connects.
    assertThatThrownBy(() -> connector.execute(run("restart-api", null), dev, new TestSecrets(), TestCtx.read()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED))
        .hasMessage("Runbook restart-api changes state: it runs only after a person approves it.");
    assertThatThrownBy(() -> connector.execute(exec("uptime"), dev, new TestSecrets(), TestCtx.read()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void anApprovedRunbookThatChangedSinceDoesntRun() {
    ActionPreview approved = connector.describeAction(run("restart-api", null), dev);
    TestConfigs.offer(connector, TestCtx.devLimits(), List.of(dev), TestConfigs.contributions("c1",
        TestConfigs.runbook("restart-api", "docker compose restart api worker", false, null)));
    ObjectNode forged = (ObjectNode) approved.payload().deepCopy();
    forged.put("wire", "docker compose down");

    assertThatThrownBy(() -> connector.execute(run("restart-api", null), dev, new TestSecrets(),
        TestCtx.approved(TestCtx.devLimits(), "ap_1", approved.payload())))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED))
        .hasMessage("Runbook restart-api changed after approval. Not executed.");
    assertThatThrownBy(() -> connector.execute(run("restart-api", null), dev, new TestSecrets(),
        TestCtx.approved(TestCtx.devLimits(), "ap_1", forged)))
        .hasMessage("Runbook restart-api changed after approval. Not executed.");
    assertThatThrownBy(() -> connector.execute(exec("uptime"), dev, new TestSecrets(),
        TestCtx.approved(TestCtx.devLimits(), "ap_1", approved.payload())))
        .hasMessage("The approved request doesn't match this call. Not executed.");
  }

  @Test
  void resourcesAndDescribeListTheRunbooksWithoutIo() throws Exception {
    List<ResourceDescriptor> resources = connector.resources(dev);
    ToolResult list = connector.readResource("mcpgw://connections/dev-vm/runbooks", dev, new TestSecrets(),
        TestCtx.read());
    ToolResult one = connector.readResource("mcpgw://connections/dev-vm/runbooks/compose-logs", dev,
        new TestSecrets(), TestCtx.read());
    JsonNode described = connector.describe(dev, new TestSecrets(), TestCtx.read(), null).orElseThrow();

    assertThat(resources).extracting(ResourceDescriptor::uri).containsExactly("mcpgw://connections/dev-vm/runbooks",
        "mcpgw://connections/dev-vm/runbooks/{runbook}");
    assertThat(list.structuredContent().path("runbooks")).hasSize(3);
    assertThat(list.content().get(0).text()).contains("restart-api() — needs approval");
    assertThat(one.structuredContent().path("signature").asString())
        .isEqualTo("compose-logs(service: api|worker, lines?: 1–500)");
    assertThat(described.path("target").asString()).isEqualTo("deploy@127.0.0.1:1");
    assertThat(described.path("runbooks")).hasSize(3);
    assertThatThrownBy(() -> connector.readResource("mcpgw://connections/dev-vm/runbooks/nope", dev,
        new TestSecrets(), TestCtx.read())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void theRunbookKindCompilesAndPreviewsWithoutIo() {
    TemplateKind kind = connector.templateKinds().get(0);
    ObjectNode def = TestConfigs.runbook("compose-logs", "docker compose logs --tail {lines} {service}", true, LOGS);

    TemplateKind.CompileResult compiled = kind.compile(def, dev);
    ActionPreview preview = kind.preview(def, (ObjectNode) TestConfigs.json("{\"service\":\"api\"}"), dev);
    ActionPreview viaParams = kind.preview(def,
        (ObjectNode) TestConfigs.json("{\"runbook\":\"compose-logs\",\"params\":{\"service\":\"worker\"}}"), dev);

    assertThat(compiled.errors()).isEmpty();
    assertThat(compiled.tools()).isEmpty();
    assertThat(compiled.contributions()).hasSize(1);
    assertThat(compiled.contributions().get(0).path("readOnly").asBoolean()).isTrue();
    assertThat(preview.text()).isEqualTo("docker compose logs --tail '100' 'api'");
    assertThat(viaParams.text()).isEqualTo("docker compose logs --tail '100' 'worker'");
    assertThat(kind.definitionSchema().path("type").asString()).isEqualTo("object");
  }
}
