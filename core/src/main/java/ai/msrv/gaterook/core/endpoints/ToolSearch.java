package ai.msrv.gaterook.core.endpoints;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The search behind {@code find_tools} (ARCHITECTURE.md §5.7): BM25 over what an AI app would read of each tool the
 * model may call, with fields weighted by how much they say about the tool. Its name (split at {@code _} and at
 * camelCase), title, connections and their types, argument names, the description its connector gives, and the
 * descriptions of its arguments. Words are lowercased and lightly stemmed, common English words are dropped, and a
 * query word also matches a few words of the same meaning ({@code get} and {@code fetch}, {@code issue} and
 * {@code ticket}). A query word inside a tool's name counts too, because an MCP server's names come lowercased
 * ({@code searchjiraissuesusingjql}). The tool whose name or title is the query comes first. The gateway's own tools and
 * those only an MCP App may call are left out. Built once per catalog, so per endpoint snapshot; a query reads every
 * tool once, well under a millisecond for a few thousand.
 */
public final class ToolSearch {

  public static final int DEFAULT_LIMIT = 8;
  public static final int MAX_LIMIT = 20;
  public static final int MAX_NAMES = 10;

  /** One match and its score. */
  public record Hit(ToolCatalog.PublishedTool tool, double score) {}

  /** The best matches of one query, at most the limit asked for, and how many tools matched in all. */
  public record Result(List<Hit> hits, int total) {}

  private static final double K1 = 1.2;
  private static final double B = 0.75;
  private static final double NAME = 3.0;
  private static final double TITLE = 2.0;
  private static final double CONNECTION = 1.5;
  private static final double ARGUMENT = 1.0;
  private static final double DESCRIPTION = 1.0;
  private static final double ARGUMENT_DESCRIPTION = 0.5;
  /** What a word of the same meaning counts for, against the query's own word. */
  private static final double SYNONYM = 0.6;
  /** What a query word inside a tool's name counts for, against the rarest word. */
  private static final double INSIDE_NAME = 0.8;
  /** What a query word that is a word of the tool's name or title adds, against the rarest word. */
  private static final double IN_NAME = 2.0;

  private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
  private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

  private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "of", "to", "for", "in", "on", "at", "by",
      "with", "and", "or", "is", "are", "be", "this", "that", "it", "its", "from", "as", "into", "via", "use",
      "using", "all", "any", "each", "my", "me", "i", "you", "your", "we", "our", "can", "do", "does", "how", "what",
      "which", "when", "where", "who", "will", "should", "would", "please", "tool", "tools", "one", "some");

  private static final List<List<String>> SYNONYMS = List.of(
      List.of("get", "fetch", "read", "show", "view", "retrieve", "load", "describe", "detail", "info", "inspect"),
      List.of("list", "find", "search", "query", "lookup", "browse", "scan", "filter", "discover"),
      List.of("create", "add", "new", "insert", "make", "post"),
      List.of("update", "edit", "modify", "change", "set", "patch", "rename"),
      List.of("delete", "remove", "drop", "destroy", "erase", "purge"),
      List.of("run", "execute", "exec", "call", "invoke", "trigger", "start"),
      List.of("issue", "ticket", "bug", "incident", "problem"),
      List.of("page", "document", "doc", "article", "wiki", "content"),
      List.of("log", "event"),
      List.of("table", "schema", "column", "collection", "index"),
      List.of("database", "db", "sql"),
      List.of("message", "email", "mail", "reply"),
      List.of("user", "member", "person", "people", "account", "profile"),
      List.of("comment", "note", "remark"),
      List.of("error", "exception", "crash", "failure", "fault"),
      List.of("repo", "repository"),
      List.of("alert", "monitor", "alarm", "notification"),
      List.of("metric", "stat", "statistic", "measurement", "usage"),
      List.of("host", "server", "machine", "instance", "vm"));

  private static final Map<String, Set<String>> SAME_MEANING = sameMeaning();

  private record Doc(ToolCatalog.PublishedTool tool, Map<String, Double> tf, double length, String compactName,
      Set<String> nameTerms, Set<String> connections) {}

  private final List<Doc> docs;
  private final Map<String, Integer> df;
  private final double averageLength;

  private ToolSearch(List<Doc> docs) {
    this.docs = docs;
    Map<String, Integer> counts = new HashMap<>();
    double total = 0;
    for (Doc d : docs) {
      d.tf().keySet().forEach(t -> counts.merge(t, 1, Integer::sum));
      total += d.length();
    }
    this.df = counts;
    this.averageLength = docs.isEmpty() ? 1 : Math.max(1, total / docs.size());
  }

  /** The search over {@code tools}: those the model may call, without the gateway's own. */
  static ToolSearch of(List<ToolCatalog.PublishedTool> tools) {
    List<Doc> docs = new ArrayList<>();
    for (ToolCatalog.PublishedTool t : tools) {
      if (searchable(t)) {
        docs.add(doc(t));
      }
    }
    return new ToolSearch(List.copyOf(docs));
  }

  /** Whether {@code find_tools} finds it: a tool of a connection that the model may call. */
  public static boolean searchable(ToolCatalog.PublishedTool t) {
    return !t.core() && !t.appOnly();
  }

  /**
   * Whether it only reads: every connection's tool of that name is a READ, so a change can't come of it. A tool that
   * decides per call ({@code ssh_run}, {@code http_request}) can change data.
   */
  public static boolean readOnly(ToolCatalog.PublishedTool t) {
    return !t.core() && !t.pairs().isEmpty() && t.pairs().stream().noneMatch(p -> p.tool().mutating());
  }

  /** How many tools it searches. */
  public int size() {
    return docs.size();
  }

  /**
   * The best {@code limit} matches of {@code query}, only of {@code connection} unless it is {@code null}: best first,
   * then by name.
   */
  public Result search(String query, String connection, int limit) {
    String asked = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    List<String> raw = rawWords(asked);
    List<String> terms = new ArrayList<>(new LinkedHashSet<>(terms(asked)));
    List<Hit> hits = new ArrayList<>();
    for (Doc d : docs) {
      if (connection != null && !d.connections().contains(connection)) {
        continue;
      }
      double score = 0;
      int matched = 0;
      for (String term : terms) {
        double best = bm25(d, term);
        for (String same : SAME_MEANING.getOrDefault(term, Set.of())) {
          best = Math.max(best, SYNONYM * bm25(d, same));
        }
        if (d.nameTerms().contains(term)) {
          // The name says what the tool is for; a description mentions much else.
          best += IN_NAME * idf(term);
        }
        score += best;
        matched += best > 0 ? 1 : 0;
      }
      for (String word : raw) {
        String stemmed = stem(word);
        if (word.length() >= 3 && !STOPWORDS.contains(word) && !d.nameTerms().contains(stemmed)
            && d.compactName().contains(word)) {
          score += INSIDE_NAME * idf(stemmed);
        }
      }
      if (!terms.isEmpty()) {
        // A tool that matches more of the query's words comes before one that matches a few of them often.
        score *= 0.7 + 0.3 * matched / terms.size();
      }
      String name = d.tool().name().toLowerCase(Locale.ROOT);
      String title = d.tool().title() == null ? "" : d.tool().title().strip().toLowerCase(Locale.ROOT);
      if (!asked.isEmpty() && (name.equals(asked) || name.endsWith("_" + asked) || title.equals(asked))) {
        score += 1_000;
      }
      if (score > 0) {
        hits.add(new Hit(d.tool(), score));
      }
    }
    hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h -> h.tool().name()));
    int n = Math.max(1, Math.min(limit, MAX_LIMIT));
    return new Result(List.copyOf(hits.subList(0, Math.min(n, hits.size()))), hits.size());
  }

  private double bm25(Doc d, String term) {
    Double tf = d.tf().get(term);
    if (tf == null) {
      return 0;
    }
    double norm = K1 * (1 - B + B * d.length() / averageLength);
    return idf(term) * tf * (K1 + 1) / (tf + norm);
  }

  /** Rarer words weigh more; a word no tool has weighs most. */
  private double idf(String term) {
    int n = df.getOrDefault(term, 0);
    return Math.log(1 + (docs.size() - n + 0.5) / (n + 0.5));
  }

  private static Doc doc(ToolCatalog.PublishedTool t) {
    Map<String, Double> tf = new HashMap<>();
    double[] length = {0};
    java.util.function.BiConsumer<String, Double> add = (text, weight) -> {
      for (String term : terms(text)) {
        tf.merge(term, weight, Double::sum);
        length[0] += weight;
      }
    };
    add.accept(t.name(), NAME);
    add.accept(t.title(), TITLE);
    Set<String> connections = new HashSet<>();
    Set<String> kinds = new LinkedHashSet<>();
    for (EndpointSnapshot.Pair p : t.pairs()) {
      connections.add(p.connectionName());
      kinds.add(p.connectionName());
      if (p.manifest() != null && p.manifest().name() != null) {
        kinds.add(p.manifest().name());
      }
    }
    kinds.forEach(k -> add.accept(k, CONNECTION));
    // What its connector says: the published description adds the gateway's own notes, the same for every tool.
    add.accept(t.pairs().isEmpty() ? t.description() : t.pairs().get(0).tool().description(), DESCRIPTION);
    JsonNode properties = t.clientInputSchema() == null ? null : t.clientInputSchema().path("properties");
    if (properties != null && properties.isObject()) {
      for (Map.Entry<String, JsonNode> e : properties.properties()) {
        if (e.getKey().equals("connection")) {
          continue;
        }
        add.accept(e.getKey(), ARGUMENT);
        add.accept(e.getValue().path("description").asString(""), ARGUMENT_DESCRIPTION);
      }
    }
    String compact = t.name().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    // An MCP server's title is often its own camelCase name (getProfile), which the lowercased name hides.
    Set<String> named = new HashSet<>(terms(t.name()));
    named.addAll(terms(t.title()));
    return new Doc(t, Map.copyOf(tf), length[0], compact, Set.copyOf(named), Set.copyOf(connections));
  }

  /** The words of {@code text} as the search compares them: split, lowercased, without common words, stemmed. */
  static List<String> terms(String text) {
    List<String> out = new ArrayList<>();
    for (String word : rawWords(text == null ? "" : CAMEL.matcher(text).replaceAll(" "))) {
      if (word.length() > 1 && !STOPWORDS.contains(word)) {
        out.add(stem(word));
      }
    }
    return out;
  }

  private static List<String> rawWords(String text) {
    List<String> out = new ArrayList<>();
    for (String word : SEPARATORS.split(text.toLowerCase(Locale.ROOT))) {
      if (!word.isEmpty()) {
        out.add(word);
      }
    }
    return out;
  }

  /**
   * A light English stemmer, the same for tools and queries: plurals, then {@code -ing} and {@code -ed}, then a final
   * {@code e}, so that {@code update}, {@code updates}, {@code updated} and {@code updating} meet. Short words stay.
   */
  static String stem(String word) {
    String w = word;
    if (w.length() <= 3) {
      return w;
    }
    if (w.endsWith("ies") && w.length() > 4) {
      w = w.substring(0, w.length() - 3) + "y";
    } else if (w.endsWith("sses")) {
      w = w.substring(0, w.length() - 2);
    } else if (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("zes")) {
      w = w.substring(0, w.length() - 2);
    } else if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
      w = w.substring(0, w.length() - 1);
    }
    if (w.endsWith("ing") && w.length() - 3 >= 3) {
      w = undouble(w.substring(0, w.length() - 3));
    } else if (w.endsWith("ed") && w.length() - 2 >= 3) {
      w = undouble(w.substring(0, w.length() - 2));
    }
    if (w.endsWith("e") && w.length() - 1 >= 3) {
      w = w.substring(0, w.length() - 1);
    }
    return w;
  }

  /** {@code stopp} → {@code stop}, {@code runn} → {@code run}; {@code ll}, {@code ss} and {@code zz} stay. */
  private static String undouble(String w) {
    int n = w.length();
    if (n >= 4 && w.charAt(n - 1) == w.charAt(n - 2) && "aeioulsz".indexOf(w.charAt(n - 1)) < 0) {
      return w.substring(0, n - 1);
    }
    return w;
  }

  private static Map<String, Set<String>> sameMeaning() {
    Map<String, Set<String>> out = new HashMap<>();
    for (List<String> group : SYNONYMS) {
      Set<String> stems = new LinkedHashSet<>();
      group.forEach(w -> stems.add(stem(w)));
      for (String s : stems) {
        Set<String> others = new LinkedHashSet<>(stems);
        others.remove(s);
        out.merge(s, others, (a, b) -> {
          Set<String> both = new LinkedHashSet<>(a);
          both.addAll(b);
          return both;
        });
      }
    }
    Map<String, Set<String>> frozen = new HashMap<>();
    out.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
    return Map.copyOf(frozen);
  }
}
