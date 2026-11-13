package com.gaterook.core.platform;

import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.JsonNumbers;
import com.gaterook.core.platform.mask.EntropyDetector;
import com.gaterook.core.platform.mask.MaskCounter;
import com.gaterook.core.platform.mask.PiiRules;
import com.gaterook.core.platform.mask.SecretRules;
import com.gaterook.core.platform.mask.TextRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes secrets and, by policy, personal data from everything that leaves a call (ARCHITECTURE.md §5 step 10, §9;
 * report 12 §7.7): results before the agent, Playground or the audit see them, arguments in the audit, texts of
 * notifications, log messages. Every string the agent can read is a value here: texts, the {@code uri}, {@code name}
 * and {@code mimeType} of a {@code resource_link}, JSON property names (hashes keyed by tokens), table column names
 * and string cells; numbers and binary nodes are checked by their text against the taint and personal data. An
 * image, audio or blob of a result is left out whole when its bytes carry a secret ({@code carriesSecret}). Each
 * goes through, in order:
 * <ol>
 *   <li>the call's {@link Taint}: every stored secret handed out and every derived credential, in their encodings,
 *       and the start of one that a connector's cut left before its marker;</li>
 *   <li>the {@link SecretFingerprints} of the process: every secret any call has been handed since start-up, so one
 *       connection's password is masked in another connection's data;</li>
 *   <li>sensitive keys of JSON objects ({@code password}, {@code client_secret}, {@code authorization}, …) and the
 *       policy's "Always mask these fields";</li>
 *   <li>the 18 secret rules, each behind a literal prefilter;</li>
 *   <li>entropy: long random-looking tokens nobody declared;</li>
 *   <li>personal data of the policy (email, phone, card, IBAN).</li>
 * </ol>
 * Whatever matched becomes {@link #MASK}; a masked number or binary value becomes the string {@link #MASK}, and a
 * masked property name that collides with another name of its object gets a suffix ({@code ‹masked›#2}). The outcome
 * counts the values changed ("3 fields masked") and the hits per rule. Trees are copied, never changed in place.
 * Stateless and thread-safe.
 */
public final class Masker {

  /** What replaces a secret, everywhere. */
  public static final String MASK = "‹masked›";

  /** How every connector's cut of a long value begins ({@code …‹truncated 12 chars›}, {@code …‹256 bytes›}). */
  static final String CUT_MARK = "…‹";

  private static final Masker STANDARD = new Masker(SecretFingerprints.process());

  private final SecretFingerprints fingerprints;

  /** A masker over the process's fingerprints, like {@link #standard()}. */
  public Masker() {
    this(SecretFingerprints.process());
  }

  /** A masker over {@code fingerprints} alone. */
  Masker(SecretFingerprints fingerprints) {
    this.fingerprints = fingerprints;
  }

  /** Exact sensitive key names, normalized (lower case, no {@code -} or {@code _}). */
  private static final Set<String> SENSITIVE_KEYS = Set.of("password", "passwd", "pwd", "secret", "clientsecret",
      "token", "accesstoken", "refreshtoken", "idtoken", "sessiontoken", "apikey", "xapikey", "privatekey",
      "authorization", "proxyauthorization", "cookie", "setcookie", "secretstring", "secretaccesskey",
      "connectionstring", "authstring");
  /** Suffixes that make a key sensitive ({@code dbPassword}, {@code webhook_secret}, {@code githubToken}). */
  private static final List<String> SENSITIVE_SUFFIXES = List.of("password", "passwd", "secret", "apikey",
      "privatekey", "accesstoken", "refreshtoken", "secretkey", "token");
  /** Cursors, not credentials: pagination of GCP, AWS and others must keep working. */
  private static final List<String> CURSOR_SUFFIXES = List.of("pagetoken", "nexttoken", "continuationtoken",
      "synctoken", "resumetoken", "cursortoken");
  /** Keys whose values are cursors or handles: random by design, so the entropy detector leaves them alone. */
  private static final List<String> OPAQUE_SUFFIXES = List.of("cursor", "pagetoken", "nexttoken", "continuationtoken",
      "synctoken", "resumetoken", "handle", "requeststate", "etag", "digest", "sha256", "checksum");

  /** The outcome of one masking pass. */
  public record Outcome(int fields, Map<String, Integer> byRule) {
    public static final Outcome NONE = new Outcome(0, Map.of());

    public boolean masked() {
      return fields > 0;
    }
  }

  public record MaskedText(String text, Outcome outcome) {}

  public record MaskedTree(JsonNode tree, Outcome outcome) {}

  public record MaskedResult(ToolResult result, Outcome outcome) {}

  /** The shared instance (the rules are compiled once). */
  public static Masker standard() {
    return STANDARD;
  }

  /** Masks one text, e.g. a {@code content[].text} or an approval's action for a channel. */
  public MaskedText text(String text, Taint taint, MaskPolicy policy) {
    Pass pass = new Pass(fingerprints, taint, policy);
    String out = pass.string(text);
    return new MaskedText(out, pass.outcome());
  }

  /** A masked copy of {@code tree}: arguments for the audit, {@code structuredContent}. */
  public MaskedTree tree(JsonNode tree, Taint taint, MaskPolicy policy) {
    Pass pass = new Pass(fingerprints, taint, policy);
    JsonNode out = pass.node(tree, new ArrayList<>(), false);
    return new MaskedTree(out, pass.outcome());
  }

  /**
   * Masks a connector's result: texts and resource links, {@code structuredContent} (plus the connector's own
   * {@code maskPaths}, JSON pointers of known-sensitive values), the table's columns and cells (columns matched by the
   * policy's fields are masked whole) and {@code meta}. The returned result carries {@code maskedCount}.
   */
  public MaskedResult result(ToolResult result, Taint taint, MaskPolicy policy) {
    Pass pass = new Pass(fingerprints, taint, policy);
    List<ToolResult.Content> content = new ArrayList<>(result.content().size());
    for (ToolResult.Content c : result.content()) {
      if (c.binary() && pass.carriesSecret(c.data())) {
        // Bytes can't be masked without breaking them: a block that carries a secret is left out whole.
        content.add(ToolResult.Content.text("[" + c.type() + (c.mimeType() == null ? "" : " " + pass.string(
            c.mimeType())) + " withheld by GateRook: it carried a secret]"));
        continue;
      }
      // A resource link reaches the agent as much as a text does: a presigned URL carries the STS session token.
      content.add(new ToolResult.Content(c.type(), pass.string(c.text()), pass.string(c.uri()),
          pass.string(c.mimeType()), pass.string(c.name()), c.data(),
          c.meta() == null ? null : pass.node(c.meta(), new ArrayList<>(), false)));
    }
    JsonNode structured = result.structuredContent() == null ? null
        : pass.node(maskPaths(result.structuredContent(), result.maskPaths(), pass), new ArrayList<>(), false);
    ToolResult.Table table = result.table() == null ? null : pass.table(result.table());
    JsonNode meta = result.meta() == null ? null : pass.node(result.meta(), new ArrayList<>(), false);
    Outcome outcome = pass.outcome();
    ToolResult masked = result.withMeta(meta).accounted(content, structured, table, outcome.fields(),
        result.resultBytes());
    return new MaskedResult(masked, outcome);
  }

  /**
   * A log message: secret rules and entropy, no taint (the logger doesn't know the call), no personal data. Backs
   * the {@code %mask} conversion word of the core's log pattern.
   */
  public String log(String message) {
    if (message == null || message.isEmpty()) {
      return message;
    }
    return new Pass(fingerprints, null, MaskPolicy.SECRETS_ONLY).string(message);
  }

  /**
   * A URL as a person may see it again: the password of its user info, every path segment, query value and fragment
   * that looks like a secret masked whole, the rest as typed. Some MCP servers take their key in their URL
   * ({@code https://mcp.example.com/<key>/mcp}, {@code ?apiKey=…}), and the URL is stored as typed. Part by part:
   * {@code /} and {@code =} belong to the base64 alphabet, so a random-looking key would take its neighbours with it.
   */
  public String url(String url) {
    if (url == null || url.isEmpty()) {
      return url;
    }
    Pass pass = new Pass(fingerprints, null, MaskPolicy.SECRETS_ONLY);
    int scheme = url.indexOf("://");
    int start = scheme < 0 ? 0 : scheme + 3;
    int pathStart = firstOf(url, start, "/?#");
    int queryStart = firstOf(url, pathStart, "?#");
    int fragmentStart = firstOf(url, queryStart, "#");
    StringBuilder out = new StringBuilder(url.length()).append(url, 0, start);
    String authority = url.substring(start, pathStart);
    int at = authority.lastIndexOf('@');
    if (at >= 0) {
      String user = authority.substring(0, at);
      int colon = user.indexOf(':');
      out.append(colon >= 0 ? user.substring(0, colon + 1) + MASK : masked(pass, user, user)).append('@');
    }
    out.append(authority, at + 1, authority.length());
    String path = url.substring(pathStart, queryStart);
    int segment = 0;
    for (String part : path.split("/", -1)) {
      out.append(segment++ == 0 ? "" : "/").append(masked(pass, part, part));
    }
    if (queryStart < fragmentStart) {
      out.append('?');
      String[] pairs = url.substring(queryStart + 1, fragmentStart).split("&", -1);
      for (int i = 0; i < pairs.length; i++) {
        // Its name stays, unless it is the key: a rule such as apiKey=… needs it, and it says what was masked.
        int eq = pairs[i].indexOf('=');
        String name = eq < 0 ? pairs[i] : pairs[i].substring(0, eq);
        out.append(i == 0 ? "" : "&").append(!masked(pass, name, name).equals(name) ? MASK
            : masked(pass, pairs[i], eq < 0 ? pairs[i] : pairs[i].substring(eq + 1)));
      }
    }
    if (fragmentStart < url.length()) {
      String fragment = url.substring(fragmentStart + 1);
      out.append('#').append(masked(pass, fragment, fragment));
    }
    return out.toString();
  }

  /** {@code part} with {@code secret} (all of it, or its value) masked when anything in {@code part} looks like one. */
  private static String masked(Pass pass, String part, String secret) {
    if (part.isEmpty() || pass.string(part).equals(part)) {
      return part;
    }
    return part.substring(0, part.length() - secret.length()) + MASK;
  }

  /** The index of the first of {@code stops} in {@code text} from {@code from}, or the length of {@code text}. */
  private static int firstOf(String text, int from, String stops) {
    for (int i = from; i < text.length(); i++) {
      if (stops.indexOf(text.charAt(i)) >= 0) {
        return i;
      }
    }
    return text.length();
  }

  /**
   * Whether a value someone typed into a non-secret field looks like a secret ("This looks like a secret: store it as
   * a secret"); the rule id that matched. Uses syntax and optional entropy, not process fingerprints: resolving a
   * password must not make a coincidentally matching hostname or port invalid. Output masking still uses both.
   */
  public Optional<String> looksLikeSecret(String value) {
    return looksLikeSecret(value, true);
  }

  /**
   * The same, optionally without the entropy check: a form field that legitimately holds random-looking text (a CA
   * certificate, a host key fingerprint) is checked against the named rules only.
   */
  public Optional<String> looksLikeSecret(String value, boolean entropy) {
    if (value == null || value.isEmpty()) {
      return Optional.empty();
    }
    MaskCounter counter = new MaskCounter();
    String out = SecretRules.apply(value, MASK, counter);
    if (entropy) {
      EntropyDetector.apply(out, MASK, counter);
    }
    return counter.byRule().keySet().stream().findFirst();
  }

  /** Whether {@code key} names a cursor, handle or digest: random-looking by design, not a secret. */
  static boolean opaqueKey(String key) {
    String k = normalize(key);
    for (String suffix : OPAQUE_SUFFIXES) {
      if (k.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code key} names a secret value. */
  static boolean sensitiveKey(String key) {
    String k = normalize(key);
    if (SENSITIVE_KEYS.contains(k)) {
      return true;
    }
    for (String cursor : CURSOR_SUFFIXES) {
      if (k.endsWith(cursor)) {
        return false;
      }
    }
    for (String suffix : SENSITIVE_SUFFIXES) {
      if (k.endsWith(suffix) && k.length() > suffix.length()) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode maskPaths(JsonNode tree, List<String> pointers, Pass pass) {
    if (pointers == null || pointers.isEmpty()) {
      return tree;
    }
    JsonNode copy = tree.deepCopy();
    for (String pointer : pointers) {
      JsonPointer p;
      try {
        p = JsonPointer.compile(pointer);
      } catch (IllegalArgumentException e) {
        continue;
      }
      JsonNode parent = copy.at(p.head());
      String last = p.last() == null ? null : p.last().getMatchingProperty();
      if (parent instanceof ObjectNode object && last != null && object.has(last) && !object.get(last).isNull()) {
        object.put(last, MASK);
        pass.counter.hit("connector-path");
        pass.fields++;
      } else if (parent instanceof ArrayNode array && p.last() != null && p.last().getMatchingIndex() >= 0
          && p.last().getMatchingIndex() < array.size()) {
        array.set(p.last().getMatchingIndex(), JsonNodeFactory.instance.stringNode(MASK));
        pass.counter.hit("connector-path");
        pass.fields++;
      }
    }
    return copy;
  }

  private static String normalize(String key) {
    StringBuilder out = new StringBuilder(key.length());
    for (int i = 0; i < key.length(); i++) {
      char c = Character.toLowerCase(key.charAt(i));
      if (c != '-' && c != '_' && c != ' ' && c != '.') {
        out.append(c);
      }
    }
    return out.toString();
  }

  /** One masking pass: shares the counter and the policy's compiled field patterns. */
  private static final class Pass {
    /** Property names that came out unchanged; enough for the names of a page of rows, bounded for huge maps. */
    private static final int MAX_CLEAN_NAMES = 4096;
    private static final Set<MaskPolicy.Pii> CARDS = Set.of(MaskPolicy.Pii.CARD);

    final SecretFingerprints fingerprints;
    final Taint taint;
    final MaskPolicy policy;
    final MaskCounter counter = new MaskCounter();
    final List<String[]> fieldPatterns;
    /** Numbers and binary values can only match the taint or a card number: checked when either is in play. */
    final boolean scalars;
    final Set<String> cleanNames = new HashSet<>();
    int fields;

    Pass(SecretFingerprints fingerprints, Taint taint, MaskPolicy policy) {
      this.fingerprints = fingerprints;
      this.taint = taint;
      this.policy = policy;
      this.fieldPatterns = policy.fields().stream().map(f -> f.split("\\.", -1)).toList();
      this.scalars = (taint != null && !taint.isEmpty()) || !fingerprints.isEmpty()
          || policy.pii().contains(MaskPolicy.Pii.CARD);
    }

    Outcome outcome() {
      return fields == 0 ? Outcome.NONE : new Outcome(fields, counter.byRule());
    }

    /**
     * Whether the Base64 of a binary block carries a secret: the taint keeps every secret's Base64 at each alignment,
     * so a secret anywhere in the bytes shows in their Base64; the fingerprints of the process are checked the same
     * way. No rule or entropy check: random bytes look like secrets to both. Counts a field when it does.
     */
    boolean carriesSecret(String base64) {
      if (base64 == null || base64.isEmpty()) {
        return false;
      }
      boolean found = (taint != null && taint.occursIn(base64))
          || !fingerprints.mask(base64, MASK, new MaskCounter()).equals(base64);
      if (found) {
        counter.hit("stored-secret");
        fields++;
      }
      return found;
    }

    /** One string value: taint → rules → entropy → personal data. Counts a field when anything changed. */
    String string(String value) {
      return string(value, policy.entropy());
    }

    String string(String value, boolean entropy) {
      if (value == null || value.isEmpty()) {
        return value;
      }
      String out = value;
      if (taint != null && !taint.isEmpty()) {
        out = maskTaint(out);
      }
      out = fingerprints.mask(out, MASK, counter);
      out = SecretRules.apply(out, MASK, counter);
      if (entropy) {
        out = EntropyDetector.apply(out, MASK, counter);
      }
      if (!policy.pii().isEmpty()) {
        out = PiiRules.apply(out, policy.pii(), MASK, counter);
      }
      if (!out.equals(value)) {
        fields++;
      }
      return out;
    }

    private String maskTaint(String value) {
      String out = value;
      if (taint.occursIn(value)) {
        out = maskForms(out);
      }
      // A connector's cut (…‹truncated 12 chars›, …‹256 bytes›) can fall inside a secret: the start that is left
      // right before the marker is masked when it is long enough to be more than a coincidence.
      int mark = out.indexOf(CUT_MARK);
      while (mark > 0) {
        int prefix = taint.cutPrefix(out, mark);
        if (prefix > 0) {
          out = out.substring(0, mark - prefix) + MASK + out.substring(mark);
          counter.hit("stored-secret");
          mark = mark - prefix + MASK.length();
        }
        mark = out.indexOf(CUT_MARK, mark + 1);
      }
      return out;
    }

    /**
     * {@code value} with every occurrence of every form masked. The occurrences are found in {@code value} as it
     * came, overlapping ones included, and each run they cover becomes one mask: replacing form by form would break
     * an overlapping second form and leave its other end readable.
     */
    private String maskForms(String value) {
      long[] runs = new long[8];
      int count = 0;
      for (char[] form : taint.forms()) {
        int at = indexOf(value, form, 0);
        while (at >= 0) {
          int end = at + form.length;
          int next = indexOf(value, form, at + 1);
          while (next >= 0 && next < end) {
            end = next + form.length;
            next = indexOf(value, form, next + 1);
          }
          if (count == runs.length) {
            runs = Arrays.copyOf(runs, count * 2);
          }
          runs[count++] = ((long) at << 32) | end;
          at = next;
        }
      }
      if (count == 0) {
        return value;
      }
      Arrays.sort(runs, 0, count);
      StringBuilder out = new StringBuilder(value.length());
      int copied = 0;
      int i = 0;
      while (i < count) {
        int start = (int) (runs[i] >>> 32);
        int end = (int) runs[i];
        i++;
        while (i < count && (int) (runs[i] >>> 32) < end) {
          end = Math.max(end, (int) runs[i]);
          i++;
        }
        out.append(value, copied, start).append(MASK);
        counter.hit("stored-secret");
        copied = end;
      }
      return out.append(value, copied, value.length()).toString();
    }

    /**
     * A property or column name: masked like a value, since Redis and MongoDB hashes are keyed by session tokens and
     * a column can be named by anything a query returns. Names repeat on every row, so clean ones are remembered.
     */
    String name(String name) {
      if (name == null || name.isEmpty() || cleanNames.contains(name)) {
        return name;
      }
      String masked = string(name);
      if (masked.equals(name) && cleanNames.size() < MAX_CLEAN_NAMES) {
        cleanNames.add(name);
      }
      return masked;
    }

    JsonNode node(JsonNode node, List<String> path, boolean forced) {
      if (node == null) {
        return null;
      }
      if (node.isObject()) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
          // Decisions use the name as the connector wrote it; only the output carries the masked one.
          path.add(e.getKey());
          boolean force = forced || sensitiveKey(e.getKey()) || fieldMatches(path);
          out.set(unique(out, name(e.getKey())), node(e.getValue(), path, force));
          path.removeLast();
        }
        return out;
      }
      if (node.isArray()) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode(node.size());
        for (JsonNode item : node) {
          out.add(node(item, path, forced));
        }
        return out;
      }
      if (forced && !node.isNull() && !node.isBoolean() && !(node.isString() && node.asString().isEmpty())) {
        if (node.isString() && node.asString().equals(MASK)) {
          return node;
        }
        counter.hit(path.isEmpty() ? "field" : "sensitive-field");
        fields++;
        return JsonNodeFactory.instance.stringNode(MASK);
      }
      if (node.isString()) {
        boolean entropy = policy.entropy() && (path.isEmpty() || !opaqueKey(path.getLast()));
        String masked = string(node.asString(), entropy);
        return masked.equals(node.asString()) ? node : JsonNodeFactory.instance.stringNode(masked);
      }
      if (scalars && (node.isNumber() || node.isBinary())) {
        return scalar(node);
      }
      return node;
    }

    /**
     * A number or binary value: a numeric PIN or account number that was tainted, a card number on prod, a secret
     * serialized as Base64. Matched by its text (plus the plain decimal form of a floating-point number, cut after
     * {@link JsonNumbers#MAX_PADDING} zeros of padding: an exponent never decides how much is written out); any hit
     * replaces the whole value, since half a number means nothing.
     */
    private JsonNode scalar(JsonNode node) {
      String text = node.asString();
      boolean hit;
      if (node.isBinary()) {
        hit = !string(text, false).equals(text);
      } else {
        hit = numberHit(text);
        // NaN and the infinities have no digits to match.
        if (!hit && node.isFloatingPointNumber() && !JsonNumbers.notFinite(node)) {
          String plain = JsonNumbers.checkable(node.decimalValue());
          hit = !plain.equals(text) && numberHit(plain);
        }
        if (hit) {
          fields++;
        }
      }
      return hit ? JsonNodeFactory.instance.stringNode(MASK) : node;
    }

    /**
     * Digits can only match the taint or a card number: the secret rules, entropy and the other kinds of personal
     * data need letters, {@code @} or {@code +}. Skipping them keeps numeric tables cheap.
     */
    private boolean numberHit(String text) {
      String out = text;
      if (taint != null && !taint.isEmpty()) {
        out = maskTaint(out);
      }
      out = fingerprints.mask(out, MASK, counter);
      if (text.length() >= 13 && policy.pii().contains(MaskPolicy.Pii.CARD)) {
        out = PiiRules.apply(out, CARDS, MASK, counter);
      }
      return !out.equals(text);
    }

    /** {@code name}, or {@code name#2}, {@code #3}… when a masked name collides with one the object already has. */
    private static String unique(ObjectNode out, String name) {
      if (!out.has(name)) {
        return name;
      }
      for (int n = 2; ; n++) {
        String candidate = name + "#" + n;
        if (!out.has(candidate)) {
          return candidate;
        }
      }
    }

    ToolResult.Table table(ToolResult.Table table) {
      List<ToolResult.Column> columns = table.columns();
      List<ToolResult.Column> maskedColumns = new ArrayList<>(columns.size());
      boolean[] forced = new boolean[columns.size()];
      for (int i = 0; i < columns.size(); i++) {
        ToolResult.Column c = columns.get(i);
        forced[i] = forcedColumn(c);
        // Column metadata is rendered into the table's text and the structured result: a value like any other.
        maskedColumns.add(new ToolResult.Column(name(c.name()), name(c.type()), name(c.sourceSchema()),
            name(c.sourceTable()), name(c.sourceColumn())));
      }
      ArrayNode rows = JsonNodeFactory.instance.arrayNode(table.rows() == null ? 0 : table.rows().size());
      if (table.rows() != null) {
        for (JsonNode row : table.rows()) {
          if (!row.isArray()) {
            rows.add(node(row, new ArrayList<>(), false));
            continue;
          }
          ArrayNode cells = JsonNodeFactory.instance.arrayNode(row.size());
          for (int i = 0; i < row.size(); i++) {
            List<String> path = new ArrayList<>();
            if (i < columns.size()) {
              path.add(columns.get(i).name());
            }
            cells.add(node(row.get(i), path, i < forced.length && forced[i]));
          }
          rows.add(cells);
        }
      }
      // A cursor is random by design (no entropy check), but a tainted value or a known token in it still goes.
      return new ToolResult.Table(maskedColumns, rows, string(table.nextCursor(), false));
    }

    /**
     * Whether a column is masked whole: its name in the result ({@code SELECT pw AS password}) or its origin
     * ({@code schema.table.column} as far as the driver knows it; an expression has none) is a sensitive key or one
     * of the policy's fields.
     */
    private boolean forcedColumn(ToolResult.Column c) {
      String name = c.name() == null ? "" : c.name();
      if (sensitiveKey(name) || fieldMatches(List.of(name))) {
        return true;
      }
      boolean known = !blank(c.sourceColumn());
      String column = known ? c.sourceColumn() : name;
      List<String> origin = new ArrayList<>(3);
      if (!blank(c.sourceTable())) {
        if (!blank(c.sourceSchema())) {
          origin.add(c.sourceSchema());
        }
        origin.add(c.sourceTable());
      }
      origin.add(column);
      return (known && sensitiveKey(column)) || fieldMatches(origin);
    }

    private static boolean blank(String s) {
      return s == null || s.isBlank();
    }

    /** "Always mask these fields": {@code a.b} matches a path ending in {@code a.b}; {@code *} is any one name. */
    boolean fieldMatches(List<String> path) {
      for (String[] pattern : fieldPatterns) {
        if (matchesSuffix(pattern, path)) {
          return true;
        }
      }
      return false;
    }

    private static boolean matchesSuffix(String[] pattern, List<String> path) {
      int p = pattern.length - 1;
      int s = path.size() - 1;
      while (p >= 0) {
        if (s < 0) {
          // A leading "*" may stand for nothing: *password matches a top-level password too.
          return p == 0 && pattern[0].equals("*");
        }
        if (!pattern[p].equals("*") && !pattern[p].equalsIgnoreCase(path.get(s))) {
          return false;
        }
        p--;
        s--;
      }
      return true;
    }

    private static int indexOf(String text, char[] needle, int from) {
      int max = text.length() - needle.length;
      char first = needle[0];
      for (int i = from; i <= max; i++) {
        if (text.charAt(i) != first) {
          i = text.indexOf(first, i + 1);
          if (i < 0 || i > max) {
            return -1;
          }
        }
        int j = 1;
        while (j < needle.length && text.charAt(i + j) == needle[j]) {
          j++;
        }
        if (j == needle.length) {
          return i;
        }
      }
      return -1;
    }
  }

  @Override
  public String toString() {
    return "Masker" + Arrays.toString(SecretRules.ALL.stream().map(TextRule::id).toArray());
  }
}
