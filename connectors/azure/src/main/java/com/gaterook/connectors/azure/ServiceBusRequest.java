package com.gaterook.connectors.azure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call of {@code azure_servicebus_read} (peek the dead-letter queue of a queue or a topic subscription) or
 * {@code azure_servicebus_change} (resubmit dead-lettered messages by sequence number), checked without I/O. The
 * namespace is its Resource Manager ID, or its name in a subscription of the connection; requests go to the
 * namespace's own host with an Entra ID token for Service Bus.
 *
 * @param topicSubscription the topic's subscription whose dead letters these are, or {@code null} for a queue
 */
record ServiceBusRequest(String tool, Namespace namespace, String queue, String topic, String topicSubscription,
    int count, int maxBytes, List<Long> sequenceNumbers, int scan) {

  static final String READ = "azure_servicebus_read";
  static final String CHANGE = "azure_servicebus_change";
  static final int MAX_PEEK = 32;
  static final int DEFAULT_MESSAGE_BYTES = 4 * 1024;
  static final int MAX_MESSAGE_BYTES = 64 * 1024;
  static final int MAX_RESUBMIT = 32;
  static final int DEFAULT_SCAN = 100;
  static final int MAX_SCAN = 200;

  /** A Service Bus namespace of the connection: its Resource Manager ID, or its name and subscription. */
  record Namespace(String subscription, ResourceIds.Parsed id, String name) {
    String rawId() {
      return id == null ? null : "/" + String.join("/", id.segments());
    }
  }

  private static final Pattern NAMESPACE = Pattern.compile("^[a-z][a-z0-9-]{4,48}[a-z0-9]$");
  private static final Pattern ENTITY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,259}$");
  private static final Pattern SUBSCRIPTION_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$");

  static ServiceBusRequest of(String tool, JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    boolean change = CHANGE.equals(tool);
    Namespace ns = namespace(args, s, p);
    if (!p.serviceAllowed("servicebus")) {
      throw new Refusal("service_not_allowed", "The service servicebus isn't allowed: " + p.servicesText() + ".",
          null);
    }
    String queue = text(args, "queue");
    String topic = text(args, "topic");
    String sub = text(args, "topicSubscription");
    if ((queue == null) == (topic == null) || topic != null && sub == null || queue != null && sub != null) {
      throw new Refusal("bad_input", "Name either queue, or topic and topicSubscription.", null);
    }
    for (String name : new String[] {queue, topic}) {
      if (name != null && !ENTITY.matcher(name).matches()) {
        throw new Refusal("bad_input", "\"" + Settings.abbreviate(name) + "\" isn't a queue or topic name (letters, "
            + "digits, ., - and _).", null);
      }
    }
    if (sub != null && !SUBSCRIPTION_NAME.matcher(sub).matches()) {
      throw new Refusal("bad_input", "\"" + Settings.abbreviate(sub) + "\" isn't a subscription name.", null);
    }
    ServiceBusRequest probe = new ServiceBusRequest(tool, ns, queue, topic, sub, 0, 0, List.of(), 0);
    if (ns.id() != null) {
      ResourceIds.Parsed entity = ResourceIds.parse(probe.entityId(ns.rawId()));
      if (entity == null || !p.resourceAllowed(entity)) {
        throw new Refusal("resource_not_allowed", probe.entityText() + " of " + ns.name() + " isn't allowed by the "
            + "Cloud policy's resource masks on " + p.env().id() + ".", null);
      }
    }
    if (!change) {
      int count = integer(args, "count", 10, 1, MAX_PEEK);
      int maxBytes = integer(args, "maxBytes", DEFAULT_MESSAGE_BYTES, 1, MAX_MESSAGE_BYTES);
      return new ServiceBusRequest(READ, ns, queue, topic, sub, count, maxBytes, List.of(), 0);
    }
    Set<Long> numbers = new LinkedHashSet<>();
    for (JsonNode n : args.path("sequenceNumbers")) {
      if (!n.isIntegralNumber() || n.asLong() < 0) {
        throw new Refusal("bad_input", "sequenceNumbers are the sequence numbers azure_servicebus_read shows.", null);
      }
      numbers.add(n.asLong());
    }
    if (numbers.isEmpty() || numbers.size() > MAX_RESUBMIT) {
      throw new Refusal("bad_input", "Name 1 to " + MAX_RESUBMIT + " sequenceNumbers of dead-lettered messages, as "
          + "azure_servicebus_read shows them.", null);
    }
    int scan = integer(args, "scan", DEFAULT_SCAN, 1, MAX_SCAN);
    return new ServiceBusRequest(CHANGE, ns, queue, topic, sub, 0, 0, List.copyOf(numbers), scan);
  }

  private static Namespace namespace(JsonNode args, Settings s, AzurePolicy p) throws Refusal {
    String raw = text(args, "namespace");
    if (raw == null) {
      throw new Refusal("bad_input", "namespace is the Service Bus namespace's resource ID (/subscriptions/…/"
          + "providers/Microsoft.ServiceBus/namespaces/<name>) or its name.", null);
    }
    if (raw.startsWith("/")) {
      ResourceIds.Parsed id = ResourceIds.parse(raw);
      if (id == null || !"microsoft.servicebus/namespaces".equals(id.type()) || id.segments().size() != 8
          || id.resourceGroup() == null) {
        throw new Refusal("azure_path", "\"" + Settings.abbreviate(raw) + "\" isn't a Service Bus namespace's "
            + "resource ID.", null);
      }
      if (!s.hasSubscription(id.subscription())) {
        throw new Refusal("subscription", "Subscription " + id.subscription() + " isn't one of this connection's "
            + "subscriptions.", "Use one of: " + String.join(", ", s.subscriptions()) + ".");
      }
      String name = id.name().toLowerCase(Locale.ROOT);
      if (!NAMESPACE.matcher(name).matches()) {
        throw new Refusal("bad_input", "A namespace's name is 6 to 50 letters, digits and dashes.", null);
      }
      return new Namespace(id.subscription(), id, name);
    }
    String name = raw.toLowerCase(Locale.ROOT);
    if (!NAMESPACE.matcher(name).matches()) {
      throw new Refusal("bad_input", "namespace is the namespace's resource ID or its name (6 to 50 letters, digits "
          + "and dashes).", null);
    }
    String subscription = text(args, "subscription");
    if (subscription == null && s.subscriptions().size() == 1) {
      subscription = s.subscriptions().getFirst();
    }
    if (subscription == null || !s.hasSubscription(subscription)) {
      throw new Refusal("subscription", subscription == null ? "Name the namespace's subscription, or pass its "
          + "resource ID." : "Subscription " + subscription + " isn't one of this connection's subscriptions.",
          "Use one of: " + String.join(", ", s.subscriptions()) + ".");
    }
    if (!p.resources().isEmpty()) {
      throw new Refusal("resource_not_allowed", "The Cloud policy on " + p.env().id() + " allows some resources "
          + "only: pass the namespace's resource ID, so GateRook can check it.", null);
    }
    return new Namespace(subscription.toLowerCase(Locale.ROOT), null, name);
  }

  boolean read() {
    return READ.equals(tool);
  }

  /** {@code <namespace ID>/queues/<queue>} or {@code …/topics/<topic>/subscriptions/<name>}. */
  String entityId(String namespaceId) {
    return namespaceId + (queue != null ? "/queues/" + queue : "/topics/" + topic + "/subscriptions/"
        + topicSubscription);
  }

  /** The entity's path on the namespace's host, encoded: {@code /orders} or {@code /events/subscriptions/billing}. */
  String entityPath() {
    return queue != null ? "/" + ResourceIds.encode(queue) : "/" + ResourceIds.encode(topic) + "/subscriptions/"
        + ResourceIds.encode(topicSubscription);
  }

  /** Where resubmitted messages go: the queue, or the topic (and so every subscription whose rules match). */
  String sendPath() {
    return "/" + ResourceIds.encode(queue != null ? queue : topic);
  }

  /** {@code queue orders}, {@code subscription billing of topic events}, for texts. */
  String entityText() {
    return queue != null ? "Queue " + queue : "Subscription " + topicSubscription + " of topic " + topic;
  }

  String where() {
    return namespace.name() + "/" + (queue != null ? queue : topic + "/" + topicSubscription);
  }

  /** The canonical form an approval stores and executes. */
  ObjectNode payload() {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", tool);
    ObjectNode a = p.putObject("arguments");
    a.put("namespace", namespace.rawId() != null ? namespace.rawId() : namespace.name());
    if (namespace.rawId() == null) {
      a.put("subscription", namespace.subscription());
    }
    if (queue != null) {
      a.put("queue", queue);
    } else {
      a.put("topic", topic);
      a.put("topicSubscription", topicSubscription);
    }
    if (read()) {
      a.put("count", count);
      a.put("maxBytes", maxBytes);
    } else {
      ArrayNode n = a.putArray("sequenceNumbers");
      sequenceNumbers.forEach(n::add);
      a.put("scan", scan);
    }
    return p;
  }

  List<String> numbersText() {
    List<String> out = new ArrayList<>();
    sequenceNumbers.forEach(n -> out.add(String.valueOf(n)));
    return out;
  }

  private static int integer(JsonNode args, String field, int dflt, int min, int max) throws Refusal {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return dflt;
    }
    if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
      throw new Refusal("bad_input", field + " is " + min + " to " + max + ".", null);
    }
    return n.asInt();
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? null : n.asString().strip();
  }
}
