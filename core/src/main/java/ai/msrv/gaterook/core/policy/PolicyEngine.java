package ai.msrv.gaterook.core.policy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.policy.Decision.Outcome;
import ai.msrv.gaterook.core.policy.internal.LimitsOf;
import ai.msrv.gaterook.core.policy.internal.StricterMerge;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The decision for one classified call (ARCHITECTURE.md §5 step 7, §9 "Every mutation requires
 * approval"). The truth table is an invariant with no switch anywhere:
 * <ol>
 *   <li>a DENY of the classification → DENY;</li>
 *   <li>mode Off → "Unknown tool";</li>
 *   <li>a MUTATION → APPROVAL, whatever the mode (on a read-only endpoint: DENY, it never asks a person);</li>
 *   <li>mode Approval → APPROVAL, for a READ too;</li>
 *   <li>READ on a Read only pair → ALLOW.</li>
 * </ol>
 * Before these: an environment whose policy didn't verify denies everything ({@code core.integrity}); a prod
 * connection outside a prod endpoint, or the reverse, is denied ({@code core.prod-isolation}). A tool whose manifest
 * says MUTATION is a mutation even if its connector classified the call as READ.
 *
 * <p>Limits = min(environment policy of the connection, the connection's own stricter settings, the endpoint's,
 * the pair's), merged key by key with {@code x-stricter}.
 */
@Component
public class PolicyEngine {

  private final PolicyService policies;

  public PolicyEngine(PolicyService policies) {
    this.policies = policies;
  }

  /** The merged policy the connector gets in {@code classify} (no I/O, a few µs). */
  public PolicyView view(PairView pair, ConnectionConfig cfg) {
    return view(pair, cfg, merged(pair, cfg));
  }

  /** Calls per minute of one principal, and of one principal × tool, on this pair (ARCHITECTURE.md §5 step 5). */
  public record Rates(int perToken, int perTool) {}

  /** The rate limits of a pair before its call is classified: they apply to every call, refused or not. */
  public Rates rates(PairView pair, ConnectionConfig cfg) {
    ObjectNode sections = merged(pair, cfg);
    return new Rates(LimitsOf.ratePerToken(sections, pair.connectionEnv()),
        LimitsOf.ratePerTool(sections, pair.connectionEnv()));
  }

  /** The rate limits of an environment (core tools that have no pair, like {@code approval_status}). */
  public Rates rates(Env env) {
    ObjectNode sections = policies.effective(env);
    return new Rates(LimitsOf.ratePerToken(sections, env), LimitsOf.ratePerTool(sections, env));
  }

  /** The rule of a call refused because a policy it depends on didn't verify. */
  public static final String INTEGRITY_RULE = "core.integrity";

  /**
   * The first of {@code envs} whose policy (or a policy it inherits from) didn't verify, or {@code null}: what a call
   * without a pair (the core's own tools) checks before it reads anything, as {@link #decide} does first.
   */
  public Env unverified(Env... envs) {
    for (Env env : envs) {
      if (env != null && !policies.verified(env)) {
        return env;
      }
    }
    return null;
  }

  /** What the agent is told when {@code env}'s policy didn't verify. */
  public static String integrityReason(Env env) {
    return "The " + env.id() + " policy was changed outside GateRook. Ask the user to review it in GateRook.";
  }

  public Decision decide(PairView pair, ConnectionConfig cfg, Classification classification) {
    ObjectNode sections = merged(pair, cfg);
    PolicyView view = view(pair, cfg, sections);
    Env env = pair.connectionEnv();
    int perToken = LimitsOf.ratePerToken(sections, env);
    int perTool = LimitsOf.ratePerTool(sections, env);
    Env unverified = unverified(env, pair.endpointEnv());
    if (unverified != null) {
      return new Decision(Outcome.DENY, INTEGRITY_RULE, integrityReason(unverified), null, view, view.limits(),
          perToken, perTool);
    }
    if (pair.mode() == Mode.OFF) {
      return new Decision(Outcome.UNKNOWN_TOOL, null, "Unknown tool " + pair.tool().name(), null, view,
          view.limits(), perToken, perTool);
    }
    if (classification == null) {
      return deny("core.classify-error", "The call couldn't be classified, so it was not run.", view, perToken,
          perTool);
    }
    if (classification.kind() == Classification.Kind.DENY) {
      String rule = classification.ruleId() == null ? view.rule("deny") : classification.ruleId();
      return new Decision(Outcome.DENY, rule, classification.reason(), classification.hint(), view, view.limits(),
          perToken, perTool);
    }
    if ((pair.endpointEnv() == Env.PROD) != (pair.connectionEnv() == Env.PROD)) {
      return deny("core.prod-isolation", "Prod connections are used only through prod endpoints.", view, perToken,
          perTool);
    }
    boolean mutation = classification.kind() == Classification.Kind.MUTATION
        || pair.tool().nature() == ToolDefinition.Nature.MUTATION;
    if (mutation) {
      if (pair.endpointReadOnly()) {
        return deny(pair.endpointEnv().id() + ".endpoint.read-only", pair.endpointSlug() + " is read only: it "
            + "doesn't run anything that changes data.", view, perToken, perTool);
      }
      String reason = classification.reason() == null ? "Changes data" : classification.reason();
      return new Decision(Outcome.APPROVAL, view.rule("write-approval"), reason, null, view, view.limits(), perToken,
          perTool);
    }
    if (pair.mode() == Mode.APPROVAL) {
      return new Decision(Outcome.APPROVAL, view.rule("approval-mode"), "The tool is set to Approval on "
          + pair.endpointSlug(), null, view, view.limits(), perToken, perTool);
    }
    return new Decision(Outcome.ALLOW, null, null, null, view, view.limits(), perToken, perTool);
  }

  private static Decision deny(String rule, String reason, PolicyView view, int perToken, int perTool) {
    return new Decision(Outcome.DENY, rule, reason, null, view, view.limits(), perToken, perTool);
  }

  private PolicyView view(PairView pair, ConnectionConfig cfg, ObjectNode sections) {
    PolicySchemas schemas = policies.schemas();
    ConnectorManifest m = pair.manifest();
    String section = schemas.sectionOf(m.id()).orElse(m.id());
    JsonNode own = sections.path(section);
    return new PolicyView(pair.connectionEnv(), pair.connectionEnv().id() + "." + section,
        own.isObject() ? own.deepCopy() : JsonNodeFactory.instance.objectNode(),
        LimitsOf.from(sections, pair.connectionEnv()));
  }

  /** The environment's sections narrowed by the connection, the endpoint and the pair. */
  ObjectNode merged(PairView pair, ConnectionConfig cfg) {
    PolicySchemas schemas = policies.schemas();
    ObjectNode sections = policies.effective(pair.connectionEnv()).deepCopy();
    String section = schemas.sectionOf(pair.manifest().id()).orElse(null);
    if (section != null && cfg != null && pair.manifest().configSchema() != null) {
      ObjectNode own = (ObjectNode) sections.path(section);
      for (String[] field : stricterFields(pair.manifest().configSchema())) {
        JsonNode value = cfg.config().at(field[0]);
        if (!value.isMissingNode() && !schemas.key(section, field[1]).isMissingNode()) {
          own.set(field[1], StricterMerge.merge(schemas.stricter(section, field[1]), own.get(field[1]), value));
        }
      }
    }
    narrow(schemas, sections, pair.endpointLimits());
    narrow(schemas, sections, pair.overrides());
    return sections;
  }

  /** Applies a flat map of overrides to every section that has the key, by that key's {@code x-stricter}. */
  private static void narrow(PolicySchemas schemas, ObjectNode sections, JsonNode overrides) {
    if (overrides == null || !overrides.isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> o : overrides.properties()) {
      for (String section : schemas.names()) {
        if (schemas.key(section, o.getKey()).isMissingNode() || !sections.path(section).isObject()) {
          continue;
        }
        ObjectNode values = (ObjectNode) sections.get(section);
        values.set(o.getKey(), StricterMerge.merge(schemas.stricter(section, o.getKey()), values.get(o.getKey()),
            o.getValue()));
      }
    }
  }

  /** {@code [pointer, key]} of every property of a config schema that declares {@code x-stricter}. */
  static List<String[]> stricterFields(JsonNode schema) {
    List<String[]> out = new ArrayList<>();
    collect(schema, "", out);
    return out;
  }

  private static void collect(JsonNode schema, String pointer, List<String[]> out) {
    JsonNode properties = schema.path("properties");
    for (Map.Entry<String, JsonNode> p : properties.properties()) {
      String child = pointer + "/" + p.getKey();
      if (p.getValue().has("x-stricter")) {
        out.add(new String[] {child, p.getKey()});
      }
      if (p.getValue().path("properties").isObject()) {
        collect(p.getValue(), child, out);
      }
    }
  }
}
