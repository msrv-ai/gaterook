package ai.msrv.gaterook.core.platform;

import com.networknt.schema.Error;
import com.networknt.schema.ExecutionConfig;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.ExecutionContextCustomizer;
import com.networknt.schema.OutputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressionFactory;
import com.networknt.schema.regex.RegularExpressions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * JSON Schema 2020-12 validation (networknt 3.0.6) for what people and connectors hand the core: connection configs
 * against {@code configSchema}, policy sections against their schemas. Violations come back with the JSON Pointer of the
 * offending field, so that the UI can mark it ({@code problem+json} {@code field}).
 *
 * <p>An agent's tool arguments go through {@link #validateBounded} (ARCHITECTURE.md §5 step 5): the arguments may be
 * nested at most {@value #MAX_DEPTH} levels, the tool's schema may have at most {@value #MAX_SUBSCHEMAS} subschemas
 * (schemas come from connectors now, and from MCP servers and plugins later), and the validation has a time budget —
 * checked before every keyword and, inside {@code pattern}, while the regular expression reads its input, so a
 * backtracking pattern can't hold a request thread either.
 *
 * <p>Compiled schemas are kept by the SHA-256 of their canonical text, at most {@value #MAX_CACHED}. Schemas never
 * resolve remote {@code $ref}s: only {@code urn:} locations of their own.
 */
public final class SchemaValidator {

  /** One problem: where ({@code /connection/port}) and what. */
  public record Violation(String pointer, String message) {}

  /** How deep an agent's arguments may nest. */
  public static final int MAX_DEPTH = 32;

  /** How many subschemas a tool's input schema may have. */
  public static final int MAX_SUBSCHEMAS = 2_000;

  /** How long validating an agent's arguments may take. */
  public static final Duration BUDGET = Duration.ofMillis(50);

  private static final int MAX_CACHED = 256;

  /** Keywords whose value is one schema, an array of schemas, or a map of names to schemas (2020-12 and older). */
  private static final Set<String> ONE = Set.of("not", "if", "then", "else", "items", "additionalItems", "contains",
      "additionalProperties", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "contentSchema");
  private static final Set<String> MANY = Set.of("allOf", "anyOf", "oneOf", "prefixItems", "items");
  private static final Set<String> NAMED = Set.of("properties", "patternProperties", "$defs", "definitions",
      "dependentSchemas", "dependencies");

  /** The deadline of the bounded validation running on this thread, if any ({@code pattern} reads it). */
  private static final ThreadLocal<long[]> DEADLINE = new ThreadLocal<>();

  private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
      b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder().regularExpressionFactory(new BudgetedRegex())
          .build()));
  private final Map<String, Schema> compiled = new LinkedHashMap<>(64, 0.75f, true) {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Schema> eldest) {
      return size() > MAX_CACHED;
    }
  };

  public List<Violation> validate(JsonNode schema, JsonNode instance) {
    return violations(compile(schema).validate(instance));
  }

  /**
   * {@link #validate} within the bounds of an agent's call. A bound that doesn't hold is one violation at {@code /}:
   * the call is refused like any other with invalid arguments, and nothing ran.
   */
  public List<Violation> validateBounded(JsonNode schema, JsonNode instance, Duration budget) {
    if (depth(instance, MAX_DEPTH + 1) > MAX_DEPTH) {
      return List.of(new Violation("/", "nested deeper than " + MAX_DEPTH + " levels"));
    }
    if (subschemas(schema, MAX_SUBSCHEMAS + 1) > MAX_SUBSCHEMAS) {
      return List.of(new Violation("/", "the tool's input schema has more than " + MAX_SUBSCHEMAS
          + " subschemas, so MCP Gateway doesn't check arguments against it"));
    }
    // Compiling is outside the budget: it happens once per schema, and the count above bounds it.
    Schema s = compile(schema);
    long deadline = System.nanoTime() + budget.toNanos();
    long[] previous = DEADLINE.get();
    DEADLINE.set(new long[] {deadline});
    try {
      return violations(s.validate(new Budgeted(s, deadline), instance, OutputFormat.DEFAULT,
          (ExecutionContextCustomizer) null));
    } catch (OverBudget e) {
      return List.of(new Violation("/", "took longer than " + budget.toMillis() + " ms to check"));
    } finally {
      if (previous == null) {
        DEADLINE.remove();
      } else {
        DEADLINE.set(previous);
      }
    }
  }

  /** How deep {@code node} nests (a scalar is 0, {@code {}} is 1), counted up to {@code cap}. */
  static int depth(JsonNode node, int cap) {
    if (node == null || !node.isContainer()) {
      return 0;
    }
    int deepest = 0;
    Deque<Map.Entry<JsonNode, Integer>> todo = new ArrayDeque<>();
    todo.push(Map.entry(node, 1));
    while (!todo.isEmpty()) {
      Map.Entry<JsonNode, Integer> next = todo.pop();
      int level = next.getValue();
      deepest = Math.max(deepest, level);
      if (deepest >= cap) {
        return deepest;
      }
      for (JsonNode child : next.getKey()) {
        if (child.isContainer()) {
          todo.push(Map.entry(child, level + 1));
        }
      }
    }
    return deepest;
  }

  /** The schema and every schema under a schema keyword, counted up to {@code cap}. */
  static int subschemas(JsonNode schema, int cap) {
    int count = 0;
    Deque<JsonNode> todo = new ArrayDeque<>();
    todo.push(schema);
    while (!todo.isEmpty() && count < cap) {
      JsonNode s = todo.pop();
      count++;
      if (!s.isObject()) {
        continue;
      }
      for (Map.Entry<String, JsonNode> e : s.properties()) {
        JsonNode v = e.getValue();
        if (ONE.contains(e.getKey()) && (v.isObject() || v.isBoolean())) {
          todo.push(v);
        } else if (MANY.contains(e.getKey()) && v.isArray()) {
          v.forEach(todo::push);
        } else if (NAMED.contains(e.getKey()) && v.isObject()) {
          for (JsonNode sub : v) {
            if (sub.isObject() || sub.isBoolean()) {
              todo.push(sub);
            }
          }
        }
      }
    }
    return count;
  }

  private static List<Violation> violations(List<Error> errors) {
    List<Violation> out = new ArrayList<>();
    for (Error e : errors) {
      String pointer = pointer(e.getInstanceLocation());
      if ("required".equals(e.getKeyword()) && e.getProperty() != null) {
        pointer = pointer + "/" + escape(e.getProperty());
      }
      out.add(new Violation(pointer.isEmpty() ? "/" : pointer, e.getMessage()));
    }
    return out;
  }

  /** The bounded validation ran out of time. A {@link SchemaException}, which networknt passes on without logging. */
  private static final class OverBudget extends SchemaException {
    private static final long serialVersionUID = 1L;

    OverBudget() {
      super("over budget");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  private static void checkDeadline(long deadline) {
    if (System.nanoTime() - deadline > 0) {
      throw new OverBudget();
    }
  }

  /**
   * networknt's own execution context (the defaults of {@code Schema.createExecutionContext}) that checks the deadline
   * whenever the evaluation steps into a keyword, a property or an item.
   */
  private static final class Budgeted extends ExecutionContext {
    private final long deadline;

    Budgeted(Schema schema, long deadline) {
      super(ExecutionConfig.builder()
          .locale(schema.getSchemaContext().getSchemaRegistryConfig().getLocale())
          .formatAssertionsEnabled(schema.getSchemaContext().getSchemaRegistryConfig().getFormatAssertionsEnabled())
          .failFast(schema.getSchemaContext().getSchemaRegistryConfig().isFailFast()).build());
      this.deadline = deadline;
    }

    @Override
    public void evaluationPathAddLast(String token) {
      checkDeadline(deadline);
      super.evaluationPathAddLast(token);
    }

    @Override
    public void evaluationPathAddLast(int token) {
      checkDeadline(deadline);
      super.evaluationPathAddLast(token);
    }
  }

  /**
   * networknt's JDK regular expressions (the same translation of the pattern), reading the input through a view that
   * checks the deadline of a bounded validation; without one, plain.
   */
  private static final class BudgetedRegex implements RegularExpressionFactory {

    @Override
    public RegularExpression getRegularExpression(String regex) {
      Pattern pattern = Pattern.compile(RegularExpressions.replaceLongformCharacterProperties(
          RegularExpressions.replaceDollarAnchors(regex)));
      return value -> {
        long[] deadline = DEADLINE.get();
        // find(), not matches(): JSON Schema patterns aren't anchored.
        return pattern.matcher(deadline == null ? value : new Deadlined(value, deadline[0])).find();
      };
    }
  }

  /** A string whose reads check the deadline every 1,024 characters. */
  private static final class Deadlined implements CharSequence {
    private final CharSequence text;
    private final long deadline;
    private int reads;

    Deadlined(CharSequence text, long deadline) {
      this.text = text;
      this.deadline = deadline;
    }

    @Override
    public char charAt(int index) {
      if ((++reads & 1023) == 0) {
        checkDeadline(deadline);
      }
      return text.charAt(index);
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new Deadlined(text.subSequence(start, end), deadline);
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }

  private Schema compile(JsonNode schema) {
    String key = CanonicalJson.sha256Hex(schema);
    synchronized (compiled) {
      Schema s = compiled.get(key);
      if (s == null) {
        s = registry.getSchema(SchemaLocation.of("urn:mcp-gateway:schema:" + key), schema);
        s.initializeValidators();
        compiled.put(key, s);
      }
      return s;
    }
  }

  private static String pointer(NodePath path) {
    StringBuilder out = new StringBuilder();
    if (path == null) {
      return "";
    }
    for (int i = 0; i < path.getNameCount(); i++) {
      Object element = path.getElement(i);
      if (element == null) {
        continue;
      }
      String name = element.toString();
      if ("$".equals(name) && i == 0) {
        continue;
      }
      out.append('/').append(escape(name));
    }
    return out.toString();
  }

  private static String escape(String name) {
    return name.replace("~", "~0").replace("/", "~1");
  }
}
