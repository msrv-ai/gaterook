package ai.msrv.gaterook.connectors.azure;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code azure_describe} call, checked without I/O: a Resource Graph query (KQL over the connection's
 * subscriptions) or the current state of one resource by its ID. The subscriptions a query reads are always the
 * connection's, set in the request body by the gateway; the query text can't widen them.
 *
 * <p>Checks: the length, no control command, and the tables: {@code authorizationresources} (role assignments and
 * definitions) needs the sensitive service {@code authorization} in the Cloud policy. Rows are checked against the
 * policy again when Azure answers ({@link ResourceGraph}).
 *
 * @param subscriptions the subscriptions of the request: the connection's, or the one the agent chose among them
 */
record DescribeRequest(String query, String id, List<String> subscriptions, int limit, String cursor) {

  static final int MAX_QUERY = 8_000;
  static final String DEFAULT_QUERY = "resources | project id, name, type, location, resourceGroup, tags";
  private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  static DescribeRequest of(JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    String query = text(args, "query");
    String id = text(args, "id");
    if (query != null && id != null) {
      throw new Refusal("bad_input", "Pass either query or id, not both.", null);
    }
    String subscription = text(args, "subscription");
    List<String> subscriptions = s.subscriptions();
    if (subscription != null) {
      if (!s.hasSubscription(subscription)) {
        throw new Refusal("subscription", "Subscription " + Settings.abbreviate(subscription) + " isn't one of this "
            + "connection's subscriptions.", "Use one of: " + String.join(", ", s.subscriptions()) + ".");
      }
      subscriptions = List.of(subscription.toLowerCase(Locale.ROOT));
    }
    int limit = args.path("limit").isNumber() ? args.path("limit").asInt() : Math.min(50, p.maxItems());
    limit = Math.max(1, Math.min(limit, p.maxItems()));
    String cursor = text(args, "cursor");
    if (id != null) {
      ResourceIds.Parsed parsed = ResourceIds.parse(id);
      if (parsed == null) {
        throw new Refusal("azure_path", "\"" + Settings.abbreviate(id) + "\" isn't a resource ID "
            + "(/subscriptions/<id>/resourceGroups/<group>/providers/<namespace>/<type>/<name>).", null);
      }
      if (!s.hasSubscription(parsed.subscription())) {
        throw new Refusal("subscription", "Subscription " + parsed.subscription() + " isn't one of this "
            + "connection's subscriptions.", null);
      }
      CallRequest.checkPolicy(parsed, p);
      return new DescribeRequest(null, id, List.of(parsed.subscription()), 1, null);
    }
    String q = query == null ? DEFAULT_QUERY : query;
    if (q.length() > MAX_QUERY) {
      throw new Refusal("query_too_long", "The query has " + q.length() + " characters; at most " + MAX_QUERY
          + " are accepted.", null);
    }
    if (q.stripLeading().startsWith(".")) {
      throw new Refusal("kql_command", "Control commands aren't queries.", "Start the query with a table, like "
          + "resources | where type =~ 'microsoft.web/sites'.");
    }
    if (mentions(q, "authorizationresources") && !p.serviceAllowed("authorization")) {
      throw new Refusal("sensitive_service", "The table authorizationresources holds role assignments, which are "
          + "shown only when the Cloud policy names authorization under Allowed services.", null);
    }
    return new DescribeRequest(q, null, subscriptions, limit, cursor);
  }

  boolean get() {
    return id != null;
  }

  /**
   * The query that goes to Resource Graph: the agent's, or for one resource {@code resources | where id =~ '…'}
   * ({@code resourcecontainers} for a subscription or a resource group).
   */
  String effectiveQuery() {
    if (id == null) {
      return query;
    }
    ResourceIds.Parsed p = ResourceIds.parse(id);
    String table = p != null && p.isResource() ? "resources" : "resourcecontainers";
    return table + " | where id =~ '" + id.replace("\\", "\\\\").replace("'", "\\'") + "' | limit 1";
  }

  /** The hash of what makes two calls the same query, for cursors. */
  String hash() {
    return Cursors.hash(effectiveQuery(), String.join(",", subscriptions), Integer.toString(limit));
  }

  ObjectNode payload() {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", "azure_describe");
    ObjectNode a = p.putObject("arguments");
    if (id != null) {
      a.put("id", id);
    } else {
      a.put("query", query);
      if (subscriptions.size() == 1) {
        a.put("subscription", subscriptions.getFirst());
      }
      a.put("limit", limit);
      if (cursor != null) {
        a.put("cursor", cursor);
      }
    }
    return p;
  }

  /** Whether {@code word} appears as a whole word, case-insensitively, anywhere in the text (strings too). */
  static boolean mentions(String text, String word) {
    Matcher m = WORD.matcher(text);
    while (m.find()) {
      if (m.group().equalsIgnoreCase(word)) {
        return true;
      }
    }
    return false;
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? null : n.asString().strip();
  }
}
