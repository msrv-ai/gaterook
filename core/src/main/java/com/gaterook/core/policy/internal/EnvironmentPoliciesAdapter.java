package com.gaterook.core.policy.internal;

import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connectors.support.egress.Cidr;
import com.gaterook.core.connectors.EnvironmentPolicies;
import com.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** {@link EnvironmentPolicies} for the modules below {@code policy}: the environment's values, no overrides. */
@Component
class EnvironmentPoliciesAdapter implements EnvironmentPolicies {

  private static final Logger log = LoggerFactory.getLogger(EnvironmentPoliciesAdapter.class);

  private final PolicyService policies;
  /** {@code env|range} of the ranges already reported as unreadable, so each is logged once. */
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  EnvironmentPoliciesAdapter(PolicyService policies) {
    this.policies = policies;
  }

  @Override
  public PolicyView view(Env env, ConnectorManifest manifest) {
    String section = policies.schemas().sectionOf(manifest.id()).orElse(manifest.id());
    JsonNode sections = policies.effective(env);
    JsonNode own = sections.path(section);
    return new PolicyView(env, env.id() + "." + section,
        own.isObject() ? own.deepCopy() : JsonNodeFactory.instance.objectNode(), LimitsOf.from(sections, env, section));
  }

  @Override
  public Limits limits(Env env) {
    return policies.limits(env);
  }

  /**
   * {@inheritDoc}
   *
   * <p>An entry that isn't a network the egress guard can read (stored before saves checked it, for example
   * {@code 192.168.01.0/24}) allows nothing: it is left out and logged once, so the rest of the environment's calls,
   * tests and health checks keep working instead of failing on it.
   */
  @Override
  public List<String> allowedPrivateRanges(Env env, String connectionName) {
    List<String> out = new ArrayList<>();
    for (JsonNode range : policies.section(env, "http").path(PolicyService.PRIVATE_RANGES)) {
      // {cidr, connections} as the schema has it; a bare string is read as a range for every connection.
      JsonNode cidr = range.isObject() ? range.path("cidr") : range;
      if (!cidr.isString() || cidr.asString().isBlank()) {
        continue;
      }
      if (!readable(env, cidr.asString())) {
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

  private boolean readable(Env env, String text) {
    try {
      Cidr.parse(text);
      return true;
    } catch (IllegalArgumentException e) {
      if (reported.size() < 1_000 && reported.add(env.id() + "|" + text)) {
        String shown = text.strip().length() > 60 ? text.strip().substring(0, 60) + "…" : text.strip();
        log.warn("The {} policy allows the private range \"{}\", which isn't a network GateRook can read: it allows "
            + "nothing. Correct it under Policies › {} › HTTP egress.", env.id(), shown.replaceAll("\\p{Cntrl}", "?"),
            env.id());
      }
      return false;
    }
  }

  @Override
  public void renameConnection(String from, String to, String reason) {
    policies.renameConnection(from, to, reason);
  }
}
