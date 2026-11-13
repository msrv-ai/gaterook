package com.gaterook.connectors.aws;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One service of the AWS API catalog ({@link ApiCatalog}): its protocol and signing facts, the endpoint table the
 * generator resolved from the model's rules, its operations and the shapes of their input and output. Read only.
 *
 * <p>Shapes are the generator's compact form: {@code {"t": "structure", "m": {"Name": {"s": "Target", ...}}}}, a
 * built-in type as {@code "#string"}; member traits keep their protocol meaning ({@code label}, {@code query},
 * {@code header}, {@code payload}, {@code json}, {@code xml}, {@code flat}, {@code attr}, {@code ec2}, {@code ts},
 * {@code token}, {@code host}, {@code req}); a shape may be {@code sensitive}, {@code streaming} or carry a
 * {@code ts} (timestamp format), {@code xml} name, {@code ns} (XML namespace) or {@code media} type.
 */
final class ApiModel {

  /** The partitions the AWS API tools reach, as the generator resolved them; another partition's region is refused. */
  private static final Pattern AWS = Pattern.compile("^(us|eu|ap|sa|ca|me|af|il|mx)-\\w+-\\d+$");
  private static final Pattern AWS_CN = Pattern.compile("^cn-\\w+-\\d+$");
  private static final Pattern AWS_US_GOV = Pattern.compile("^us-gov-\\w+-\\d+$");

  /** How a paginated operation pages ({@code smithy.api#paginated}). */
  record Paging(String inputToken, String outputToken, String items, String pageSize) {}

  /** One operation and the facts its classification and request need. */
  record Operation(String name, String input, String output, boolean readonly, String method, String uri, int code,
      Paging paging, String hostPrefix, String iamAction, boolean noAuth, boolean dataPlane, boolean discovery,
      boolean checksum, boolean unsignedPayload, List<String> streaming, int endpointGroup) {

    /** The IAM action's name: the model's {@code aws.iam#iamAction} name where it renames it, else the operation's. */
    String action() {
      return iamAction != null ? iamAction : name;
    }
  }

  /** Where one request goes: the URL of the service in the region, and what it is signed for. */
  record Endpoint(URI url, String signingRegion, String signingName) {}

  final String name;
  final String sdkId;
  final String protocol;
  /** The service shape's name: {@code X-Amz-Target} of the JSON protocols. */
  final String serviceShape;
  final String signingName;
  final String iam;
  final String version;
  final String xmlNamespace;
  private final JsonNode endpoints;
  private final Map<String, Operation> operations;
  private final Map<String, String> spellings;
  private final JsonNode shapes;

  ApiModel(JsonNode doc) {
    this.name = doc.path("name").asString();
    this.sdkId = doc.path("sdkId").asString(name);
    this.protocol = doc.path("protocol").asString();
    this.serviceShape = doc.path("service").asString();
    this.signingName = doc.path("signingName").asString(null);
    this.iam = doc.path("iam").asString(name);
    this.version = doc.path("version").asString("");
    this.xmlNamespace = doc.hasNonNull("xmlNamespace") ? doc.get("xmlNamespace").asString() : null;
    this.endpoints = doc.path("endpoints");
    this.shapes = doc.path("shapes");
    Map<String, Operation> ops = new LinkedHashMap<>();
    Map<String, String> spelled = new HashMap<>();
    Map<String, Integer> collisions = new HashMap<>();
    for (Map.Entry<String, JsonNode> e : doc.path("operations").properties()) {
      Operation op = operation(e.getKey(), e.getValue());
      ops.put(op.name(), op);
      String key = spelling(op.name());
      if (collisions.merge(key, 1, Integer::sum) == 1) {
        spelled.put(key, op.name());
      } else {
        spelled.remove(key);
      }
    }
    this.operations = Collections.unmodifiableMap(ops);
    this.spellings = Map.copyOf(spelled);
  }

  private static Operation operation(String name, JsonNode o) {
    JsonNode http = o.path("http");
    JsonNode page = o.path("page");
    Paging paging = page.isObject() ? new Paging(text(page, "inputToken"), text(page, "outputToken"),
        text(page, "items"), text(page, "pageSize")) : null;
    List<String> streaming = new ArrayList<>();
    o.path("streaming").forEach(s -> streaming.add(s.asString()));
    return new Operation(name, text(o, "i"), text(o, "o"), o.path("ro").asInt(0) == 1,
        http.isArray() ? http.get(0).asString() : "POST", http.isArray() ? http.get(1).asString() : "/",
        http.isArray() ? http.get(2).asInt(200) : 200, paging, text(o, "hostPrefix"), text(o, "iam"),
        o.path("noAuth").asInt(0) == 1, o.path("dataPlane").asInt(0) == 1, o.path("discovery").asInt(0) == 1,
        o.path("checksum").asInt(0) == 1, o.path("unsignedPayload").asInt(0) == 1, List.copyOf(streaming),
        o.path("endpoint").asInt(0));
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  /** {@code DescribeServices}, {@code describe-services}, {@code describe_services} → one key. */
  static String spelling(String operation) {
    return operation.strip().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
  }

  /** The operation an agent named in any of its spellings, or {@code null}. */
  Operation operation(String spelled) {
    if (spelled == null) {
      return null;
    }
    Operation exact = operations.get(spelled.strip());
    if (exact != null) {
      return exact;
    }
    String name = spellings.get(spelling(spelled));
    return name == null ? null : operations.get(name);
  }

  Map<String, Operation> operations() {
    return operations;
  }

  /** A shape by its reference; a built-in ({@code #string}) as a shape of that type. */
  JsonNode shape(String ref) {
    if (ref == null) {
      return null;
    }
    if (ref.startsWith("#")) {
      ObjectNode builtIn = JsonNodeFactory.instance.objectNode();
      builtIn.put("t", ref.substring(1));
      return builtIn;
    }
    JsonNode s = shapes.get(ref);
    if (s == null) {
      throw new IllegalStateException(name + " has no shape " + ref);
    }
    return s;
  }

  /** The type of a shape: {@code structure}, {@code list}, {@code string}, {@code timestamp}… */
  static String type(JsonNode shape) {
    return shape.path("t").asString("");
  }

  /** The members of a structure or union, in model order. */
  static Iterable<Map.Entry<String, JsonNode>> members(JsonNode shape) {
    return shape.path("m").properties();
  }

  /** Whether the shape a member targets is {@code @sensitive}. */
  boolean sensitive(JsonNode member) {
    return shape(member.path("s").asString()).path("sensitive").asInt(0) == 1;
  }

  /** The partition of {@code region} as the catalog's endpoint table names it, or {@code null} for another one. */
  static String partition(String region) {
    if (AWS_US_GOV.matcher(region).matches()) {
      return "aws-us-gov";
    }
    if (AWS_CN.matcher(region).matches()) {
      return "aws-cn";
    }
    return AWS.matcher(region).matches() ? "aws" : null;
  }

  /**
   * Where {@code op} goes in {@code region}: the region's own entry, else its partition's template.
   *
   * @throws Refusal when the model's rules have no endpoint there
   */
  Endpoint endpoint(Operation op, String region) throws Refusal {
    String partition = partition(region);
    if (partition == null) {
      throw new Refusal("scope_not_allowed", "The AWS API tools reach the aws, aws-cn and aws-us-gov partitions; "
          + region
          + " is in none of them.", null);
    }
    JsonNode group = endpoints.path(op.endpointGroup());
    JsonNode e = group.path("regions").path(region);
    if (e.isMissingNode()) {
      e = group.path(partition);
    }
    if (e.isMissingNode() || e.has("error") || !e.hasNonNull("url")) {
      String why = e.path("error").asString(group.path("error").asString("no endpoint"));
      throw new Refusal("unsupported", sdkId + " has no endpoint in " + region + " (" + why + ").", null);
    }
    String url = e.get("url").asString().replace("{region}", region);
    String signingRegion = e.path("signingRegion").asString(region).replace("{region}", region);
    String signing = e.path("signingName").asString(signingName);
    return new Endpoint(URI.create(url), signingRegion, signing);
  }
}
