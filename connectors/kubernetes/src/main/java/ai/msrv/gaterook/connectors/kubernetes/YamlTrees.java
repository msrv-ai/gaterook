package ai.msrv.gaterook.connectors.kubernetes;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * YAML that agents and kubeconfig files bring, read into JSON trees with everything that makes YAML dangerous turned
 * off (report 08 §3.4): no aliases at all (billion laughs), no duplicate keys (two readers, two meanings), no
 * recursive or non-scalar keys, no environment substitution, no custom tags ({@code !!java…}), a size limit.
 * YAML 1.2 core schema, as JSON: {@code on}/{@code yes} stay strings.
 */
final class YamlTrees {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private YamlTrees() {}

  /** A problem with the text, in words for the agent (never with the text itself). */
  static final class YamlException extends Exception {
    private static final long serialVersionUID = 1L;

    YamlException(String message) {
      super(message);
    }
  }

  static LoadSettings settings(int codePointLimit) {
    return LoadSettings.builder()
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(0)
        .setAllowRecursiveKeys(false)
        .setAllowNonScalarKeys(false)
        .setCodePointLimit(codePointLimit)
        .setEnvConfig(Optional.empty())
        .setUseMarks(true)
        .build();
  }

  /** Every document of {@code text}; an empty document ({@code ---} alone) is skipped. */
  static List<JsonNode> documents(String text, int codePointLimit) throws YamlException {
    List<JsonNode> out = new ArrayList<>();
    try {
      refuseAnchors(text, codePointLimit);
      for (Object doc : new Load(settings(codePointLimit)).loadAllFromString(text)) {
        if (doc != null) {
          out.add(tree(doc, 0));
        }
      }
    } catch (YamlEngineException e) {
      throw new YamlException(describe(e));
    } catch (IllegalArgumentException | ClassCastException e) {
      throw new YamlException("The YAML has a value MCP Gateway can't read as JSON.");
    }
    return out;
  }

  /**
   * The engine's alias limit counts aliases of collections only ({@code *x} of a scalar passes); anchors and aliases
   * are refused here, before anything is built, from the parser's events.
   */
  private static void refuseAnchors(String text, int codePointLimit) throws YamlException {
    for (Event e : new Parse(settings(codePointLimit)).parseString(text)) {
      if (e instanceof AliasEvent || e instanceof NodeEvent n && n.getAnchor().isPresent()) {
        String at = e.getStartMark().map(m -> " (line " + (m.getLine() + 1) + ", column " + (m.getColumn() + 1) + ")")
            .orElse("");
        throw new YamlException("The YAML uses an " + (e instanceof AliasEvent ? "alias (*name)" : "anchor (&name)")
            + ": MCP Gateway doesn't expand aliases. Write the values out." + at);
      }
    }
  }

  /**
   * The engine's message names the problem and where it is ("found duplicate key replicas … line 7, column 3"); the
   * snippet of the text it quotes after the position is dropped: the text may hold a secret.
   */
  private static String describe(YamlEngineException e) {
    String m = e.getMessage() == null ? "" : e.getMessage();
    // The engine writes problems unindented, " in reader, line 7, column 3:" indented once and the quoted text below.
    String problem = null;
    String where = null;
    for (String line : m.lines().toList()) {
      String s = line.strip();
      if (s.isEmpty()) {
        continue;
      }
      if (!Character.isWhitespace(line.charAt(0))) {
        problem = s;
      } else if (s.startsWith("in ") && s.contains("line ")) {
        where = s.substring(s.indexOf("line ")).replaceAll(":$", "");
      }
    }
    String at = where == null ? "" : " (" + where + ")";
    if (m.contains("aliases")) {
      return "The YAML uses an alias (*name): MCP Gateway doesn't expand aliases. Write the values out." + at;
    }
    if (m.contains("could not determine a constructor")) {
      return "The YAML uses a custom tag (!…): only plain YAML or JSON is accepted." + at;
    }
    if (m.contains("code point")) {
      return "The YAML is too long.";
    }
    return (problem == null ? "The YAML can't be parsed." : "The YAML can't be parsed: " + problem) + at;
  }

  @SuppressWarnings("unchecked")
  private static JsonNode tree(Object value, int depth) throws YamlException {
    if (depth > 100) {
      throw new YamlException("The YAML is nested more than 100 levels deep.");
    }
    if (value == null) {
      return NODES.nullNode();
    }
    if (value instanceof Map<?, ?> map) {
      ObjectNode o = NODES.objectNode();
      for (Map.Entry<Object, Object> e : ((Map<Object, Object>) map).entrySet()) {
        if (!(e.getKey() instanceof String) && !(e.getKey() instanceof Number) && !(e.getKey() instanceof Boolean)) {
          throw new YamlException("A YAML key must be a string.");
        }
        o.set(String.valueOf(e.getKey()), tree(e.getValue(), depth + 1));
      }
      return o;
    }
    if (value instanceof List<?> list) {
      ArrayNode a = NODES.arrayNode();
      for (Object item : list) {
        a.add(tree(item, depth + 1));
      }
      return a;
    }
    if (value instanceof String s) {
      return NODES.stringNode(s);
    }
    if (value instanceof Boolean b) {
      return NODES.booleanNode(b);
    }
    if (value instanceof Integer i) {
      return NODES.numberNode(i);
    }
    if (value instanceof Long l) {
      return NODES.numberNode(l);
    }
    if (value instanceof BigInteger b) {
      return NODES.numberNode(b);
    }
    if (value instanceof Double d) {
      if (d.isNaN() || d.isInfinite()) {
        throw new YamlException("The YAML has a number JSON can't hold (.nan or .inf).");
      }
      return NODES.numberNode(d);
    }
    if (value instanceof byte[] bytes) {
      // !!binary: kubectl sends base64 text too.
      return NODES.stringNode(Base64.getEncoder().encodeToString(bytes));
    }
    throw new YamlException("The YAML has a value of an unsupported type (" + value.getClass().getSimpleName()
        + ").");
  }
}
