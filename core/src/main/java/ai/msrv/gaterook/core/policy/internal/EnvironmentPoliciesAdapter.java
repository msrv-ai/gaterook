package ai.msrv.gaterook.core.policy.internal;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** {@link EnvironmentPolicies} for the modules below {@code policy}: the environment's values, no overrides. */
@Component
class EnvironmentPoliciesAdapter implements EnvironmentPolicies {

  private final PolicyService policies;

  EnvironmentPoliciesAdapter(PolicyService policies) {
    this.policies = policies;
  }

  @Override
  public PolicyView view(Env env, ConnectorManifest manifest) {
    String section = policies.schemas().sectionOf(manifest.id()).orElse(manifest.id());
    JsonNode own = policies.section(env, section);
    return new PolicyView(env, env.id() + "." + section,
        own.isObject() ? own.deepCopy() : JsonNodeFactory.instance.objectNode(), policies.limits(env));
  }

  @Override
  public Limits limits(Env env) {
    return policies.limits(env);
  }

  @Override
  public List<String> allowedPrivateRanges(Env env, String connectionName) {
    List<String> out = new ArrayList<>();
    for (JsonNode range : policies.section(env, "http").path("allowedPrivateRanges")) {
      // {cidr, connections} as the schema has it; a bare string is read as a range for every connection.
      JsonNode cidr = range.isObject() ? range.path("cidr") : range;
      if (!cidr.isString() || cidr.asString().isBlank()) {
        continue;
      }
      JsonNode only = range.path("connections");
      boolean applies = !only.isArray() || only.isEmpty();
      for (JsonNode name : only) {
        applies |= name.asString("").equals(connectionName);
      }
      if (applies) {
        out.add(cidr.asString().strip());
      }
    }
    return List.copyOf(out);
  }
}
