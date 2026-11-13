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

  /**
   * The value a merged list holds when the lists it was narrowed from had nothing in common: no name matches it, so
   * the list allows nothing (an empty list would read as "every name" or as the connector's default set).
   */
  public static final String NOTHING = "\u0000none";

  /**
   * In a list whose key's schema says {@code "x-empty": "connector-default"}: the core narrowed it from an empty list,
   * which allows the connector's default set. The connector then keeps only the listed names that an empty list
   * allows too, so an endpoint or a tool override never reaches past that set (for example a Kubernetes Secret, or a
   * sensitive cloud service).
   */
  public static final String WITHIN_DEFAULT = "\u0000within-default";

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
