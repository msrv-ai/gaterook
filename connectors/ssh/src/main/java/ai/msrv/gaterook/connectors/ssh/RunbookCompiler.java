package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Checks a runbook definition when its template is saved (report 06 §4, ARCHITECTURE.md §9 "Command injection" and
 * "READ is a declaration"): what may vary is typed, every placeholder stands outside quotes, and read only is a person's
 * statement about one exact command.
 *
 * <pre>{"name":"compose-logs","description":"…","readOnly":true,"readOnlyConfirmedFor":"docker compose logs …",
 *  "command":"docker compose logs --tail {lines} {service}","workdir":"/srv/app","timeoutSeconds":20,
 *  "params":[{"name":"service","type":"enum","values":["api","worker"],"required":true},
 *            {"name":"lines","type":"int","min":1,"max":500,"default":100}]}</pre>
 *
 * <p><b>Read only.</b> A read-only runbook runs without approval, so the flag counts only when
 * {@code readOnlyConfirmedFor} repeats the command byte for byte: the editor writes it when the person ticks "I
 * confirm this runbook doesn't change state". A new version with another command keeps the old confirmation, which
 * no longer matches, so it runs with approval until someone confirms again; a template imported without it never
 * starts read only. Commands that look like changes ({@code restart}, {@code rm}, {@code sudo}, {@code >}) say so in a
 * warning even when confirmed.
 */
final class RunbookCompiler {

  static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,47}$");
  private static final Pattern PARAM_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
  private static final Pattern LITERAL = Pattern.compile("^[A-Za-z0-9_=.,:/@%+-]*$");
  private static final int MAX_COMMAND = 2000;
  private static final int MAX_PARAMS = 16;
  private static final int MAX_VALUES = 64;
  private static final int MAX_STRING = 256;
  private static final int MAX_TIMEOUT = 600;
  private static final Set<String> CHANGING_WORDS = Set.of("restart", "stop", "start", "reload", "rm", "rmdir", "kill",
      "pkill", "killall", "up", "down", "reboot", "shutdown", "halt", "poweroff", "mv", "dd", "mkfs", "chmod", "chown",
      "truncate", "sudo", "su", "apply", "delete", "drop", "install", "remove", "purge", "enable", "disable", "mask",
      "tee", "scale", "prune", "rollout", "set", "unset", "write", "push", "deploy");
  private static final Set<String> ENDLESS_WORDS = Set.of("-f", "--follow", "watch", "-F");
  private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_.-]+");
  private static final Pattern REDIRECT = Pattern.compile("(?<![0-9&<>])>>?(?!&)|[0-9]>>?(?!&)");

  /** A definition checked: a runbook if it has no errors. */
  record Outcome(Runbook runbook, List<String> errors, List<String> warnings) {
    boolean ok() {
      return errors.isEmpty();
    }
  }

  private RunbookCompiler() {}

  /** A definition from the template editor or the catalog. */
  static Outcome compile(JsonNode definition) {
    return parse(definition, false);
  }

  /**
   * A runbook back from its contribution ({@link Runbook#toJson}): the same checks, its read only as compiled. The
   * core compiles every contribution from a signed template row with {@link #compile}, so the flag is ours.
   */
  static Outcome fromContribution(JsonNode contribution) {
    return parse(contribution, true);
  }

  private static Outcome parse(JsonNode d, boolean compiledReadOnly) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (d == null || !d.isObject()) {
      return new Outcome(null, List.of("The definition must be an object"), List.of());
    }
    Set<String> known = Set.of("name", "description", "readOnly", "readOnlyConfirmedFor", "command", "workdir",
        "timeoutSeconds", "params", "freeText");
    for (String key : d.propertyNames()) {
      if (!known.contains(key)) {
        errors.add(key + " is not a field of a runbook");
      }
    }
    String name = d.path("name").asString(null);
    if (name == null || !NAME.matcher(name).matches()) {
      errors.add("name: 2–48 lowercase letters, digits and -, starting with a letter");
    }
    String description = d.path("description").asString(null);
    if (description != null && (description.length() > 300 || !clean(description))) {
      errors.add("description: one line of at most 300 characters");
    }
    String command = d.path("command").asString(null);
    if (command == null || command.isBlank()) {
      errors.add("command: the command line the runbook runs");
      command = "";
    } else if (command.length() > MAX_COMMAND) {
      errors.add("command: at most " + MAX_COMMAND + " characters");
    } else if (!clean(command)) {
      errors.add("command: one line without control or invisible characters");
    }
    String workdir = d.path("workdir").asString(null);
    if (workdir != null && (workdir.isEmpty() || workdir.length() > 512 || !clean(workdir))) {
      errors.add("workdir: a folder of at most 512 characters");
    }
    int timeout = 0;
    JsonNode t = d.get("timeoutSeconds");
    if (t != null && !t.isNull()) {
      if (!t.isIntegralNumber() || t.asInt() < 1 || t.asInt() > MAX_TIMEOUT) {
        errors.add("timeoutSeconds: 1–" + MAX_TIMEOUT);
      } else {
        timeout = t.asInt();
      }
    }

    List<Runbook.Param> params = new ArrayList<>();
    JsonNode ps = d.path("params");
    if (!ps.isMissingNode() && !ps.isNull() && !ps.isArray()) {
      errors.add("params must be a list");
    } else if (ps.size() > MAX_PARAMS) {
      errors.add("params: at most " + MAX_PARAMS);
    } else {
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < ps.size(); i++) {
        Runbook.Param p = param(ps.get(i), "params[" + i + "]", errors);
        if (p != null && !seen.add(p.name())) {
          errors.add("params[" + i + "]: " + p.name() + " is declared twice");
        } else if (p != null) {
          params.add(p);
        }
      }
    }

    Set<String> declared = new LinkedHashSet<>();
    params.forEach(p -> declared.add(p.name()));
    CommandLexer.Result lexed = CommandLexer.lex(command, declared);
    errors.addAll(lexed.errors().stream().map(e -> "command: " + e).toList());
    Set<String> used = new HashSet<>();
    for (CommandLexer.Segment s : lexed.segments()) {
      if (s instanceof CommandLexer.Placeholder p) {
        used.add(p.name());
      }
    }
    for (String p : declared) {
      if (!used.contains(p)) {
        errors.add("params: " + p + " is declared but the command doesn't use {" + p + "}");
      }
    }

    boolean wantsReadOnly = d.path("readOnly").asBoolean(false);
    boolean readOnly;
    if (compiledReadOnly) {
      readOnly = wantsReadOnly;
    } else {
      String confirmedFor = d.path("readOnlyConfirmedFor").asString(null);
      readOnly = wantsReadOnly && command.equals(confirmedFor);
      if (wantsReadOnly && !readOnly) {
        warnings.add(confirmedFor == null
            ? "Read only isn't confirmed for this command, so every run waits for approval. Confirm that it doesn't "
                + "change state (readOnlyConfirmedFor) to let it run at once."
            : "The command changed since it was confirmed as read only, so every run waits for approval until you "
                + "confirm it again.");
      }
    }
    Set<String> changing = changingWords(command);
    if (wantsReadOnly && !changing.isEmpty()) {
      warnings.add("It looks like it changes state (" + String.join(", ", changing) + "), yet it is marked read only: "
          + "a read-only runbook runs without approval.");
    }
    for (String w : words(command)) {
      if (ENDLESS_WORDS.contains(w)) {
        warnings.add(w + " never ends: the run will stop at the timeout.");
        break;
      }
    }
    danglingOptions(lexed.segments(), params, warnings);

    if (!errors.isEmpty()) {
      return new Outcome(null, List.copyOf(errors), List.copyOf(warnings));
    }
    boolean freeText = params.stream().anyMatch(p -> p.type() == Runbook.Param.Type.STRING);
    Runbook runbook = new Runbook(name, description, readOnly, command, workdir, timeout, List.copyOf(params),
        lexed.segments(), freeText);
    return new Outcome(runbook, List.of(), List.copyOf(warnings));
  }

  private static Runbook.Param param(JsonNode j, String at, List<String> errors) {
    if (!j.isObject()) {
      errors.add(at + " must be an object");
      return null;
    }
    String name = j.path("name").asString(null);
    if (name == null || !PARAM_NAME.matcher(name).matches()) {
      errors.add(at + ".name: lowercase letters, digits and _, starting with a letter");
      return null;
    }
    Runbook.Param.Type type = Runbook.Param.Type.of(j.path("type").asString(""));
    if (type == null) {
      errors.add(at + ".type: enum, int, bool or string");
      return null;
    }
    boolean required = j.path("required").asBoolean(false);
    JsonNode dflt = j.get("default");
    if (dflt != null && dflt.isNull()) {
      dflt = null;
    }
    int before = errors.size();
    List<String> values = List.of();
    long min = 0;
    long max = 0;
    Pattern pattern = null;
    int maxLength = 0;
    boolean leadingDash = false;
    String whenTrue = null;
    String whenFalse = null;
    switch (type) {
      case ENUM -> {
        JsonNode vs = j.path("values");
        if (!vs.isArray() || vs.isEmpty() || vs.size() > MAX_VALUES) {
          errors.add(at + ".values: 1–" + MAX_VALUES + " values");
        } else {
          List<String> list = new ArrayList<>();
          for (JsonNode v : vs) {
            String s = v.isString() ? v.asString() : null;
            if (s == null || s.isEmpty() || s.length() > 128 || !clean(s) || s.startsWith("-")) {
              errors.add(at + ".values: strings of 1–128 characters, no control characters, no leading -");
            } else if (list.contains(s)) {
              errors.add(at + ".values: " + s + " twice");
            } else {
              list.add(s);
            }
          }
          values = List.copyOf(list);
          if (dflt != null && (!dflt.isString() || !values.contains(dflt.asString()))) {
            errors.add(at + ".default must be one of the values");
          }
        }
      }
      case INT -> {
        JsonNode lo = j.get("min");
        JsonNode hi = j.get("max");
        if (lo == null || hi == null || !lo.isIntegralNumber() || !hi.isIntegralNumber() || lo.asLong() > hi.asLong()
            || !lo.canConvertToLong() || !hi.canConvertToLong()) {
          errors.add(at + ": an int needs min and max");
        } else {
          min = lo.asLong();
          max = hi.asLong();
          if (dflt != null && (!dflt.isIntegralNumber() || dflt.asLong() < min || dflt.asLong() > max)) {
            errors.add(at + ".default must be an integer " + min + ".." + max);
          }
        }
      }
      case BOOL -> {
        whenTrue = j.path("whenTrue").asString("");
        whenFalse = j.path("whenFalse").asString("");
        if (!LITERAL.matcher(whenTrue).matches() || !LITERAL.matcher(whenFalse).matches()) {
          errors.add(at + ": whenTrue and whenFalse are literals of letters, digits and _=.,:/@%+-");
        }
        if (whenTrue.equals(whenFalse)) {
          errors.add(at + ": whenTrue and whenFalse must differ");
        }
        if (dflt != null && !dflt.isBoolean()) {
          errors.add(at + ".default must be true or false");
        }
        if (dflt == null) {
          dflt = JsonNodeFactory.instance.booleanNode(false);
        }
      }
      case STRING -> {
        String regex = j.path("pattern").asString(null);
        if (regex == null || regex.isEmpty()) {
          errors.add(at + ": a string needs a pattern (a regular expression the whole value must match)");
        } else {
          try {
            pattern = Pattern.compile(regex);
          } catch (PatternSyntaxException e) {
            errors.add(at + ".pattern: " + e.getDescription());
          }
        }
        maxLength = j.path("maxLength").asInt(MAX_STRING);
        if (maxLength < 1 || maxLength > MAX_STRING) {
          errors.add(at + ".maxLength: 1–" + MAX_STRING);
        }
        leadingDash = j.path("allowLeadingDash").asBoolean(false);
        if (dflt != null && !dflt.isString()) {
          errors.add(at + ".default must be a string");
        }
      }
    }
    if (errors.size() > before) {
      return null;
    }
    Runbook.Param p = new Runbook.Param(name, type, required, values, min, max, pattern, maxLength, leadingDash,
        whenTrue, whenFalse, dflt);
    if (type == Runbook.Param.Type.STRING && dflt != null) {
      // The default is a value like any other: it must pass the checks an agent's value passes.
      try {
        Runbook probe = new Runbook("x", null, false, "", null, 0, List.of(p), List.of(), true);
        probe.values(JsonNodeFactory.instance.objectNode().set(name, dflt));
      } catch (Runbook.InvalidParams e) {
        errors.add(at + ".default: " + e.getMessage());
        return null;
      }
    }
    return p;
  }

  private static boolean clean(String s) {
    return s.indexOf('\n') < 0 && s.indexOf('\t') < 0 && s.indexOf('\r') < 0 && OutputSanitizer.isClean(s);
  }

  private static List<String> words(String command) {
    List<String> out = new ArrayList<>();
    Matcher m = WORD.matcher(command);
    while (m.find()) {
      out.add(m.group());
    }
    return out;
  }

  /** Words of the command that usually mean a change, and output redirection other than to /dev/null. */
  static Set<String> changingWords(String command) {
    Set<String> out = new LinkedHashSet<>();
    for (String w : words(command)) {
      if (CHANGING_WORDS.contains(w.toLowerCase(Locale.ROOT))) {
        out.add(w);
      }
    }
    String withoutNull = command.replaceAll("[0-9]?>>?\\s*/dev/null", "");
    if (REDIRECT.matcher(withoutNull).find()) {
      out.add(">");
    }
    return out;
  }

  /** {@code --tail {lines}} with an optional lines and no default leaves {@code --tail} on its own. */
  private static void danglingOptions(List<CommandLexer.Segment> segments, List<Runbook.Param> params,
      List<String> warnings) {
    for (int i = 1; i < segments.size(); i++) {
      if (segments.get(i) instanceof CommandLexer.Placeholder p
          && segments.get(i - 1) instanceof CommandLexer.Text t && t.text().matches("(?s).*\\s-{1,2}[A-Za-z][\\w-]* $")) {
        for (Runbook.Param param : params) {
          if (param.name().equals(p.name()) && !param.required() && param.defaultValue() == null
              && param.type() != Runbook.Param.Type.BOOL) {
            warnings.add(param.name() + " is optional without a default: when it's absent, the option before it is "
                + "left alone. Give it a default or make it required.");
          }
        }
      }
    }
  }
}
