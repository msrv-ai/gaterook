package ai.msrv.gaterook.connectors.azure;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.AzureCloud.Audience;
import ai.msrv.gaterook.connectors.cloudauth.AzureCredentials;
import ai.msrv.gaterook.connectors.cloudauth.AzureSpec;
import ai.msrv.gaterook.connectors.cloudauth.AzureTrust;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection of Azure: each step timed and shown; the first failure stops the test with what happened and what
 * to do.
 *
 * <ol>
 *   <li>the person's decisions first, before anything runs or leaves the computer: the Azure CLI command to run
 *       ({@code azure-cli}: Trust writes its fingerprint to {@code trustedCli}), or the code of a device sign-in
 *       ({@code azure-sign-in}: the person signs in in a browser, then tests again);</li>
 *   <li>the token for Resource Manager: who signed in, and in which tenant; a configured directory ID must match;</li>
 *   <li>the subscriptions the token can see: each configured one must be there, enabled, in the token's tenant;</li>
 *   <li>Resource Graph over those subscriptions;</li>
 *   <li>what the identity may do on each subscription ({@code Microsoft.Authorization/permissions}): writes or
 *       deletes are a warning, Reader is enough;</li>
 *   <li>Azure Monitor alerts, and a Log Analytics token when the connection names workspaces.</li>
 * </ol>
 * The tenant the token belongs to is recorded ({@code metadata.tenant}): calls refuse a token of another tenant.
 */
final class ConnectionTest {

  static final String SUBSCRIPTIONS_API = "2022-12-01";
  static final String PERMISSIONS_API = "2022-04-01";

  private final Call call;
  private final CloudCredentials cloud;
  private final SecretResolver secrets;
  private final List<TestResult.Check> checks = new ArrayList<>();
  private final List<TestResult.Hint> hints = new ArrayList<>();

  ConnectionTest(Call call, CloudCredentials cloud, SecretResolver secrets) {
    this.call = call;
    this.cloud = cloud;
    this.secrets = secrets;
  }

  @FunctionalInterface
  private interface Step<T> {
    T run() throws ConnectorException;
  }

  private <T> T step(String name, Step<T> step, java.util.function.Function<T, String> detail)
      throws ConnectorException {
    long started = System.nanoTime();
    try {
      T value = step.run();
      checks.add(new TestResult.Check(name, true, ms(started), detail.apply(value)));
      return value;
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check(name, false, ms(started), e.getMessage()));
      throw e;
    }
  }

  TestResult run() {
    Settings s = call.settings();
    AzureSpec spec = s.credentials();
    try {
      TestResult decision = decisions(spec);
      if (decision != null) {
        return decision;
      }
    } catch (ConnectorException e) {
      return failure(e);
    }
    long dataMs = 0;
    AzureCredentials.AccessToken token;
    Map<String, String> names = new LinkedHashMap<>();
    TestResult.Level level = TestResult.Level.UNKNOWN;
    List<String> writes = new ArrayList<>();
    try {
      token = step("sign-in", () -> call.arm().token(), t -> t.account() + (t.tenantId() == null ? ""
          : " in tenant " + t.tenantId()));
      if (spec.tenantIsGuid() && token.tenantId() != null && !spec.tenantId().equals(token.tenantId())) {
        String text = "The sign-in belongs to tenant " + token.tenantId() + ", not " + spec.tenantId() + ". Sign in "
            + "to the connection's tenant" + (spec.source() == AzureSpec.Source.CLI ? " (az login --tenant "
            + spec.tenantId() + ")" : "") + " or fix the tenant ID.";
        checks.add(new TestResult.Check("tenant", false, 0, text));
        return TestResult.failure("azure.wrong-tenant", text, null).withChecks(checks);
      }
      if (token.tenantId() == null) {
        String text = "The token doesn't say its tenant, so GateRook can't pin it. Use a sign-in of Entra ID.";
        checks.add(new TestResult.Check("tenant", false, 0, text));
        return TestResult.failure("azure.no-tenant", text, null).withChecks(checks);
      }
      String tenant = token.tenantId();
      long started = System.nanoTime();
      JsonNode visible = step("subscriptions", () -> {
        call.quota(s.subscriptions().getFirst(), "Resource Manager", Call.ARM_PER_MINUTE);
        return call.arm().get(call.arm().url("/subscriptions", Arm.query(SUBSCRIPTIONS_API)),
            "Azure Resource Manager");
      }, v -> v.path("value").size() + " visible");
      dataMs = Math.max(1, ms(started));
      Map<String, JsonNode> byId = new LinkedHashMap<>();
      for (JsonNode sub : visible.path("value")) {
        byId.put(sub.path("subscriptionId").asString("").toLowerCase(Locale.ROOT), sub);
      }
      for (String id : s.subscriptions()) {
        JsonNode sub = byId.get(id);
        String problem = null;
        if (sub == null) {
          problem = "Subscription " + id + " isn't visible to " + token.account() + ". Assign the Reader role on it, "
              + "or check the subscription ID.";
        } else if (!tenant.equalsIgnoreCase(sub.path("tenantId").asString(tenant))) {
          problem = "Subscription " + id + " belongs to tenant " + sub.path("tenantId").asString() + ", not the "
              + "sign-in's " + tenant + ".";
        } else if (!"Enabled".equalsIgnoreCase(sub.path("state").asString("Enabled"))) {
          problem = "Subscription " + id + " is " + sub.path("state").asString() + ".";
        }
        if (problem != null) {
          checks.add(new TestResult.Check("subscription " + id, false, 0, problem));
          return TestResult.failure("azure.subscription", problem, null).withChecks(checks);
        }
        names.put(id, sub.path("displayName").asString(""));
      }
      step("Resource Graph", () -> {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        ArrayNode subs = body.putArray("subscriptions");
        s.subscriptions().forEach(subs::add);
        body.put("query", "resources | summarize count()");
        body.putObject("options").put("resultFormat", "objectArray");
        call.quota(s.subscriptions().getFirst(), "Resource Graph", Call.GRAPH_PER_MINUTE);
        return call.arm().post(call.arm().url("/providers/Microsoft.ResourceGraph/resources",
            Arm.query(ResourceGraph.API_VERSION)), body, "Azure Resource Graph");
      }, r -> {
        JsonNode row = r.path("data").path(0);
        JsonNode count = row.isObject() && row.size() > 0 ? row.properties().iterator().next().getValue() : null;
        return count == null ? "Resource Graph answered" : count.asLong() + " resources";
      });
      level = TestResult.Level.READ;
      for (String id : s.subscriptions()) {
        List<String> actions = optional("permissions on " + id, () -> permissions(id));
        if (actions == null) {
          level = TestResult.Level.UNKNOWN;
          continue;
        }
        for (String a : actions) {
          String lower = a.toLowerCase(Locale.ROOT);
          if (lower.equals("*") || lower.endsWith("/write") || lower.endsWith("/delete") || lower.endsWith("/action")
              || lower.endsWith("/*")) {
            writes.add(a);
          }
        }
      }
      if (!writes.isEmpty()) {
        level = TestResult.Level.WRITE;
        hints.add(new TestResult.Hint(call.cfg().env() == Env.PROD ? TestResult.Severity.WARNING
            : TestResult.Severity.INFO, "azure.can-write", "/credentials",
            "This identity can change resources (" + String.join(", ", writes.stream().distinct().limit(5).toList())
                + (writes.size() > 5 ? ", …" : "") + "). Reader is enough: GateRook's changes wait for approval "
                + "anyway."));
      }
      optional("alerts", () -> {
        call.quota(s.subscriptions().getFirst(), "Azure Monitor", Call.MONITOR_PER_MINUTE);
        java.util.Map<String, String> q = Arm.query(Monitor.ALERTS_API);
        q.put("pageCount", "1");
        return call.arm().get(call.arm().url("/subscriptions/" + s.subscriptions().getFirst()
            + "/providers/Microsoft.AlertsManagement/alerts", q), "Azure Monitor alerts").path("value").size();
      });
      if (s.hasLogs()) {
        step("Log Analytics sign-in", () -> {
          AzureCredentials.AccessToken t = cloud.azure().token(owner(), spec, Audience.LOG_ANALYTICS, secrets,
              call.ctx().remaining());
          call.ctx().taint(t.value());
          return t;
        }, t -> "token for " + spec.cloud().resource(Audience.LOG_ANALYTICS));
      }
    } catch (ConnectorException e) {
      return failure(e);
    }
    if (spec.source() == AzureSpec.Source.CLIENT_SECRET && call.cfg().env() == Env.PROD) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "azure.client-secret", "/credentials/source",
          "Use a certificate on prod: a client secret is a password that lasts up to two years."));
    }
    if (spec.source() == AzureSpec.Source.DEVICE_CODE) {
      hints.add(new TestResult.Hint(TestResult.Severity.INFO, "azure.device-sign-in", "/credentials/source",
          "Device sign-ins last until GateRook restarts; then test the connection again to sign in."));
    }
    if (spec.source() == AzureSpec.Source.CLI) {
      hints.add(new TestResult.Hint(TestResult.Severity.INFO, "azure.cli", "/credentials/source",
          "Agents read with your Azure CLI sign-in: what you may read in Azure, they may read through this "
              + "connection. Changes still wait for approval."));
    }
    TestResult.Permissions permissions = new TestResult.Permissions(level,
        "Reads " + String.join(", ", s.subscriptions()) + " through Resource Manager, Resource Graph and Azure Monitor",
        level == TestResult.Level.WRITE ? "Can change resources; GateRook's changes wait for approval"
            : level == TestResult.Level.READ ? "None found" : "Unknown",
        writes.stream().distinct().limit(20).toList(), null);
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    details.put("tenant", token.tenantId()).put("account", token.account()).put("application", token.application());
    ObjectNode subs = details.putObject("subscriptions");
    names.forEach(subs::put);
    ObjectNode metadata = JsonNodeFactory.instance.objectNode();
    metadata.put("serverVersion", "Azure · " + spec.cloud().id() + " · " + (names.size() == 1
        ? names.values().iterator().next() : names.size() + " subscriptions"));
    metadata.put("tenant", token.tenantId());
    TestResult result = TestResult.success(dataMs, "Azure Resource Manager · " + spec.cloud().id(), token.account(),
        permissions)
        .withChecks(checks)
        .withMetadata(metadata)
        .withDetails(details);
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /**
   * What the person decides before anything runs: the CLI command to allow, or the device sign-in to finish.
   * {@code null} when there is nothing to decide.
   */
  private TestResult decisions(AzureSpec spec) throws ConnectorException {
    if (spec.source() != AzureSpec.Source.CLI && spec.source() != AzureSpec.Source.DEVICE_CODE) {
      return null;
    }
    TestResult.Confirmation c = step(spec.source() == AzureSpec.Source.CLI ? "Azure CLI" : "device sign-in",
        () -> AzureTrust.decision(cloud.azure(), spec, call.cfg().name(), call.ctx().remaining()),
        d -> d == null ? (spec.source() == AzureSpec.Source.CLI ? "allowed" : "signed in")
            : d.kind().equals("azure-cli") ? "waiting for your decision" : "waiting for the code");
    return c == null ? null : TestResult.confirm(c, checks);
  }

  /** The actions the identity may take on the subscription (its roles, with notActions taken out). */
  private List<String> permissions(String subscription) throws ConnectorException {
    call.quota(subscription, "Resource Manager", Call.ARM_PER_MINUTE);
    JsonNode answer = call.arm().get(call.arm().url("/subscriptions/" + subscription
        + "/providers/Microsoft.Authorization/permissions", Arm.query(PERMISSIONS_API)), "Azure Resource Manager");
    List<String> actions = new ArrayList<>();
    for (JsonNode p : answer.path("value")) {
      List<String> not = new ArrayList<>();
      p.path("notActions").forEach(n -> not.add(n.asString("").toLowerCase(Locale.ROOT)));
      for (JsonNode a : p.path("actions")) {
        String action = a.asString("");
        if (!not.contains(action.toLowerCase(Locale.ROOT))) {
          actions.add(action);
        }
      }
    }
    return actions;
  }

  /** A step whose failure is a fact, not a failed test; {@code null} then. */
  private <T> T optional(String name, Step<T> step) throws ConnectorException {
    long started = System.nanoTime();
    try {
      T value = step.run();
      checks.add(new TestResult.Check(name, true, ms(started), "allowed"));
      return value;
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE || e.kind() == ConnectorException.Kind.TIMEOUT) {
        checks.add(new TestResult.Check(name, false, ms(started), e.getMessage()));
        throw e;
      }
      checks.add(new TestResult.Check(name, true, ms(started), "not allowed: " + e.getMessage()));
      return null;
    }
  }

  /** The token and one cheap read: what calls need, without the details Test connection collects. */
  HealthResult health() {
    long started = System.nanoTime();
    try {
      String first = call.settings().subscriptions().getFirst();
      call.arm().get(call.arm().url("/subscriptions/" + first, Arm.query(SUBSCRIPTIONS_API)),
          "Azure Resource Manager");
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  private TestResult failure(ConnectorException e) {
    String code = switch (e.kind()) {
      case TIMEOUT -> "azure.timeout";
      case RESOURCE_UNAVAILABLE -> "azure.unreachable";
      case BAD_INPUT -> "azure.invalid";
      case DENIED -> "azure.denied";
      default -> "azure.refused";
    };
    Throwable cause = e.getCause();
    return TestResult.failure(code, e.getMessage(), cause == null ? null : cause.getClass().getSimpleName() + ": "
        + cause.getMessage()).withChecks(checks);
  }

  private CloudCredentials.Owner owner() {
    return new CloudCredentials.Owner(call.cfg().id(), call.cfg().version(), call.cfg().name());
  }

  private static long ms(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}
