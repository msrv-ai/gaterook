package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection of GCP (report 09 §5): each step timed and shown, the first failure stops the test with what
 * happened and what to do.
 *
 * <ol>
 *   <li>a token — Google sign-in on this computer or the key, impersonating when the connection says so;</li>
 *   <li>each scope through Cloud Resource Manager (id, number, name, state); a least-privilege account may not read
 *       it, which is a hint, not a failure;</li>
 *   <li>one inventory search (Cloud Asset), the latency shown as "Healthy · 96 ms";</li>
 *   <li>one metric descriptor (Cloud Monitoring); a refusal is a warning: {@code gcp_metrics} won't work;</li>
 *   <li>{@code testIamPermissions} on the scopes: read permissions and probes for write — an account that can change
 *       resources or read secret values gets a hint, a warning on prod.</li>
 * </ol>
 */
final class ConnectionTest {

  /** What the gateway reads with (report 09 §5 step 5). */
  static final List<String> READ = List.of("cloudasset.assets.searchAllResources", "monitoring.timeSeries.list",
      "resourcemanager.projects.get", "run.services.get", "compute.instances.get", "cloudsql.instances.get",
      "container.clusters.get");
  /** Probes for write: none of them should be granted. */
  static final List<String> WRITE = List.of("compute.instances.stop", "compute.instances.delete",
      "run.services.update", "cloudsql.instances.update", "container.clusters.update", "pubsub.topics.delete",
      "storage.buckets.update", "resourcemanager.projects.setIamPolicy", "iam.serviceAccounts.actAs",
      "iam.serviceAccountKeys.create", "secretmanager.versions.access");
  private static final int MAX_SCOPES = 3;

  private final Call call;
  private final List<TestResult.Check> checks = new ArrayList<>();
  private final List<TestResult.Hint> hints = new ArrayList<>();

  ConnectionTest(Call call) {
    this.call = call;
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
    GcpSpec spec = s.credentials();
    ObjectNode projects = JsonNodeFactory.instance.objectNode();
    ArrayNode scopes = JsonNodeFactory.instance.arrayNode();
    long dataMs;
    TestResult.Level level = TestResult.Level.UNKNOWN;
    List<String> canWrite = new ArrayList<>();
    try {
      step("token", () -> call.api().headers(call.base("gcp.cloudasset", GoogleApi.ASSET)), h -> spec.impersonate()
          != null ? "Impersonates " + spec.impersonate() + " for " + spec.lifetimeSeconds() / 60 + " min"
          : "Signed in as " + spec.identity());
      for (String scope : s.scopes().subList(0, Math.min(MAX_SCOPES, s.scopes().size()))) {
        JsonNode info = optional("scope " + scope, () -> call.api().get(crm(scope), "Cloud Resource Manager"),
            ConnectionTest::scopeText, scope);
        if (info != null) {
          ObjectNode row = scopes.addObject().put("scope", scope);
          String number = info.path("name").asString("");
          if (info.path("projectId").isString()) {
            row.put("projectId", info.path("projectId").asString());
            if (number.startsWith("projects/")) {
              projects.put(info.path("projectId").asString(), number.substring("projects/".length()));
            }
          }
          row.put("name", number);
          row.put("displayName", info.path("displayName").asString(""));
          row.put("state", info.path("state").asString(""));
          if (!info.path("state").asString("ACTIVE").equals("ACTIVE")) {
            hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "gcp.scope-inactive", "/scopes",
                scope + " is " + info.path("state").asString() + ": its resources may be gone."));
          }
        }
      }
      long started = System.nanoTime();
      step("inventory", () -> {
        call.assetQuota();
        return call.api().get(URI.create(call.base("gcp.cloudasset", GoogleApi.ASSET).toString().replaceAll("/$", "")
            + "/v1/" + s.firstScope() + ":searchAllResources?pageSize=1&readMask=name"), "Cloud Asset");
      }, r -> "Cloud Asset API v1" + (r.path("results").isEmpty() ? " · no resources in " + s.firstScope()
          : " · reads " + s.firstScope()));
      dataMs = ms(started);
      String metricsProject = s.firstMetricsProject();
      if (metricsProject != null) {
        try {
          step("metrics", () -> {
            call.monitoringQuota(metricsProject);
            return call.api().get(URI.create(call.base("gcp.monitoring", GoogleApi.MONITORING).toString()
                .replaceAll("/$", "") + "/v3/projects/" + metricsProject + "/metricDescriptors?pageSize=1"),
                "Cloud Monitoring");
          }, r -> "Cloud Monitoring API v3 · " + metricsProject);
        } catch (GoogleApi.GoogleException e) {
          if (e.status != 403) {
            throw e;
          }
          hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "gcp.metrics-denied", "/metricsProjects",
              "gcp_metrics won't work: grant roles/monitoring.viewer on " + metricsProject + " to "
                  + spec.identity() + "."));
        }
      } else {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "gcp.no-metrics-project", "/metricsProjects",
            "No project to read metrics from: add one under Metrics projects for gcp_metrics."));
      }
      boolean tested = false;
      List<String> missingLive = new ArrayList<>();
      for (String scope : s.scopes().subList(0, Math.min(MAX_SCOPES, s.scopes().size()))) {
        List<String> granted = optional("permissions on " + scope, () -> call.api().testIamPermissions(
            URI.create(crm(scope) + ":testIamPermissions"), concat(READ, WRITE), "Cloud Resource Manager"),
            g -> g.size() + " of " + (READ.size() + WRITE.size()) + " permissions checked", null);
        if (granted == null) {
          continue;
        }
        tested = true;
        for (String w : WRITE) {
          if (granted.contains(w) && !canWrite.contains(w)) {
            canWrite.add(w);
          }
        }
        for (String r : List.of("run.services.get", "compute.instances.get", "cloudsql.instances.get",
            "container.clusters.get")) {
          if (!granted.contains(r) && !missingLive.contains(r)) {
            missingLive.add(r);
          }
        }
      }
      if (tested) {
        level = canWrite.stream().anyMatch(p -> !p.equals("secretmanager.versions.access")) ? TestResult.Level.WRITE
            : TestResult.Level.READ;
        boolean prod = call.cfg().env() == Env.PROD;
        List<String> changes = canWrite.stream().filter(p -> !p.equals("secretmanager.versions.access")).toList();
        if (!changes.isEmpty()) {
          hints.add(new TestResult.Hint(prod ? TestResult.Severity.WARNING : TestResult.Severity.INFO,
              "gcp.can-write", "/credentials", "This account can change resources (" + String.join(", ", changes)
                  + "). Use roles/cloudasset.viewer + roles/monitoring.viewer and per-service viewer roles for prod."));
        }
        if (canWrite.contains("secretmanager.versions.access")) {
          hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "gcp.reads-secrets", "/credentials",
              "This account can read secret values (secretmanager.versions.access). The gateway never asks for them,"
                  + " but a leaked token could."));
        }
        if (!missingLive.isEmpty() && s.liveGet()) {
          hints.add(new TestResult.Hint(TestResult.Severity.INFO, "gcp.live-partial", "/liveGet",
              "Without " + String.join(", ", missingLive) + " gcp_describe shows the inventory's snapshot of those "
                  + "resources instead of their current state; per-service viewer roles add them."));
        }
      }
    } catch (ConnectorException e) {
      String code = switch (e.kind()) {
        case TIMEOUT -> "gcp.timeout";
        case RESOURCE_UNAVAILABLE -> "gcp.unreachable";
        case BAD_INPUT -> "gcp.invalid";
        default -> "gcp.refused";
      };
      Throwable cause = e.getCause();
      return TestResult.failure(code, e.getMessage(), cause == null ? null : cause.getClass().getSimpleName() + ": "
          + cause.getMessage()).withChecks(checks);
    }
    if (call.cfg().env() == Env.PROD && spec.impersonate() == null) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "gcp.long-lived-credentials",
          "/credentials/impersonate", "Use impersonation on prod: a stored key or a personal sign-in doesn't expire, "
              + "a borrowed token lasts " + spec.lifetimeSeconds() / 60 + " min."));
    }
    String reads = "Reads " + String.join(", ", s.scopes());
    TestResult result = TestResult.success(dataMs, "Cloud Asset API v1 · Cloud Monitoring API v3", spec.identity(),
        new TestResult.Permissions(level, reads, level == TestResult.Level.WRITE ? "Can change resources"
            : level == TestResult.Level.READ ? "None" : "Unknown", List.copyOf(canWrite), null))
        .withChecks(checks)
        .withMetadata(JsonNodeFactory.instance.objectNode().put("serverVersion", "GCP · " + s.target())
            .set("projects", projects))
        .withDetails(JsonNodeFactory.instance.objectNode().set("scopes", scopes));
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /** A token and one inventory read: what calls need, without the details Test connection collects. */
  HealthResult health() {
    long started = System.nanoTime();
    try {
      call.assetQuota();
      call.api().get(URI.create(call.base("gcp.cloudasset", GoogleApi.ASSET).toString().replaceAll("/$", "")
          + "/v1/" + call.settings().firstScope() + ":searchAllResources?pageSize=1&readMask=name"), "Cloud Asset");
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  /** A step that may be refused (403) without failing the test; anything else stops it. */
  private <T> T optional(String name, Step<T> step, java.util.function.Function<T, String> detail, String scope)
      throws ConnectorException {
    try {
      return step(name, step, detail);
    } catch (GoogleApi.GoogleException e) {
      if (e.status == 403 || e.status == 404) {
        if (scope != null) {
          hints.add(new TestResult.Hint(TestResult.Severity.INFO, "gcp.scope-unreadable", "/scopes",
              "Can't read the details of " + scope + " (resourcemanager." + scope.substring(0, scope.indexOf('/'))
                  + ".get): that is fine for a least-privilege account; roles/browser shows its name and number."));
        }
        return null;
      }
      throw e;
    }
  }

  private URI crm(String scope) {
    return URI.create(call.base("gcp.cloudresourcemanager", GoogleApi.RESOURCE_MANAGER).toString()
        .replaceAll("/$", "") + "/v3/" + scope);
  }

  private static String scopeText(JsonNode info) {
    String id = info.path("projectId").asString(info.path("displayName").asString(""));
    String number = info.path("name").asString("");
    return id + " (" + number.substring(number.indexOf('/') + 1) + ") " + info.path("state").asString("");
  }

  private static List<String> concat(List<String> a, List<String> b) {
    List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static long ms(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}
