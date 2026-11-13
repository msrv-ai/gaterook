package com.gaterook.connectors.http;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.api.ConstructNode;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.Anchor;
import org.snakeyaml.engine.v2.composer.Composer;
import org.snakeyaml.engine.v2.constructor.json.ConstructYamlJsonInt;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import org.snakeyaml.engine.v2.schema.JsonSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads an OpenAPI document (report 10 §6.5): JSON with Jackson's read limits, YAML with snakeyaml-engine in the
 * YAML 1.2 JSON schema ({@code yes} and dates stay strings) with code point and alias limits and no duplicate keys,
 * converted to a tree by a walk of its own (depth ≤ 200, a recursive alias {@code &a [*a]} is refused instead of
 * recursing forever, and aliases may not expand the file past twice its size). Only OpenAPI 3.0 and 3.1, and only
 * local {@code $ref}s.
 *
 * <p>YAML gets the JSON reader's limits as well: no collection as a key (it would have to be written out as text
 * first), no nesting deeper than {@value #MAX_DEPTH} even while snakeyaml composes the file (its composer recurses per
 * level), and the same longest number, text value and key. Numbers mean what they mean in JSON: a decimal is read as
 * written when a double can't hold it ({@code 1e999}), and {@code .inf} and {@code .nan}, which JSON has no number for,
 * stay text. Whatever else a file can't be read for is a {@link Rejected} with a reason, never an exception of the
 * parser.
 */
final class OpenApiDocument {

  /** GitHub's description is 13.6 MB; Stripe's YAML 6.6 MB. */
  static final int MAX_BYTES = 20 * 1_024 * 1_024;
  private static final int MAX_DEPTH = 200;
  /** The longest number, text value and key of a file, in characters: the same for JSON and YAML. */
  static final int MAX_NUMBER = 1_000;
  static final int MAX_TEXT = 5_000_000;
  static final int MAX_KEY = 50_000;

  private static final StreamReadConstraints LIMITS = StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH)
      .maxStringLength(MAX_TEXT).maxNameLength(MAX_KEY).maxDocumentLength(25_000_000L).maxNumberLength(MAX_NUMBER)
      .build();
  private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(LIMITS)
      .build()).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** A document that can't be imported, with the reason a person reads. */
  static final class Rejected extends Exception {
    private static final long serialVersionUID = 1L;

    Rejected(String message) {
      super(message, null, false, false);
    }
  }

  private OpenApiDocument() {}

  /** Parses and checks the document; the tree it returns is OpenAPI 3.0 or 3.1 with local references only. */
  static JsonNode read(byte[] bytes) throws Rejected {
    if (bytes == null || bytes.length == 0) {
      throw new Rejected("The file is empty.");
    }
    if (bytes.length > MAX_BYTES) {
      throw new Rejected("The file is larger than 20 MB.");
    }
    JsonNode root = looksLikeJson(bytes) ? json(bytes) : yaml(bytes);
    if (!root.isObject()) {
      throw new Rejected("The file isn't an OpenAPI document: it has no top-level object.");
    }
    String version = root.path("openapi").asString("");
    if (root.has("swagger")) {
      throw new Rejected("This is Swagger " + root.path("swagger").asString("2.0") + ". Convert it to OpenAPI 3 first "
          + "(npx swagger2openapi).");
    }
    if (!version.startsWith("3.0") && !version.startsWith("3.1")) {
      throw new Rejected("Only OpenAPI 3.0 and 3.1 can be imported" + (version.isEmpty() ? "." : "; this is "
          + version + "."));
    }
    List<String> external = new ArrayList<>();
    scanRefs(root, external, 0);
    if (!external.isEmpty()) {
      throw new Rejected("The file refers to other files (" + external.size() + " external $ref, e.g. "
          + clip(external.getFirst()) + "). Bundle the file into one document first.");
    }
    return root;
  }

  private static boolean looksLikeJson(byte[] bytes) {
    for (byte b : bytes) {
      if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
        continue;
      }
      return b == '{' || b == '[';
    }
    return false;
  }

  private static JsonNode json(byte[] bytes) throws Rejected {
    try {
      return JSON.readTree(bytes);
    } catch (JacksonException e) {
      throw new Rejected("The file isn't valid JSON: " + clip(e.getOriginalMessage()) + ".");
    } catch (NumberFormatException | ArithmeticException e) {
      // An exponent beyond what a decimal holds at all (1e2147483648): Jackson does not wrap that failure.
      throw new Rejected("The file isn't valid JSON: it has a number that can't be read.");
    }
  }

  private static JsonNode yaml(byte[] bytes) throws Rejected {
    LoadSettings settings = LoadSettings.builder().setSchema(new Numbers()).setCodePointLimit(25 * 1_024 * 1_024)
        .setMaxAliasesForCollections(50).setAllowDuplicateKeys(false).setAllowRecursiveKeys(false)
        .setAllowNonScalarKeys(false).build();
    Object loaded;
    try {
      loaded = new Bounded(settings).loadFromString(new String(bytes, StandardCharsets.UTF_8));
    } catch (Unreadable e) {
      throw new Rejected(e.getMessage());
    } catch (YamlEngineException e) {
      throw new Rejected("The file isn't valid YAML: " + clip(e.getMessage()) + ".");
    } catch (RuntimeException e) {
      throw new Rejected("The file isn't valid YAML.");
    }
    return tree(loaded, 0, new IdentityHashMap<>(), new long[] {expansionBudget(bytes.length)});
  }

  /** Why snakeyaml can't go on: the reason a person reads, passed through its constructor unchanged. */
  private static final class Unreadable extends YamlEngineException {
    private static final long serialVersionUID = 1L;

    Unreadable(String message) {
      super(message);
    }
  }

  /** {@link Load} whose composer refuses to nest deeper than {@link #MAX_DEPTH}: it recurses once per level. */
  private static final class Bounded extends Load {
    private final LoadSettings settings;

    Bounded(LoadSettings settings) {
      super(settings);
      this.settings = settings;
    }

    @Override
    protected Composer createComposer(String yaml) {
      return new Composer(settings, new ParserImpl(settings, new StreamReader(settings, yaml))) {
        private int depth;

        @Override
        protected SequenceNode composeSequenceNode(Optional<Anchor> anchor) {
          enter();
          try {
            return super.composeSequenceNode(anchor);
          } finally {
            depth--;
          }
        }

        @Override
        protected Node composeMappingNode(Optional<Anchor> anchor) {
          enter();
          try {
            return super.composeMappingNode(anchor);
          } finally {
            depth--;
          }
        }

        /** The tree walk refuses the same depth; a collection one level deeper is where composing stops. */
        private void enter() {
          if (++depth > MAX_DEPTH + 1) {
            throw new Unreadable("The file nests deeper than " + MAX_DEPTH + " levels.");
          }
        }
      };
    }
  }

  /**
   * The YAML 1.2 JSON schema with JSON's numbers: at most {@link #MAX_NUMBER} characters, checked before a digit is
   * converted; integers as before ({@code Integer}, {@code Long}, {@code BigInteger}); a decimal a double holds as
   * that double, as before, and one it doesn't ({@code 1e999}, {@code 1e-999}) as the exact decimal; {@code .inf},
   * {@code -.inf} and {@code .nan} as their text.
   */
  private static final class Numbers extends JsonSchema {
    private static final ConstructNode INTEGERS = new ConstructYamlJsonInt();

    @Override
    public Map<Tag, ConstructNode> getSchemaTagConstructors() {
      Map<Tag, ConstructNode> constructors = new HashMap<>(super.getSchemaTagConstructors());
      constructors.put(Tag.INT, node -> INTEGERS.construct(checked(node)));
      constructors.put(Tag.FLOAT, node -> decimal(((ScalarNode) checked(node)).getValue()));
      return constructors;
    }

    private static Node checked(Node node) {
      if (node instanceof ScalarNode scalar && scalar.getValue().length() > MAX_NUMBER) {
        throw new Unreadable("The file has a number longer than " + String.format(Locale.US, "%,d", MAX_NUMBER)
            + " characters.");
      }
      return node;
    }

    private static Object decimal(String text) {
      if (text.equals(".inf") || text.equals("-.inf") || text.equals(".nan")) {
        return text;
      }
      try {
        double d = Double.parseDouble(text);
        if (Double.isFinite(d) && (d != 0 || !nonZero(text))) {
          return d;
        }
        return new BigDecimal(text);
      } catch (NumberFormatException e) {
        throw new Unreadable("The file has a number that can't be read: " + clip(text) + ".");
      }
    }

    /** Whether a digit other than 0 comes before the exponent of a decimal's text: zero as a double is then wrong. */
    private static boolean nonZero(String text) {
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == 'e' || c == 'E') {
          return false;
        }
        if (c >= '1' && c <= '9') {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * How much a YAML file of {@code bytes} may become (nodes plus characters of strings and keys): without aliases a
   * file can't hold more than its size, and aliases may share a part of it, never multiply it. snakeyaml's limit of 50
   * aliases of collections still lets 3 aliases a level over 16 levels expand to 3^16 nodes, and it doesn't limit
   * aliases of scalars at all — one long string a thousand times is a gigabyte.
   */
  static long expansionBudget(int bytes) {
    return 2L * bytes + 100_000;
  }

  /**
   * Java values of snakeyaml-engine to a tree; a collection that contains itself is refused, and so is a file that
   * expands past {@code budget[0]} (decremented as nodes and strings are written).
   */
  private static JsonNode tree(Object value, int depth, IdentityHashMap<Object, Boolean> path, long[] budget)
      throws Rejected {
    if (depth > MAX_DEPTH) {
      throw new Rejected("The file nests deeper than " + MAX_DEPTH + " levels.");
    }
    spend(budget, 1);
    if (value == null) {
      return NODES.nullNode();
    }
    if (value instanceof String s) {
      if (s.length() > MAX_TEXT) {
        throw new Rejected("The file has a text value longer than " + String.format(Locale.US, "%,d", MAX_TEXT)
            + " characters.");
      }
      spend(budget, s.length());
      return NODES.stringNode(s);
    }
    if (value instanceof BigDecimal decimal) {
      return NODES.numberNode(decimal);
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
    if (value instanceof BigInteger bi) {
      return NODES.numberNode(bi);
    }
    if (value instanceof Double d) {
      // The constructor keeps a decimal a double can't hold as a BigDecimal; text for anything else, defensively.
      return Double.isFinite(d) ? NODES.numberNode(new BigDecimal(Double.toString(d))) : NODES.stringNode(d.toString());
    }
    if (value instanceof Float f) {
      return Float.isFinite(f) ? NODES.numberNode(new BigDecimal(Float.toString(f))) : NODES.stringNode(f.toString());
    }
    if (value instanceof Map<?, ?> map) {
      enter(value, path);
      ObjectNode out = NODES.objectNode();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        // A key is a scalar (snakeyaml refuses collections as keys), so its text is as long as the file said.
        String key = String.valueOf(e.getKey());
        if (key.length() > MAX_KEY) {
          throw new Rejected("The file has a key longer than " + String.format(Locale.US, "%,d", MAX_KEY)
              + " characters.");
        }
        spend(budget, key.length());
        out.set(key, tree(e.getValue(), depth + 1, path, budget));
      }
      path.remove(value);
      return out;
    }
    if (value instanceof List<?> list) {
      enter(value, path);
      ArrayNode out = NODES.arrayNode();
      for (Object item : list) {
        out.add(tree(item, depth + 1, path, budget));
      }
      path.remove(value);
      return out;
    }
    if (value instanceof byte[] bytes) {
      spend(budget, bytes.length * 4L / 3);
      return NODES.stringNode(java.util.Base64.getEncoder().encodeToString(bytes));
    }
    String text = String.valueOf(value);
    spend(budget, text.length());
    return NODES.stringNode(text);
  }

  private static void enter(Object collection, IdentityHashMap<Object, Boolean> path) throws Rejected {
    if (path.put(collection, Boolean.TRUE) != null) {
      throw new Rejected("The file has a recursive YAML alias.");
    }
  }

  private static void spend(long[] budget, long amount) throws Rejected {
    budget[0] -= amount;
    if (budget[0] < 0) {
      throw new Rejected("The file's YAML aliases expand to more than twice its size. Remove the anchors and aliases "
          + "(write each part out), then import it again.");
    }
  }

  /** Every {@code $ref} that doesn't point into this document. */
  private static void scanRefs(JsonNode n, List<String> external, int depth) {
    if (depth > MAX_DEPTH || external.size() > 10) {
      return;
    }
    if (n.isObject()) {
      JsonNode ref = n.get("$ref");
      if (ref != null && ref.isString() && !ref.asString().startsWith("#/")) {
        external.add(ref.asString());
      }
      for (Map.Entry<String, JsonNode> e : n.properties()) {
        scanRefs(e.getValue(), external, depth + 1);
      }
    } else if (n.isArray()) {
      for (JsonNode c : n) {
        scanRefs(c, external, depth + 1);
      }
    }
  }

  /**
   * The node a local {@code $ref} points to ({@code #/components/schemas/Refund}), following a chain of references
   * (at most 16); {@code null} when it points nowhere.
   */
  static JsonNode deref(JsonNode root, JsonNode node) {
    JsonNode n = node;
    for (int i = 0; i < 16 && n != null && n.isObject() && n.path("$ref").isString(); i++) {
      String ref = n.get("$ref").asString();
      if (!ref.startsWith("#/")) {
        return null;
      }
      JsonNode target = root.at(ref.substring(1));
      n = target.isMissingNode() ? null : target;
    }
    return n;
  }

  static final Set<String> METHODS = Set.of("get", "head", "post", "put", "patch", "delete", "options", "trace");

  private static String clip(String s) {
    if (s == null) {
      return "";
    }
    String clean = ToolText.clean(s, 160);
    return clean == null ? "" : clean;
  }
}
