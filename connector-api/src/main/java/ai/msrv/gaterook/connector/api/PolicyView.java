package ai.msrv.gaterook.connector.api;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The connector's policy section merged by the core (environment → connection → endpoint → tool, only stricter
 * wins).
 *
 * @param rulePrefix e.g. {@code "prod.sql"}; rule ids shown in Activity are {@code rulePrefix + "." + id}
 */
public record PolicyView(Env env, String rulePrefix, JsonNode section, Limits limits) {

  public PolicyView {
    if (section == null) {
      section = JsonNodeFactory.instance.objectNode();
    }
  }

  public String rule(String id) {
    return rulePrefix + "." + id;
  }

  public boolean flag(String key, boolean dflt) {
    JsonNode n = section.get(key);
    return n == null || n.isNull() ? dflt : n.asBoolean(dflt);
  }

  public int integer(String key, int dflt) {
    JsonNode n = section.get(key);
    return n == null || n.isNull() ? dflt : n.asInt(dflt);
  }

  public List<String> strings(String key) {
    List<String> out = new ArrayList<>();
    JsonNode n = section.get(key);
    if (n != null) {
      for (JsonNode v : n) {
        out.add(v.asString());
      }
    }
    return out;
  }
}
