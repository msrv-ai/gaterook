package com.gaterook.connectors.prometheus;

import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.OutputSanitizer;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answers of the listing tools (series, label names and values, metadata, targets, rules, alerts): structured
 * content and the same as text, within the call's response budget. Items beyond the row limit or the budget go from
 * the end, and the answer says so. Every string the server sent is sanitized and cut to the endpoint's cell size.
 */
final class ListAnswers {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  /** Longest text of one item's free text (help, an error, an annotation) on its line. */
  static final int TEXT_FIELD = 500;

  private ListAnswers() {}

  /** One item: its structured form and its text. */
  record Item(JsonNode node, String text) {}

  /** The first line of a listing for the items shown: how many, and whether the server has more. */
  @FunctionalInterface
  interface Header {
    String of(int shown, boolean more);
  }

  /**
   * The parts of a listing.
   *
   * @param key    the structured content's list ({@code series}, {@code labels}, …)
   * @param inline whether the items' texts share one line, comma separated (names), or take one line each
   * @param more   whether the server had more than the items given (its limit, the bounds of the read)
   */
  record Listing(String key, Header header, List<Item> items, boolean inline, boolean more, ObjectNode extra,
      List<String> notes) {}

  // ── The tools ─────────────────────────────────────────────────────────────────────────────────────────────

  static ToolResult series(PromJson.Tree t, Plan plan, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<Item> items = new ArrayList<>();
    for (JsonNode labels : t.data()) {
      if (labels.isObject()) {
        ObjectNode l = labels(labels, shape.maxCellChars());
        items.add(new Item(l, QueryAnswers.labelsText(l)));
      }
    }
    List<String> notes = new ArrayList<>();
    if (items.isEmpty()) {
      notes.add("No series matched in the window. Widen it with start, or check the selectors' labels with "
          + "prometheus_label_values.");
    }
    String matching = String.join(" or ", strings(plan.arguments.path("match")));
    String window = Plan.iso(plan.start) + " → " + Plan.iso(plan.end);
    return fit(new Listing("series", (n, more) -> count(n, more, "series", "series") + " matching " + matching
        + " · " + window, items, false, more(t, t.data()), window(plan), notes), t, shape, s);
  }

  static ToolResult labels(PromJson.Tree t, Plan plan, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<Item> items = names(t.data(), shape);
    String window = Plan.iso(plan.start) + " → " + Plan.iso(plan.end);
    List<String> notes = new ArrayList<>();
    if (items.isEmpty()) {
      notes.add("No labels in the window. Widen it with start.");
    }
    return fit(new Listing("labels", (n, more) -> count(n, more, "label name", "label names") + " · " + window,
        items, true, more(t, t.data()), window(plan), notes), t, shape, s);
  }

  static ToolResult labelValues(PromJson.Tree t, Plan plan, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<Item> items = names(t.data(), shape);
    String label = QueryReader.cell(plan.label, shape.maxCellChars());
    String window = Plan.iso(plan.start) + " → " + Plan.iso(plan.end);
    List<String> notes = new ArrayList<>();
    if (items.isEmpty()) {
      notes.add("No series in the window has the label " + label + ". List the label names with prometheus_labels.");
    }
    return fit(new Listing("values", (n, more) -> count(n, more, "value", "values") + " of " + label + " · "
        + window, items, true, more(t, t.data()), window(plan).put("label", label), notes), t, shape, s);
  }

  static ToolResult metadata(PromJson.Tree t, Plan plan, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<Item> items = new ArrayList<>();
    JsonNode data = t.data();
    for (Map.Entry<String, JsonNode> e : data.properties()) {
      String metric = QueryReader.cell(e.getKey(), shape.maxCellChars());
      for (JsonNode m : e.getValue()) {
        ObjectNode item = F.objectNode().put("metric", metric)
            .put("type", QueryReader.cell(m.path("type").asString(""), 64))
            .put("help", QueryReader.cell(m.path("help").asString(""), shape.maxCellChars()))
            .put("unit", QueryReader.cell(m.path("unit").asString(""), 64));
        StringBuilder text = new StringBuilder(metric).append(" · ").append(item.path("type").asString());
        if (!item.path("unit").asString().isEmpty()) {
          text.append(" · unit ").append(item.path("unit").asString());
        }
        if (!item.path("help").asString().isEmpty()) {
          text.append(" · ").append(cut(item.path("help").asString(), TEXT_FIELD));
        }
        items.add(new Item(item, text.toString()));
      }
    }
    List<String> notes = new ArrayList<>();
    if (items.isEmpty()) {
      notes.add(plan.arguments.has("metric") ? "The server has no metadata of this metric: no target exposes it, or "
          + "it is a recording rule's." : "The server has no metric metadata.");
    }
    return fit(new Listing("metrics", (n, more) -> count(n, more, "metric description", "metric descriptions"),
        items, false, more(t, data), F.objectNode(), notes), t, shape, s);
  }

  static ToolResult targets(PromJson.Tree t, Plan plan, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<Item> items = new ArrayList<>();
    JsonNode active = t.data().path("activeTargets");
    for (JsonNode target : active) {
      ObjectNode labels = labels(target.path("labels"), shape.maxCellChars());
      String h = QueryReader.cell(target.path("health").asString("unknown"), 32);
      ObjectNode item = F.objectNode()
          .put("scrapePool", QueryReader.cell(target.path("scrapePool").asString(""), shape.maxCellChars()))
          .put("job", labels.path("job").asString(""))
          .put("instance", labels.path("instance").asString(""))
          .put("health", h)
          .put("lastError", QueryReader.cell(target.path("lastError").asString(""), shape.maxCellChars()))
          .put("lastScrape", QueryReader.cell(target.path("lastScrape").asString(""), 64));
      if (target.path("lastScrapeDuration").isNumber()) {
        item.set("lastScrapeDurationSeconds", target.path("lastScrapeDuration"));
      }
      item.put("scrapeInterval", QueryReader.cell(target.path("scrapeInterval").asString(""), 32))
          .put("scrapeTimeout", QueryReader.cell(target.path("scrapeTimeout").asString(""), 32))
          .put("scrapeUrl", QueryReader.cell(withoutQuery(target.path("scrapeUrl").asString("")),
              shape.maxCellChars()));
      item.set("labels", labels);
      StringBuilder text = new StringBuilder(item.path("job").asString()).append(' ')
          .append(item.path("instance").asString()).append(" · ").append(h);
      if (!item.path("lastScrape").asString().isEmpty()) {
        text.append(" · last scrape ").append(item.path("lastScrape").asString());
        if (item.has("lastScrapeDurationSeconds")) {
          text.append(" (").append(Math.round(item.path("lastScrapeDurationSeconds").asDouble() * 1000))
              .append(" ms)");
        }
      }
      text.append(" · ").append(item.path("scrapeUrl").asString());
      if (!item.path("lastError").asString().isEmpty()) {
        text.append("\n  error: ").append(cut(item.path("lastError").asString(), TEXT_FIELD));
      }
      items.add(new Item(item, text.toString()));
    }
    JsonNode dropped = t.data().path("droppedTargets");
    for (JsonNode target : dropped) {
      ObjectNode item = F.objectNode().put("dropped", true);
      ObjectNode labels = labels(target.path("discoveredLabels"), shape.maxCellChars());
      if (target.path("scrapePool").isString()) {
        item.put("scrapePool", QueryReader.cell(target.path("scrapePool").asString(), shape.maxCellChars()));
      }
      item.set("discoveredLabels", labels);
      items.add(new Item(item, "dropped · " + QueryAnswers.labelsText(labels)));
    }
    // What the shown targets are: 2 active targets: 1 down, 1 up · 3 dropped targets.
    Header header = (n, more) -> {
      Map<String, Integer> health = new LinkedHashMap<>();
      int activeShown = 0;
      int droppedShown = 0;
      for (int i = 0; i < n; i++) {
        JsonNode node = items.get(i).node();
        if (node.path("dropped").asBoolean(false)) {
          droppedShown++;
        } else {
          activeShown++;
          health.merge(node.path("health").asString(), 1, Integer::sum);
        }
      }
      StringBuilder h = new StringBuilder();
      if (!plan.state.equals("dropped")) {
        h.append(count(activeShown, more && plan.state.equals("active"), "active target", "active targets"));
        List<String> parts = new ArrayList<>();
        health.forEach((state, k) -> parts.add(k + " " + state));
        if (!parts.isEmpty()) {
          h.append(": ").append(String.join(", ", parts));
        }
      }
      if (!plan.state.equals("active")) {
        h.append(h.isEmpty() ? "" : " · ").append(count(droppedShown, more, "dropped target", "dropped targets"));
      }
      return h.toString();
    };
    ObjectNode extra = F.objectNode().put("state", plan.state);
    JsonNode counts = t.data().path("droppedTargetCounts");
    if (counts.isObject() && !counts.isEmpty()) {
      ObjectNode c = extra.putObject("droppedByPool");
      counts.properties().forEach(e -> {
        if (c.size() < 200) {
          c.set(QueryReader.cell(e.getKey(), 256), e.getValue());
        }
      });
    }
    List<String> notes = new ArrayList<>();
    if (items.isEmpty()) {
      notes.add(plan.state.equals("dropped") ? "Relabeling dropped no target." : "The server scrapes no targets"
          + (plan.arguments.has("scrapePool") ? " of this pool" : "") + ": it may receive its data by remote write.");
    }
    return fit(new Listing("targets", header, items, false, more(t, active) || more(t, dropped), extra, notes), t,
        shape, s);
  }

  static ToolResult rules(PromJson.Tree t, Plan plan, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<Item> items = new ArrayList<>();
    boolean more = more(t, t.data().path("groups"));
    for (JsonNode group : t.data().path("groups")) {
      String name = QueryReader.cell(group.path("name").asString(""), shape.maxCellChars());
      String file = QueryReader.cell(group.path("file").asString(""), shape.maxCellChars());
      more |= more(t, group.path("rules"));
      for (JsonNode rule : group.path("rules")) {
        boolean alert = rule.path("type").asString("").equals("alerting");
        ObjectNode item = F.objectNode().put("group", name).put("file", file)
            .put("type", QueryReader.cell(rule.path("type").asString(""), 32))
            .put("name", QueryReader.cell(rule.path("name").asString(""), shape.maxCellChars()))
            .put("query", QueryReader.cell(rule.path("query").asString(""), shape.maxCellChars()))
            .put("health", QueryReader.cell(rule.path("health").asString(""), 32))
            .put("lastError", QueryReader.cell(rule.path("lastError").asString(""), shape.maxCellChars()))
            .put("lastEvaluation", QueryReader.cell(rule.path("lastEvaluation").asString(""), 64));
        if (alert) {
          item.put("state", QueryReader.cell(rule.path("state").asString(""), 32));
          if (rule.path("duration").isNumber()) {
            item.set("forSeconds", rule.path("duration"));
          }
          item.set("annotations", labels(rule.path("annotations"), shape.maxCellChars()));
        }
        item.set("labels", labels(rule.path("labels"), shape.maxCellChars()));
        StringBuilder text = new StringBuilder(alert ? "alert " : "record ").append(item.path("name").asString());
        if (alert) {
          text.append(" [").append(item.path("state").asString()).append(']');
          long forMs = Math.round(item.path("forSeconds").asDouble(0) * 1000);
          if (forMs > 0) {
            text.append(" · for ").append(Durations.human(Duration.ofMillis(forMs)));
          }
        }
        text.append(" · group ").append(name).append(" · health ").append(item.path("health").asString());
        text.append("\n  expr: ").append(cut(item.path("query").asString(), 2_000));
        if (!item.path("lastError").asString().isEmpty()) {
          text.append("\n  error: ").append(cut(item.path("lastError").asString(), TEXT_FIELD));
        }
        items.add(new Item(item, text.toString()));
      }
    }
    List<String> notes = new ArrayList<>();
    if (items.isEmpty()) {
      notes.add("The server evaluates no rules" + (plan.params.size() > 1 ? " that match the filters." : "."));
    }
    Header header = (n, m) -> {
      int alerting = 0;
      for (int i = 0; i < n; i++) {
        alerting += items.get(i).node().path("type").asString("").equals("alerting") ? 1 : 0;
      }
      return count(n, m, "rule", "rules") + (n == 0 ? "" : ": " + alerting + " alerting, " + (n - alerting)
          + " recording");
    };
    return fit(new Listing("rules", header, items, false, more, F.objectNode(), notes), t, shape, s);
  }

  static ToolResult alerts(PromJson.Tree t, Plan plan, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<Item> items = new ArrayList<>();
    JsonNode all = t.data().path("alerts");
    for (JsonNode alert : all) {
      String state = QueryReader.cell(alert.path("state").asString(""), 32);
      if (!plan.state.equals("any") && !plan.state.equals(state)) {
        continue;
      }
      ObjectNode labels = labels(alert.path("labels"), shape.maxCellChars());
      ObjectNode annotations = labels(alert.path("annotations"), shape.maxCellChars());
      ObjectNode item = F.objectNode();
      item.set("labels", labels);
      item.set("annotations", annotations);
      item.put("state", state).put("activeAt", QueryReader.cell(alert.path("activeAt").asString(""), 64))
          .put("value", QueryReader.cell(PromJson.value(alert.path("value")), 64));
      StringBuilder text = new StringBuilder(QueryAnswers.labelsText(labels)).append(" · ").append(state);
      if (!item.path("activeAt").asString().isEmpty()) {
        text.append(" since ").append(item.path("activeAt").asString());
      }
      text.append(" · value ").append(item.path("value").asString());
      String about = annotations.path("summary").asString(annotations.path("description").asString(""));
      if (!about.isEmpty()) {
        text.append("\n  ").append(cut(about, TEXT_FIELD));
      }
      items.add(new Item(item, text.toString()));
    }
    List<String> notes = new ArrayList<>();
    if (items.isEmpty()) {
      notes.add(plan.state.equals("any") ? "No alert is pending or firing." : "No alert is " + plan.state + ".");
    }
    Header header = (n, more) -> {
      Map<String, Integer> states = new LinkedHashMap<>();
      for (int i = 0; i < n; i++) {
        states.merge(items.get(i).node().path("state").asString(""), 1, Integer::sum);
      }
      List<String> parts = new ArrayList<>();
      states.forEach((state, k) -> parts.add(k + " " + state));
      return count(n, more, "alert", "alerts") + (parts.isEmpty() ? "" : ": " + String.join(", ", parts));
    };
    return fit(new Listing("alerts", header, items, false, more(t, all), F.objectNode().put("state", plan.state),
        notes), t, shape, s);
  }

  // ── Shared ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The answer of a listing within the row limit and the budget: whole items go from the end, the cut is said, and
   * the text shows exactly the items of the structured content.
   */
  static ToolResult fit(Listing l, PromJson.Tree t, QueryAnswers.Shape shape, PrometheusSettings s) {
    List<String> notes = new ArrayList<>(l.notes());
    String reason = null;
    int n = l.items().size();
    if (n > shape.rows()) {
      n = shape.rows();
      reason = "row_limit";
      notes.add("More than " + n + " " + l.key() + ": the first " + n + " are shown. Narrow the request.");
    } else if (l.more()) {
      reason = "row_limit";
      notes.add("The server has more " + l.key() + " than the " + n + " shown. Narrow the request.");
    }
    if (t.cut() && reason == null) {
      reason = "size_limit";
      notes.add(QueryAnswers.bodyCut(s));
    }
    List<String> warnings = new ArrayList<>(t.warnings());
    if (reason != null) {
      // The server's own words for the limit GateRook asked it for say what the notes say.
      warnings.removeIf(QueryAnswers::limitWarning);
    }
    long fixed = 600 + 2 * (QueryAnswers.utf8(l.header().of(n, true)) + notes.stream().mapToLong(QueryAnswers::utf8)
        .sum() + warnings.stream().mapToLong(QueryAnswers::utf8).sum()) + 2 * QueryAnswers.utf8(l.extra()
        .toString());
    long[] prefix = new long[n + 1];
    for (int i = 0; i < n; i++) {
      Item item = l.items().get(i);
      prefix[i + 1] = prefix[i] + QueryAnswers.utf8(item.node().toString()) + QueryAnswers.utf8(item.text()) + 4;
    }
    int keep = n;
    boolean sizeCut = false;
    while (keep > 0 && fixed + prefix[keep] > shape.budget()) {
      keep--;
      sizeCut = true;
    }
    // Ends: every pass that doesn't fit keeps fewer items, down to none.
    while (true) {
      List<String> allNotes = new ArrayList<>(notes);
      String why = reason;
      if (sizeCut) {
        why = "size_limit";
        allNotes.add("The answer was cut to the size limit: " + (n - keep) + " more " + l.key() + " are left out. "
            + "Narrow the request.");
      }
      boolean more = l.more() || keep < l.items().size();
      ObjectNode out = F.objectNode();
      out.setAll(l.extra());
      ArrayNode list = out.putArray(l.key());
      StringBuilder text = new StringBuilder(l.header().of(keep, more));
      StringBuilder line = new StringBuilder();
      for (int i = 0; i < keep; i++) {
        Item item = l.items().get(i);
        list.add(item.node());
        if (l.inline()) {
          line.append(line.isEmpty() ? "" : ", ").append(item.text());
        } else {
          text.append('\n').append(item.text());
        }
      }
      if (l.inline() && !line.isEmpty()) {
        text.append('\n').append(line);
      }
      out.put("count", keep);
      out.put("more", more);
      out.put("truncated", why != null);
      if (why != null) {
        out.put("truncatedReason", why);
      }
      ArrayNode w = out.putArray("warnings");
      warnings.forEach(w::add);
      ArrayNode notesNode = out.putArray("notes");
      allNotes.forEach(notesNode::add);
      for (String warning : warnings) {
        text.append("\nServer warning: ").append(warning);
      }
      for (String note : allNotes) {
        text.append('\n').append(note);
      }
      text.append('\n').append(QueryAnswers.FOOTER);
      String rendered = OutputSanitizer.sanitize(text.toString());
      if (keep == 0 || QueryAnswers.utf8(rendered) + QueryAnswers.utf8(out.toString()) <= shape.budget()) {
        ToolResult r = ToolResult.json(out, rendered);
        return why == null ? r : r.truncated(why);
      }
      keep = keep * 4 / 5;
      sizeCut = true;
    }
  }

  /** Whether the read left items of {@code container} out (the server sent more than the bounds keep). */
  private static boolean more(PromJson.Tree t, JsonNode container) {
    return t.total(container) > container.size();
  }

  private static List<Item> names(JsonNode data, QueryAnswers.Shape shape) {
    List<Item> items = new ArrayList<>();
    for (JsonNode v : data) {
      if (v.isString()) {
        String name = QueryReader.cell(v.asString(), shape.maxCellChars());
        items.add(new Item(F.stringNode(name), name));
      }
    }
    return items;
  }

  /** A label set or annotations: names and values sanitized, values cut to the cell size. */
  static ObjectNode labels(JsonNode labels, int maxCellChars) {
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> e : labels.properties()) {
      if (out.size() >= QueryReader.MAX_LABELS) {
        break;
      }
      out.put(QueryReader.cell(e.getKey(), 256), QueryReader.cell(e.getValue().asString(e.getValue().toString()),
          maxCellChars));
    }
    return out;
  }

  private static ObjectNode window(Plan plan) {
    return F.objectNode().put("start", Plan.iso(plan.start)).put("end", Plan.iso(plan.end));
  }

  private static String count(int n, boolean more, String one, String many) {
    return n + (more ? "+" : "") + " " + (n == 1 && !more ? one : many);
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : array) {
      out.add(cut(OutputSanitizer.sanitize(v.asString("")), 200));
    }
    return out;
  }

  /** A scrape URL without its query, fragment or user: parameters of a scrape config may hold a token. */
  static String withoutQuery(String url) {
    try {
      URI u = URI.create(url);
      if (u.getScheme() == null || u.getRawAuthority() == null) {
        return "";
      }
      String host = u.getHost() == null ? "" : u.getHost();
      return u.getScheme() + "://" + host + (u.getPort() < 0 ? "" : ":" + u.getPort())
          + (u.getRawPath() == null ? "" : u.getRawPath());
    } catch (IllegalArgumentException e) {
      // Not a URI GateRook can take apart: nothing of it is shown rather than something with a credential in it.
      return "";
    }
  }

  static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
