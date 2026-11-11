package ai.msrv.gaterook.connectors.aws;

import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What {@code describe_connection} tells about the AWS API for {@code aws_api_read} and {@code aws_api_change}: a
 * service's operations by the tool that calls them ({@code "ecs"}), or one operation's parameters
 * ({@code "ecs DescribeServices"}, {@code "ecs:describe-services"}), from the catalog alone.
 */
final class ApiDescribe {

  /** Members of nested structures shown below an operation's own parameters. */
  static final int NESTED = 40;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ApiDescribe() {}

  static Optional<JsonNode> of(ApiCatalog catalog, String object) {
    String[] parts = object.strip().split("[\\s:/]+|\\.(?=[A-Za-z])", 2);
    ApiCatalog.Entry entry = catalog.find(parts[0]);
    if (entry == null) {
      return Optional.empty();
    }
    ObjectNode out = F.objectNode();
    out.put("service", entry.name());
    out.put("sdkId", entry.sdkId());
    if (entry.unsupported() != null) {
      out.put("callable", false);
      out.put("reason", entry.unsupported());
      return Optional.of(out);
    }
    ApiModel m = catalog.model(entry);
    out.put("protocol", m.protocol);
    out.put("iamPrefix", m.iam);
    if (parts.length == 1 || parts[1].isBlank()) {
      ArrayNode reads = out.putArray("reads");
      ArrayNode changes = out.putArray("changes");
      ArrayNode refused = out.putArray("refused");
      for (ApiModel.Operation op : m.operations().values()) {
        if (ApiRules.denial(m.name, op) != null) {
          refused.add(op.name());
        } else if (ApiRules.access(m.name, op) == ApiRules.Access.READ) {
          reads.add(op.name());
        } else {
          changes.add(op.name());
        }
      }
      out.put("note", "Call reads with aws_api_read, which runs them at once; changes with aws_api_change, which waits "
          + "for a person's approval of the exact request.");
      return Optional.of(out);
    }
    ApiModel.Operation op = m.operation(parts[1]);
    if (op == null) {
      out.put("operation", parts[1]);
      out.put("found", false);
      return Optional.of(out);
    }
    out.put("operation", op.name());
    ApiRules.Denial denial = ApiRules.denial(m.name, op);
    if (denial != null) {
      out.put("access", "refused");
      out.put("reason", denial.reason());
      return Optional.of(out);
    }
    boolean read = ApiRules.access(m.name, op) == ApiRules.Access.READ;
    out.put("access", read ? "read" : "change");
    out.put("tool", read ? ApiRequest.READ : ApiRequest.CHANGE);
    out.put("iamAction", ApiPolicies.action(m, op));
    if (op.paging() != null && op.paging().inputToken() != null) {
      out.put("paginated", true);
    }
    if (op.input() != null) {
      int[] budget = {NESTED};
      out.set("parameters", members(m, m.shape(op.input()), 0, budget));
    }
    return Optional.of(out);
  }

  /** {@code {"cluster": "string", "services": "list of string", "include": ...}}, required ones marked. */
  private static ObjectNode members(ApiModel m, JsonNode shape, int depth, int[] budget) {
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> e : ApiModel.members(shape)) {
      JsonNode member = e.getValue();
      JsonNode target = m.shape(member.path("s").asString());
      String type = describe(m, target);
      if (target.path("sensitive").asInt(0) == 1) {
        type += ", secret: never sent";
      }
      if (member.path("req").asInt(0) == 1) {
        type += ", required";
      }
      if (member.path("token").asInt(0) == 1) {
        type += ", filled by GateRook when left out";
      }
      String t = ApiModel.type(target);
      JsonNode inner = t.equals("list") ? m.shape(target.path("member").path("s").asString()) : target;
      if (depth == 0 && (ApiModel.type(inner).equals("structure") || ApiModel.type(inner).equals("union"))
          && budget[0] > 0) {
        budget[0]--;
        ObjectNode nested = F.objectNode();
        nested.put("type", type);
        nested.set("members", members(m, inner, depth + 1, budget));
        out.set(e.getKey(), nested);
      } else {
        out.put(e.getKey(), type);
      }
    }
    return out;
  }

  private static String describe(ApiModel m, JsonNode shape) {
    return switch (ApiModel.type(shape)) {
      case "list" -> "list of " + describe(m, m.shape(shape.path("member").path("s").asString()));
      case "map" -> "map of " + describe(m, m.shape(shape.path("value").path("s").asString()));
      case "timestamp" -> "timestamp (ISO-8601)";
      case "blob" -> "Base64";
      default -> ApiModel.type(shape);
    };
  }
}
