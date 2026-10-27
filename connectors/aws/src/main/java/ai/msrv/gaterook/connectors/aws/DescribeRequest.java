package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.Env;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code aws_describe} call, checked without I/O (report 09 §3.1, §3.5): the same checks run in {@code classify}
 * (a refusal becomes DENY {@code <env>.cloud.<rule>}), in {@code describeAction} and again in {@code execute}.
 * Without {@code identifier} it lists one type through Cloud Control {@code ListResources}; with it, it reads one
 * resource with {@code GetResource}.
 *
 * @param parent the list's {@code ResourceModel} (a parent such as {@code LoadBalancerArn}), sorted by key
 * @param where  filters after listing: dotted property paths or {@code tags.<key>} → glob of the value
 * @param notes  what the gateway changed and why ("limit lowered to 50 by the prod policy")
 */
record DescribeRequest(String region, String type, String service, String identifier, Map<String, String> parent,
    Map<String, String> where, List<String> properties, int limit, String cursor, List<String> notes) {

  static final Pattern TYPE = Pattern.compile("^AWS::[A-Za-z0-9]+::[A-Za-z0-9]+$");
  private static final Pattern PARENT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");
  private static final Pattern WHERE_KEY = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.:/-]{0,127}$");
  private static final Pattern PROPERTY = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,127}$");

  DescribeRequest {
    parent = Map.copyOf(parent);
    where = Map.copyOf(where);
    properties = List.copyOf(properties);
    notes = List.copyOf(notes);
  }

  boolean get() {
    return identifier != null;
  }

  static DescribeRequest of(JsonNode args, String connection, Settings s, AwsPolicy p) throws Refusal {
    String region = Requests.region(args, connection, s, p);
    String type = args.path("type").asString("");
    if (type.isEmpty()) {
      throw new Refusal("bad_input", "type is required: a CloudFormation type such as AWS::EC2::Instance.", null);
    }
    if (!type.startsWith("AWS::")) {
      throw new Refusal("third_party_type", type + " isn't an AWS type: third-party types run someone else's "
          + "handler, and aws_describe reads AWS types only.", null);
    }
    if (!TYPE.matcher(type).matches()) {
      throw new Refusal("bad_input", "\"" + Requests.abbreviate(type) + "\" isn't a CloudFormation type "
          + "(AWS::EC2::Instance).", null);
    }
    if (Services.secretValues(type)) {
      throw new Refusal("sensitive_type", "Reading " + type + " returns secret values, so aws_describe never reads "
          + "it.", "Ask the person for what you need; the gateway keeps secrets away from agents.");
    }
    String service = Services.ofType(type);
    Requests.service(service, type, p);
    String identifier = null;
    if (args.hasNonNull("identifier")) {
      identifier = args.get("identifier").asString("").strip();
      if (identifier.isEmpty() || identifier.length() > 2048) {
        throw new Refusal("bad_input", "identifier is the primary identifier from a list result, 1 to 2048 "
            + "characters.", null);
      }
      Requests.printable(identifier, "identifier");
      if (p.resourcesRestricted()) {
        String arn = TypeCatalog.arn(type, identifier, null, region, s.account());
        if (arn != null && !p.resourceAllowed(arn)) {
          throw new Refusal("resource_not_allowed", arn + " is outside the resources the " + p.env().id()
              + " policy allows.", "Agents see nothing outside them.");
        }
      }
    }
    Map<String, String> parent = new TreeMap<>();
    JsonNode parentNode = args.path("parent");
    if (parentNode.isObject()) {
      if (identifier != null && !parentNode.isEmpty()) {
        throw new Refusal("bad_input", "parent is only for listing; with identifier the resource is read directly.",
            null);
      }
      if (parentNode.size() > 6) {
        throw new Refusal("bad_input", "parent has at most 6 properties.", null);
      }
      for (Map.Entry<String, JsonNode> e : parentNode.properties()) {
        if (!PARENT_KEY.matcher(e.getKey()).matches() || !e.getValue().isString()
            || e.getValue().asString().length() > 2048) {
          throw new Refusal("bad_input", "parent holds property names and text values, e.g. {\"LoadBalancerArn\":"
              + "\"arn:…\"}.", null);
        }
        Requests.printable(e.getValue().asString(), "parent");
        parent.put(e.getKey(), e.getValue().asString());
      }
    }
    Map<String, String> where = new LinkedHashMap<>();
    JsonNode whereNode = args.path("where");
    if (whereNode.isObject()) {
      if (whereNode.size() > 5) {
        throw new Refusal("bad_input", "where has at most 5 filters.", null);
      }
      for (Map.Entry<String, JsonNode> e : whereNode.properties()) {
        if (!WHERE_KEY.matcher(e.getKey()).matches() || !e.getValue().isValueNode()
            || e.getValue().asString("").length() > 256) {
          throw new Refusal("bad_input", "where maps a property path (State.Name, tags.env) to a value; * matches "
              + "any text.", null);
        }
        where.put(e.getKey(), e.getValue().asString(""));
      }
    }
    List<String> properties = new ArrayList<>();
    for (JsonNode n : args.path("properties")) {
      String name = n.asString("");
      if (!PROPERTY.matcher(name).matches()) {
        throw new Refusal("bad_input", "properties lists top-level property names (Engine, DBInstanceClass).", null);
      }
      if (!properties.contains(name)) {
        properties.add(name);
      }
    }
    if (properties.size() > 30) {
      throw new Refusal("bad_input", "properties lists at most 30 names.", null);
    }
    List<String> notes = new ArrayList<>();
    int dflt = p.env() == Env.PROD ? 10 : 25;
    int asked = args.path("limit").isNumber() ? args.path("limit").asInt(dflt) : dflt;
    if (asked < 1 || asked > 100) {
      throw new Refusal("bad_input", "limit is 1 to 100.", null);
    }
    int limit = Math.min(asked, p.maxItems());
    if (limit < asked) {
      notes.add("limit lowered to " + limit + " by the " + p.env().id() + " policy.");
    }
    String cursor = null;
    if (args.hasNonNull("cursor")) {
      cursor = args.get("cursor").asString("");
      if (identifier != null) {
        throw new Refusal("bad_input", "cursor continues a list; with identifier there is one resource.", null);
      }
      if (!cursor.startsWith(Cursors.PREFIX) || cursor.length() > 4096) {
        throw new Refusal("bad_input", "This cursor doesn't come from aws_describe. Pass nextCursor of the previous "
            + "answer, or leave cursor out.", null);
      }
    }
    return new DescribeRequest(region, type, service, identifier, parent, where, properties, limit, cursor, notes);
  }

  /** The canonical request of an approval: what runs after a person approves it. */
  ObjectNode payload() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", "aws_describe");
    n.put("region", region);
    n.put("type", type);
    if (identifier != null) {
      n.put("identifier", identifier);
    }
    if (!parent.isEmpty()) {
      ObjectNode o = n.putObject("parent");
      parent.forEach(o::put);
    }
    if (!where.isEmpty()) {
      ObjectNode o = n.putObject("where");
      new TreeMap<>(where).forEach(o::put);
    }
    if (!properties.isEmpty()) {
      ArrayNode a = n.putArray("properties");
      properties.forEach(a::add);
    }
    n.put("limit", limit);
    if (cursor != null) {
      n.put("cursor", cursor);
    }
    return n;
  }

  /** The query without its page: a cursor continues only the query it came from. */
  String hash() {
    ObjectNode n = payload();
    n.remove("cursor");
    n.remove("limit");
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(n.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code ResourceModel} of the list, or {@code null}. */
  String resourceModel() {
    if (parent.isEmpty()) {
      return null;
    }
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    parent.forEach(o::put);
    return o.toString();
  }
}
