package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The checks every tool runs on its arguments, again after the core's schema validation: the same rules hold in
 * {@code classify}, {@code prepare} and {@code execute}, also for an approved payload. Each failure is a
 * {@link Refusal} that names the argument and says what would be accepted.
 */
final class Args {

  static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
  static final Pattern UUID = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  /** A Worker's name, as Cloudflare's schema writes it (at most 63 characters). */
  static final Pattern SCRIPT = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,62}");
  /** A Pages project's name (at most 58 characters). */
  static final Pattern PROJECT = Pattern.compile("[a-z0-9][a-z0-9-]{0,57}");
  /** A Workers Logs field, such as {@code $metadata.statusCode} or {@code $workers.event.request.url}. */
  static final Pattern FIELD = Pattern.compile("[$A-Za-z0-9_.\\[\\]-]{1,128}");

  private Args() {}

  /** A string argument, or {@code null} when it is absent. */
  static String string(ObjectNode args, String field, int max) throws Refusal {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isString()) {
      throw Refusal.badInput(field + " must be text.", null);
    }
    String v = n.asString();
    if (v.isEmpty()) {
      throw Refusal.badInput(field + " is empty.", null);
    }
    if (v.length() > max) {
      throw Refusal.badInput(field + " is longer than " + max + " characters.", null);
    }
    if (!OutputSanitizer.isClean(v)) {
      throw Refusal.badInput(field + " holds control or invisible characters.", null);
    }
    return v;
  }

  static String string(ObjectNode args, String field, Pattern pattern, String what) throws Refusal {
    String v = string(args, field, 1_024);
    if (v != null && !pattern.matcher(v).matches()) {
      throw Refusal.badInput("\"" + abbreviate(v) + "\" isn't " + what + ".", null);
    }
    return v;
  }

  static String required(ObjectNode args, String field, Pattern pattern, String what, String hint) throws Refusal {
    String v = string(args, field, pattern, what);
    if (v == null) {
      throw Refusal.badInput("The call names no " + field + ".", hint);
    }
    return v;
  }

  static String choice(ObjectNode args, String field, Set<String> values, String dflt) throws Refusal {
    String v = string(args, field, 64);
    if (v == null) {
      return dflt;
    }
    if (!values.contains(v)) {
      throw Refusal.badInput(field + " \"" + abbreviate(v) + "\" isn't one of " + String.join(", ",
          values.stream().sorted().toList()) + ".", null);
    }
    return v;
  }

  static Integer integer(ObjectNode args, String field, long min, long max) throws Refusal {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() < min || n.asLong() > max) {
      throw Refusal.badInput(field + " must be a whole number from " + min + " to " + max + ".", null);
    }
    return (int) n.asLong();
  }

  static Boolean bool(ObjectNode args, String field) throws Refusal {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isBoolean()) {
      throw Refusal.badInput(field + " must be true or false.", null);
    }
    return n.asBoolean();
  }

  /** A list of strings, each checked; {@code null} when absent. */
  static List<String> strings(ObjectNode args, String field, int maxItems, int maxLength) throws Refusal {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isArray() || n.isEmpty()) {
      throw Refusal.badInput(field + " must be a list of at least one item.", null);
    }
    if (n.size() > maxItems) {
      throw Refusal.badInput(field + " holds " + n.size() + " items; at most " + maxItems + " are allowed.",
          "Split it into several calls.");
    }
    List<String> out = new ArrayList<>();
    for (JsonNode item : n) {
      if (!item.isString() || item.asString().isEmpty() || item.asString().length() > maxLength
          || !OutputSanitizer.isClean(item.asString())) {
        throw Refusal.badInput("Each item of " + field + " must be text of 1 to " + maxLength + " characters.",
            null);
      }
      if (out.contains(item.asString())) {
        throw Refusal.badInput(field + " lists \"" + abbreviate(item.asString()) + "\" twice.", null);
      }
      out.add(item.asString());
    }
    return List.copyOf(out);
  }

  /** Refuses any argument the tool doesn't take, so a misspelled filter never widens a read or a change. */
  static void only(ObjectNode args, String tool, Set<String> allowed) throws Refusal {
    for (String name : args.propertyNames()) {
      if (!allowed.contains(name)) {
        throw Refusal.badInput(tool + " takes no argument " + abbreviate(name) + ".", null);
      }
    }
  }

  /** Arguments that only some views take: refused for the others, so they never look applied when they aren't. */
  static void notFor(ObjectNode args, String view, String... fields) throws Refusal {
    for (String f : fields) {
      if (args.hasNonNull(f)) {
        throw Refusal.badInput(f + " doesn't apply to view " + view + ".", null);
      }
    }
  }

  static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }

  static String abbreviate(String s) {
    if (s == null) {
      return "";
    }
    String clean = OutputSanitizer.sanitize(s);
    return clean.length() <= 80 ? clean : clean.substring(0, 79) + "…";
  }
}
