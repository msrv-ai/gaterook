package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A runbook as it runs (report 06 §4): a fixed command whose only variable parts are typed parameters. Built by
 * {@link RunbookCompiler} from a template's definition; immutable.
 *
 * <p>{@link #render} turns the agent's values into the exact line the host gets: every value is checked against its
 * type first, then quoted (an enum, an integer, a string) or replaced by the author's literal (a boolean), and an
 * absent optional parameter disappears together with the space before it ({@code docker compose ps} rather than
 * {@code docker compose ps ''}).
 *
 * @param readOnly    true only when the person confirmed it for exactly this command (see {@link RunbookCompiler})
 * @param freeText    a string parameter: the agent chooses the bytes, so {@code ssh_run} is a full channel out
 * @param timeoutSeconds the runbook's own limit, or 0 for the policy's
 */
record Runbook(String name, String description, boolean readOnly, String command, String workdir, int timeoutSeconds,
    List<Param> params, List<CommandLexer.Segment> segments, boolean freeText) {

  /** One typed parameter. */
  record Param(String name, Type type, boolean required, List<String> values, long min, long max, Pattern pattern,
      int maxLength, boolean allowLeadingDash, String whenTrue, String whenFalse, JsonNode defaultValue) {

    enum Type {
      ENUM("enum"), INT("int"), BOOL("bool"), STRING("string");

      final String id;

      Type(String id) {
        this.id = id;
      }

      static Type of(String id) {
        for (Type t : values()) {
          if (t.id.equals(id)) {
            return t;
          }
        }
        return null;
      }
    }

    /** {@code service?: api|worker} — for descriptions, where clients ignore if/then. */
    String signature() {
      String optional = required ? "" : "?";
      return name + optional + switch (type) {
        case ENUM -> ": " + String.join("|", values);
        case INT -> ": " + min + "–" + max;
        case BOOL -> ": true|false";
        case STRING -> ": text";
      };
    }

    /** The JSON Schema of the value. */
    ObjectNode schema() {
      ObjectNode s = JsonNodeFactory.instance.objectNode();
      switch (type) {
        case ENUM -> {
          ArrayNode e = s.putArray("enum");
          values.forEach(e::add);
        }
        case INT -> s.put("type", "integer").put("minimum", min).put("maximum", max);
        case BOOL -> s.put("type", "boolean");
        case STRING -> s.put("type", "string").put("maxLength", maxLength).put("pattern", pattern.pattern());
      }
      if (defaultValue != null) {
        s.set("default", defaultValue);
      }
      return s;
    }
  }

  /** What went wrong with the agent's values; the text goes to the agent as the reason of a denial. */
  static final class InvalidParams extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidParams(String message) {
      super(message, null, false, false);
    }
  }

  /** The values to run with: each checked, defaults applied, absent optionals left out. */
  Map<String, JsonNode> values(JsonNode given) throws InvalidParams {
    if (given != null && !given.isNull() && !given.isObject()) {
      throw new InvalidParams("params must be an object of " + name + "'s parameters");
    }
    Map<String, Param> byName = new LinkedHashMap<>();
    params.forEach(p -> byName.put(p.name(), p));
    if (given != null && given.isObject()) {
      for (String key : given.propertyNames()) {
        if (!byName.containsKey(key)) {
          throw new InvalidParams(key + " is not a parameter of " + name + ". " + parameterList());
        }
      }
    }
    Map<String, JsonNode> out = new LinkedHashMap<>();
    for (Param p : params) {
      JsonNode v = given == null || given.isNull() ? null : given.get(p.name());
      if (v == null || v.isNull()) {
        if (p.defaultValue() != null) {
          out.put(p.name(), p.defaultValue());
        } else if (p.required()) {
          throw new InvalidParams(p.name() + " is required. " + parameterList());
        }
        continue;
      }
      check(p, v);
      out.put(p.name(), v);
    }
    return out;
  }

  /** The exact command line for these values (already checked by {@link #values}). */
  String render(Map<String, JsonNode> values, boolean remoteDeadline, int remoteSeconds) {
    StringBuilder line = new StringBuilder();
    for (CommandLexer.Segment s : segments) {
      switch (s) {
        case CommandLexer.Text t -> line.append(t.text());
        case CommandLexer.Placeholder p -> {
          String word = word(param(p.name()), values.get(p.name()));
          if (word.isEmpty()) {
            // An absent value takes the space before it along: "ps {service}" without a service is "ps".
            if (!line.isEmpty() && line.charAt(line.length() - 1) == ' ') {
              line.setLength(line.length() - 1);
            }
          } else {
            line.append(word);
          }
        }
      }
    }
    String command = workdir == null ? line.toString() : "cd " + ShellQuote.quote(workdir) + " && " + line;
    if (remoteDeadline) {
      // The host stops it on its own, even if this computer goes offline (report 06 §9.2): TERM, KILL 5 s later.
      return "timeout -k 5 " + remoteSeconds + " sh -c " + ShellQuote.quote(command);
    }
    return command;
  }

  /** {@code compose-logs(service: api|worker, lines?: 1–500)}. */
  String signature() {
    List<String> parts = new ArrayList<>();
    params.forEach(p -> parts.add(p.signature()));
    return name + "(" + String.join(", ", parts) + ")";
  }

  /** The JSON Schema of {@code params} for this runbook. */
  ObjectNode paramsSchema() {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("type", "object").put("additionalProperties", false);
    ObjectNode properties = s.putObject("properties");
    ArrayNode required = JsonNodeFactory.instance.arrayNode();
    for (Param p : params) {
      properties.set(p.name(), p.schema());
      if (p.required() && p.defaultValue() == null) {
        required.add(p.name());
      }
    }
    if (!required.isEmpty()) {
      s.set("required", required);
    }
    return s;
  }

  boolean hasRequiredParams() {
    return params.stream().anyMatch(p -> p.required() && p.defaultValue() == null);
  }

  Param param(String parameter) {
    for (Param p : params) {
      if (p.name().equals(parameter)) {
        return p;
      }
    }
    throw new IllegalStateException("No parameter " + parameter);
  }

  private String parameterList() {
    if (params.isEmpty()) {
      return name + " has no parameters.";
    }
    Set<String> names = new TreeSet<>();
    params.forEach(p -> names.add(p.signature()));
    return "Parameters: " + String.join(", ", names) + ".";
  }

  private static String word(Param p, JsonNode v) {
    if (v == null) {
      return "";
    }
    return switch (p.type()) {
      case BOOL -> v.asBoolean() ? p.whenTrue() : p.whenFalse();
      case INT -> ShellQuote.quote(Long.toString(v.asLong()));
      case ENUM, STRING -> ShellQuote.quote(v.asString());
    };
  }

  private void check(Param p, JsonNode v) throws InvalidParams {
    switch (p.type()) {
      case ENUM -> {
        if (!v.isString() || !p.values().contains(v.asString())) {
          throw new InvalidParams(p.name() + " must be one of " + String.join(", ", p.values()));
        }
      }
      case INT -> {
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < p.min() || v.asLong() > p.max()) {
          throw new InvalidParams(p.name() + " must be an integer " + p.min() + ".." + p.max());
        }
      }
      case BOOL -> {
        if (!v.isBoolean()) {
          throw new InvalidParams(p.name() + " must be true or false");
        }
      }
      case STRING -> {
        if (!v.isString()) {
          throw new InvalidParams(p.name() + " must be a string");
        }
        String s = v.asString();
        if (s.length() > p.maxLength()) {
          throw new InvalidParams(p.name() + " must be at most " + p.maxLength() + " characters");
        }
        if (s.indexOf('\n') >= 0 || s.indexOf('\t') >= 0 || !OutputSanitizer.isClean(s)) {
          throw new InvalidParams(p.name() + " has a control or invisible character");
        }
        if (!p.allowLeadingDash() && s.startsWith("-")) {
          throw new InvalidParams(p.name() + " can't start with -: it would reach the command as an option");
        }
        if (!p.pattern().matcher(s).matches()) {
          throw new InvalidParams(p.name() + " must match " + p.pattern().pattern());
        }
      }
    }
  }

  /** The normalized definition, as the template contributes it to {@code ssh_run}. */
  ObjectNode toJson() {
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    o.put("name", name);
    if (description != null) {
      o.put("description", description);
    }
    o.put("readOnly", readOnly);
    o.put("freeText", freeText);
    o.put("command", command);
    if (workdir != null) {
      o.put("workdir", workdir);
    }
    if (timeoutSeconds > 0) {
      o.put("timeoutSeconds", timeoutSeconds);
    }
    ArrayNode ps = o.putArray("params");
    for (Param p : params) {
      ObjectNode j = ps.addObject();
      j.put("name", p.name()).put("type", p.type().id);
      if (p.required()) {
        j.put("required", true);
      }
      switch (p.type()) {
        case ENUM -> {
          ArrayNode vs = j.putArray("values");
          p.values().forEach(vs::add);
        }
        case INT -> j.put("min", p.min()).put("max", p.max());
        case BOOL -> j.put("whenTrue", p.whenTrue()).put("whenFalse", p.whenFalse());
        case STRING -> {
          j.put("pattern", p.pattern().pattern()).put("maxLength", p.maxLength());
          if (p.allowLeadingDash()) {
            j.put("allowLeadingDash", true);
          }
        }
      }
      if (p.defaultValue() != null) {
        j.set("default", p.defaultValue());
      }
    }
    return o;
  }
}
