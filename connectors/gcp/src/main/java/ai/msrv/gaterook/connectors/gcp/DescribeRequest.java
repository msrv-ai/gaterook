package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.Env;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code gcp_describe} call, checked without I/O (report 09 §3.3, §3.5): a search of the inventory in one scope,
 * or one resource by its full name. The same checks run in {@code classify} (a refusal becomes DENY) and again in
 * {@code execute}.
 *
 * @param scope  the scope to search, or for a resource the scope the agent named ({@code null}: every scope of the
 *               connection that can hold it)
 * @param notes  what the gateway changed and why ("limit lowered to 50 by the prod policy")
 */
record DescribeRequest(String scope, String name, List<String> assetTypes, String query, String orderBy, boolean live,
    int limit, String cursor, List<String> notes) {

  static final Pattern NAME = Pattern.compile("^//[a-z0-9-]+\\.googleapis\\.com/[A-Za-z0-9._~@:/-]+$");
  static final Pattern ASSET_TYPE = Pattern.compile("^[a-z0-9-]+\\.googleapis\\.com/[A-Za-z]+$");
  static final Pattern ORDER_BY = Pattern.compile("^(name|assetType|location|createTime|updateTime|state)( DESC)?$");
  /** A custom method at the end of a name ({@code …/versions/1:access}): an API call, never a resource. */
  private static final Pattern METHOD = Pattern.compile(":[A-Za-z]+$");
  static final int MAX_QUERY = 2_000;

  DescribeRequest {
    assetTypes = List.copyOf(assetTypes);
    notes = List.copyOf(notes);
  }

  boolean get() {
    return name != null;
  }

  static DescribeRequest of(JsonNode args, String connection, Settings s, GcpPolicy p) throws Refusal {
    List<String> notes = new ArrayList<>();
    String scope = text(args, "scope");
    if (scope != null && !s.hasScope(scope)) {
      throw new Refusal("scope_not_allowed", scope + " isn't a scope of " + connection + ".", "Its scopes: "
          + String.join(", ", s.scopes()) + ".");
    }
    String name = text(args, "name");
    List<String> types = new ArrayList<>();
    for (JsonNode t : args.path("assetTypes")) {
      types.add(t.asString(""));
    }
    if (types.size() > 10) {
      throw new Refusal("bad_input", "At most 10 assetTypes.", null);
    }
    String query = text(args, "query");
    if (query != null && query.length() > MAX_QUERY) {
      throw new Refusal("bad_input", "query is longer than " + MAX_QUERY + " characters.", null);
    }
    String orderBy = text(args, "orderBy");
    if (orderBy != null && !ORDER_BY.matcher(orderBy).matches()) {
      throw new Refusal("bad_input", "orderBy is one of name, assetType, location, createTime, updateTime, state, "
          + "optionally with \" DESC\".", null);
    }
    String cursor = text(args, "cursor");
    if (cursor != null && cursor.length() > 4096) {
      throw new Refusal("bad_input", "cursor is longer than 4096 characters.", null);
    }
    if (name != null) {
      checkName(name, p);
      if (!types.isEmpty() || query != null || orderBy != null || cursor != null) {
        notes.add("With name, assetTypes, query, orderBy and cursor are not used.");
      }
      types = List.of();
      query = null;
      orderBy = null;
      cursor = null;
    } else {
      for (String t : types) {
        if (!ASSET_TYPE.matcher(t).matches()) {
          throw new Refusal("bad_input", "\"" + abbreviate(t) + "\" isn't an asset type like run.googleapis.com/"
              + "Service.", null);
        }
        checkService(Services.ofType(t), t, p);
      }
      if (types.isEmpty() && p.servicesRestricted() && Services.assetTypePatterns(p.allowedIds()).isEmpty()) {
        throw new Refusal("service_not_allowed", "No GCP service is allowed: " + p.servicesText() + ".",
            "Ask the person to add services to the Cloud policy of " + p.env().id() + ".");
      }
      if (scope == null) {
        scope = s.firstScope();
      }
    }
    boolean live = args.path("live").asBoolean(true) && s.liveGet();
    int dflt = p.env() == Env.PROD ? 10 : 25;
    int asked = args.path("limit").isNumber() ? args.path("limit").asInt(dflt) : dflt;
    if (asked < 1 || asked > 100) {
      throw new Refusal("bad_input", "limit is 1 to 100.", null);
    }
    int limit = Math.min(asked, p.maxItems());
    if (limit < asked) {
      notes.add("limit lowered to " + limit + " by the " + p.env().id() + " policy.");
    }
    return new DescribeRequest(scope, name, types, query, orderBy, live, limit, cursor, notes);
  }

  /** The name checks of §3.5: shape, service, resource masks and location. */
  static void checkName(String name, GcpPolicy p) throws Refusal {
    if (name.length() > 1024 || !NAME.matcher(name).matches() || name.contains("..") || name.indexOf("//", 2) >= 0
        || METHOD.matcher(name).find()) {
      throw new Refusal("bad_name", "\"" + abbreviate(name) + "\" isn't the full name of a resource.",
          "Pass a name exactly as gcp_describe returned it, like //run.googleapis.com/projects/acme-stage/locations/"
              + "europe-west1/services/api.");
    }
    checkService(Services.ofName(name), name, p);
    if (!p.resourceAllowed(name)) {
      throw new Refusal("resource_not_allowed", abbreviate(name) + " is outside the resources the " + p.env().id()
          + " policy shows to agents.", null);
    }
    String location = locationOf(name);
    if (location != null && !p.regionAllowed(location)) {
      throw new Refusal("region_not_allowed", abbreviate(name) + " is in " + location + "; " + p.regionsText() + ".",
          null);
    }
  }

  static void checkService(String service, String what, GcpPolicy p) throws Refusal {
    if (Services.sensitive(service) && !(p.servicesRestricted() && p.allowedIds().contains(service))) {
      throw new Refusal("sensitive_type", abbreviate(what) + " belongs to " + service + ", which agents don't see "
          + "unless the " + p.env().id() + " policy lists it.", null);
    }
    if (!p.serviceAllowed(service)) {
      throw new Refusal("service_not_allowed", abbreviate(what) + " belongs to " + service + "; " + p.servicesText()
          + ".", null);
    }
  }

  /** The location in a full name ({@code locations/…}, {@code zones/…}, {@code regions/…}, {@code global}), or null. */
  static String locationOf(String name) {
    String[] parts = name.substring(2).split("/");
    for (int i = 1; i < parts.length; i++) {
      String p = parts[i];
      if ((p.equals("locations") || p.equals("zones") || p.equals("regions")) && i + 1 < parts.length) {
        return parts[i + 1];
      }
      if (p.equals("global")) {
        return "global";
      }
    }
    return null;
  }

  /** The request as the approval stores it and the approved call runs it. */
  ObjectNode payload() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("tool", "gcp_describe");
    if (scope != null) {
      n.put("scope", scope);
    }
    if (name != null) {
      n.put("name", name);
    }
    if (!assetTypes.isEmpty()) {
      ArrayNode a = n.putArray("assetTypes");
      assetTypes.forEach(a::add);
    }
    if (query != null) {
      n.put("query", query);
    }
    if (orderBy != null) {
      n.put("orderBy", orderBy);
    }
    n.put("live", live);
    n.put("limit", limit);
    if (cursor != null) {
      n.put("cursor", cursor);
    }
    return n;
  }

  /** What a cursor is bound to: the search without its page and size. */
  String hash() {
    ObjectNode n = payload();
    n.remove("cursor");
    n.remove("limit");
    return sha256(n.toString());
  }

  static String sha256(String text) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String text(JsonNode args, String field) {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String v = n.asString("").strip();
    return v.isEmpty() ? null : v;
  }

  static String abbreviate(String s) {
    return s.length() <= 120 ? s : s.substring(0, 120) + "…";
  }
}
