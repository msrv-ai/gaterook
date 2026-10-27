package ai.msrv.gaterook.core.admin.policies;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.policy.PolicySchemas;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.core.templates.TemplateService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Policies (ARCHITECTURE.md §6 policies, kit {@code policies.html}): per environment the effective sections, what is
 * set explicitly and what is inherited (stage ← dev), the locks no setting can open ("Mutations always require
 * approval", read-only SQL transactions), the runbooks of the environment's SSH connections and the busiest token and
 * tool of the last minute. Changes go through {@link PolicyService}, which validates them for the environment and
 * every one that inherits from it and records a Setup change per key.
 */
@RestController
@RegisterReflectionForBinding({PoliciesController.Policies.class, PoliciesController.EnvView.class,
    PoliciesController.Lock.class, PoliciesController.Runbook.class, PoliciesController.Usage.class,
    PoliciesController.Busiest.class})
class PoliciesController {

  /** {@code PUT /api/policies/{env}}: only the keys given change; {@code null} unsets one. */
  record Update(JsonNode sections, Long version, String reason, String confirm) {}

  record Reason(String reason, String confirm) {}

  /**
   * A setting nobody can change ({@code const}: "Always on"), or values an environment refuses
   * ({@code x-forbidden-env-values}), or one that changes on prod only with {@code confirm: "danger"}.
   *
   * @param kind {@code fixed}, {@code forbidden-values} or {@code confirm-danger}
   */
  record Lock(String section, String key, String title, String kind, JsonNode values, String text) {}

  /** A runbook of an SSH connection on this environment; {@code status}: {@code active}, {@code disabled}, … */
  record Runbook(String connection, String name, String template, int version, String status, Instant lastRunAt) {}

  /**
   * One environment.
   *
   * @param verified whether its row verified; {@code serving} whether calls on it are decided at all (its row and
   *                 every row it inherits from verified)
   */
  record EnvView(String env, String inherits, ObjectNode sections, ObjectNode explicit, Map<String, Boolean> inherited,
      List<Lock> locks, List<Runbook> runbooks, long version, boolean verified, boolean serving) {}

  record Busiest(String name, long calls) {}

  /** "Use in the last minute" of the Limits meters. */
  record Usage(Busiest busiestToken, Busiest busiestTool) {}

  record Policies(EnvView dev, EnvView stage, EnvView prod, Usage usage) {}

  private final PolicyService policies;
  private final TemplateService templates;
  private final ConnectionConfigs connections;
  private final AuditQueries audit;
  private final Clock clock;

  PoliciesController(PolicyService policies, TemplateService templates, ConnectionConfigs connections,
      AuditQueries audit, Clock clock) {
    this.policies = policies;
    this.templates = templates;
    this.connections = connections;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping("/api/policies")
  Policies all() {
    Instant minuteAgo = clock.instant().minus(Duration.ofMinutes(1));
    Optional<AuditQueries.Busiest> token = audit.busiest("token", minuteAgo);
    Optional<AuditQueries.Busiest> tool = audit.busiest("tool", minuteAgo);
    return new Policies(view(Env.DEV), view(Env.STAGE), view(Env.PROD), new Usage(
        token.map(b -> new Busiest(b.value(), b.calls())).orElse(null),
        tool.map(b -> new Busiest(b.value(), b.calls())).orElse(null)));
  }

  @GetMapping("/api/policies/schema")
  ObjectNode schema() {
    return policies.schema();
  }

  @GetMapping("/api/policies/{env}")
  EnvView get(@PathVariable String env) {
    return view(env(env));
  }

  @PutMapping("/api/policies/{env}")
  EnvView update(@PathVariable String env, @RequestBody Update request) {
    if (request.version() == null) {
      throw ApiRequestException.unprocessable("Version missing", "Send the version you edited.", "/version");
    }
    if (request.sections() == null) {
      throw ApiRequestException.unprocessable("Nothing to change", "Send the sections to change.", "/sections");
    }
    Env e = env(env);
    policies.update(e, request.sections(), request.version(), Reasons.check(request.reason()), request.confirm());
    return view(e);
  }

  @PostMapping("/api/policies/{env}/reset")
  EnvView reset(@PathVariable String env, @RequestBody(required = false) Reason request) {
    Env e = env(env);
    policies.reset(e, request == null ? null : Reasons.check(request.reason()));
    return view(e);
  }

  /** The person reviewed a policy changed outside MCP Gateway and keeps it as stored. */
  @PostMapping("/api/policies/{env}/confirm-integrity")
  EnvView confirmIntegrity(@PathVariable String env, @RequestBody(required = false) Reason request) {
    Env e = env(env);
    policies.confirmIntegrity(e, request == null ? null : Reasons.check(request.reason()),
        request == null ? null : request.confirm());
    return view(e);
  }

  private EnvView view(Env env) {
    PolicyService.EnvPolicy p = policies.get(env);
    return new EnvView(env.id(), p.inherits() == null ? null : p.inherits().id(), p.sections(), p.explicit(),
        p.inherited(), locks(env), runbooks(env), p.version(), p.verified(), p.serving());
  }

  private List<Lock> locks(Env env) {
    List<Lock> out = new ArrayList<>();
    PolicySchemas schemas = policies.schemas();
    for (String section : schemas.names()) {
      JsonNode schema = schemas.schema(section).orElseThrow();
      for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
        JsonNode def = p.getValue();
        String title = def.path("title").asString(p.getKey());
        if (def.has("const")) {
          out.add(new Lock(section, p.getKey(), title, "fixed", def.get("const"),
              def.path("description").asString("Always on.")));
        }
        JsonNode forbidden = def.path("x-forbidden-env-values").path(env.id());
        if (forbidden.isArray() && !forbidden.isEmpty()) {
          out.add(new Lock(section, p.getKey(), title, "forbidden-values", forbidden, "Not allowed on " + env.id()
              + "."));
        }
        if (env == Env.PROD && "danger".equals(def.path("x-confirm").asString(null))) {
          out.add(new Lock(section, p.getKey(), title, "confirm-danger", null, "Changes on prod only after you "
              + "confirm the danger."));
        }
      }
    }
    return List.copyOf(out);
  }

  private List<Runbook> runbooks(Env env) {
    List<Runbook> out = new ArrayList<>();
    for (TemplateService.TemplateInfo t : templates.list("ssh-runbook")) {
      if (t.connectionName() == null) {
        continue;
      }
      boolean onEnv = connections.byName(t.connectionName()).map(c -> c.config().env() == env).orElse(false);
      if (onEnv) {
        out.add(new Runbook(t.connectionName(), t.name(), t.id(), t.version(), t.status(),
            lastRun(t.connectionName(), t.name())));
      }
    }
    return List.copyOf(out);
  }

  /** When the runbook last ran on its connection (kit: "Last run 5 h ago"), or {@code null}. */
  private Instant lastRun(String connection, String runbook) {
    return audit.lastRun(connection, runbook).orElse(null);
  }

  private static Env env(String id) {
    try {
      return Env.of(id);
    } catch (IllegalArgumentException e) {
      throw ai.msrv.gaterook.core.platform.ProblemException.notFound("No such policy", "Policies are dev, stage and "
          + "prod.");
    }
  }
}
