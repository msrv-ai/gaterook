package com.gaterook.connectors.logs;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.AwsSpec;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the OpenSearch and Elasticsearch forms (and the legacy Logs form's OpenSearch). On this computer: a server
 * on port 9200 (offered, never filled in); for an Amazon OpenSearch Service domain or Serverless collection, AWS
 * request signing with the region and service its address names, and the AWS profiles; for a server on this computer
 * one look without credentials: its transport (a self-signed certificate ends in a note to paste its CA, never in
 * plaintext), whether it needs credentials, and whether it is the other product. Signed in: who, the version, and what
 * the user may see, dated indices collapsed into patterns; for the legacy log fields also the timestamp, message and
 * severity fields of the mappings.
 */
final class SearchSuggestions {

  /** OpenSearch's and Elasticsearch's default HTTP port. */
  static final int PORT = 9200;
  /** {@code search-<domain>-<id>.<region>.es.amazonaws.com}, {@code vpc-…}, {@code <id>.<region>.aoss.amazonaws.com}. */
  static final Pattern AWS = Pattern.compile("[a-z0-9-]+\\.([a-z]{2}(?:-[a-z]+)+-\\d+)\\.(es|aoss)\\.amazonaws\\.com"
      + "(?:\\.cn)?");

  /** Where log documents usually keep their time, text, level and labels, the most common first. */
  static final List<String> TIMESTAMPS = List.of("@timestamp", "timestamp", "time", "ts", "date", "datetime",
      "event.created", "event.ingested", "eventTime", "logTime");
  static final List<String> MESSAGES = List.of("message", "msg", "log", "log.message", "event.original",
      "log.original", "text", "body", "short_message", "full_message", "line");
  static final List<String> SEVERITIES = List.of("log.level", "level", "severity", "severity_text", "severityText",
      "loglevel", "log_level", "levelname", "level_name", "lvl", "syslog.severity_label");
  static final List<String> LABELS = List.of("service.name", "kubernetes.namespace", "kubernetes.namespace_name",
      "kubernetes.pod.name", "kubernetes.pod_name", "kubernetes.container.name", "kubernetes.container_name",
      "container.name", "host.name", "hostname", "app", "application", "service", "environment", "env",
      "cloud.region");
  private static final Set<String> DATES = Set.of("date", "date_nanos");
  private static final Set<String> TEXTS = Set.of("text", "match_only_text", "keyword", "constant_keyword",
      "wildcard");
  private static final Set<String> KEYWORDS = Set.of("keyword", "constant_keyword");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Option NONE = new Option("none", "No authentication", null);
  private static final Option SIGV4 = new Option("awsSigV4", "AWS request signing", null);

  private SearchSuggestions() {}

  static void local(ConnectionConfig cfg, ExecContext ctx, CloudLocal local, Suggestions.Builder out) {
    JsonNode c = cfg.config();
    boolean elastic = "elasticsearch".equals(LogsSuggestions.text(c, "/backend"));
    String mode = Optional.ofNullable(LogsSuggestions.text(c, "/auth/mode")).orElse("basic");
    String region = null;
    String url = LogsSuggestions.text(c, "/url");
    if (url == null) {
      Loopback.offer(out, "/url", "A server", PORT, ctx);
    } else {
      try {
        URI base = Settings.baseUrl(url, false);
        String host = base.getHost().toLowerCase(Locale.ROOT);
        Matcher aws = AWS.matcher(host);
        if (!elastic && aws.matches()) {
          region = aws.group(1);
          boolean serverless = aws.group(2).equals("aoss");
          out.value("/auth/mode", "the URL", "awsSigV4", List.of(SIGV4));
          out.value("/auth/awsService", "the URL", serverless ? "aoss" : "es", List.of(serverless
              ? new Option("aoss", "OpenSearch Serverless", null) : new Option("es", "OpenSearch Service", null)));
          out.note((serverless ? "An OpenSearch Serverless collection in " + region + ": its requests are signed "
              + "with AWS credentials." : "An Amazon OpenSearch Service domain in " + region + ": GateRook signs its "
              + "requests with AWS credentials, unless the domain signs in users of its own."));
          mode = "awsSigV4";
        } else if (Loopback.host(host)) {
          onThisComputer(base, elastic, LogsSuggestions.text(c, "/caCertificate"), ctx, out);
        }
      } catch (IllegalArgumentException e) {
        // Still typing the address.
      }
    }
    if (!elastic && mode.equals("awsSigV4")) {
      String profile = LogsSuggestions.text(c, "/auth/awsCredentials/profile");
      local.aws(out, "/auth/awsCredentials/profile", profile, false);
      String r = region != null ? region : local.awsRegion(profile);
      out.value("/auth/awsRegion", region != null ? "the URL" : r != null ? "~/.aws/config" : "the AWS regions", r,
          local.awsRegionOptions());
    }
  }

  /** One look at a server on this computer, without credentials: its transport, then whether it needs a sign-in. */
  private static void onThisComputer(URI base, boolean elastic, String ca, ExecContext ctx, Suggestions.Builder out) {
    Loopback.Answer a = Loopback.ask(base, "/", Map.of(), ca, elastic ? "elasticsearch" : "opensearch", "/url", ctx,
        out);
    if (a == null) {
      return;
    }
    String address = Settings.hostPort(base);
    if (a.status() == 200) {
      JsonNode root = tree(a.body());
      String distribution = distribution(root);
      if (distribution == null) {
        out.note(address + " answers, but not as OpenSearch or Elasticsearch. Check the port.");
        return;
      }
      String version = root.at("/version/number").asString("");
      out.value("/auth/mode", distribution + " on " + address, "none", List.of(NONE));
      out.note(distribution + (version.isBlank() ? "" : " " + version) + " on " + address
          + " answers without credentials.");
      mismatch(distribution, elastic, out);
    } else if (a.status() == 401) {
      // Elasticsearch explains itself in JSON; OpenSearch's security plugin answers "Unauthorized".
      boolean es = a.body().contains("security_exception") || (elastic && !a.body().contains("OpenSearch"));
      out.options("/auth/username", "this computer", List.of(es
          ? new Option("elastic", null, "Elasticsearch's built-in superuser")
          : new Option("admin", null, "the demo setup's administrator")));
      out.note(address + " asks for credentials.");
      if (es) {
        mismatch("Elasticsearch", elastic, out);
      }
    }
  }

  /**
   * Signed in as Test connection does: who, the version, what the user may see (dated indices as patterns); for the
   * legacy Logs form, whose log fields these are, also index patterns for them and the fields of their mappings. Its
   * index patterns may still be empty ({@code x-suggest}): the cluster is then read as a whole.
   */
  static void service(ConnectionConfig cfg, Backend.Call call, Suggestions.Builder out) throws ConnectorException {
    JsonNode c = cfg.config();
    List<String> indices = LogsSuggestions.strings(c, "/indices");
    ConnectionConfig parsed = cfg;
    if (indices.isEmpty() && c instanceof ObjectNode o && "opensearch".equals(LogsSuggestions.text(c, "/backend"))) {
      // Scoped log search needs index patterns; the reads below don't, and they sign with the read-only policy.
      ObjectNode relaxed = o.deepCopy();
      relaxed.remove("indices");
      relaxed.put("apiAccess", "full");
      parsed = new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version(), relaxed,
          cfg.secretFields(), cfg.metadata());
    }
    service(LogsSuggestions.settings(parsed, Settings.OpenSearch.class), indices, call,
        cfg.type().equals(LogsConnector.TYPE), out);
  }

  private static void service(Settings.OpenSearch s, List<String> indices, Backend.Call call, boolean logFields,
      Suggestions.Builder out) throws ConnectorException {
    if (s.auth().mode() == Settings.AuthMode.AWS_SIGV4 && s.awsCredentials().source() == AwsSpec.Source.PROFILE
        && LogsSuggestions.awsProfileRunsCommand(call.cloud().endpoints(), s.awsCredentials().profile())) {
      out.note("The profile " + s.awsCredentials().profile() + " runs a command to get credentials, which GateRook "
          + "doesn't do for suggestions. Test connection runs it and checks the domain.");
      return;
    }
    OpenSearchBackend search = new OpenSearchBackend(s, call);
    JsonNode root = search.cluster();
    String distribution = Optional.ofNullable(distribution(root)).orElse(s.title());
    String version = root.at("/version/number").asString("");
    String user = search.user();
    JsonNode resolved;
    try {
      resolved = search.resolve("*");
    } catch (ConnectorException e) {
      resolved = null;
    }
    String who = "Signed in to " + distribution + (version.isBlank() ? "" : " " + version)
        + (user == null ? "" : " as " + user);
    List<IndexPatterns.Group> groups = resolved == null ? List.of() : IndexPatterns.of(resolved);
    String summary = resolved == null ? null : IndexPatterns.summary(resolved);
    if (resolved == null) {
      out.note(who + ", but listing its indices failed.");
    } else if (summary == null) {
      out.note(who + ": no indices yet.");
    } else {
      out.note(who + ": " + summary + " (" + LogsSuggestions.some(groups.stream().map(IndexPatterns.Group::pattern)
          .toList(), 4) + ").");
    }
    mismatch(distribution, s.backend().equals("elasticsearch"), out);
    if (logFields) {
      logFields(search, s, indices, groups, out);
    }
  }

  /**
   * The legacy Logs form's OpenSearch: index patterns, and the time, text, level and label fields of the mappings of
   * the patterns named, or while none is, of the first that looks like logs.
   */
  private static void logFields(OpenSearchBackend search, Settings.OpenSearch s, List<String> named,
      List<IndexPatterns.Group> groups, Suggestions.Builder out) {
    out.field("/indices", new Suggestions.Field(groups.stream().map(g -> new Option(g.pattern(), null, g.detail()))
        .toList(), null, "the cluster", true));
    List<String> indices = !named.isEmpty() ? named : groups.stream().filter(IndexPatterns.Group::logs)
        .map(IndexPatterns.Group::pattern).limit(1).toList();
    if (indices.isEmpty()) {
      return;
    }
    JsonNode caps;
    try {
      caps = search.fieldCaps(indices);
    } catch (ConnectorException e) {
      return;
    }
    Map<String, Set<String>> types = new TreeMap<>();
    caps.path("fields").properties().forEach(f -> {
      Set<String> t = new LinkedHashSet<>();
      f.getValue().properties().forEach(byType -> t.add(byType.getKey()));
      types.put(f.getKey(), t);
    });
    if (types.isEmpty()) {
      return;
    }
    String scope = "the mappings of " + String.join(", ", indices);
    String timestamp = field(out, "/timestampField", s.timestampField(), TIMESTAMPS, types, DATES, scope, true);
    if (timestamp != null && !timestamp.equals(s.timestampField())) {
      out.note(String.join(", ", indices) + " has no date field named " + s.timestampField() + ": set Timestamp "
          + "field to " + timestamp + ".");
    }
    field(out, "/messageField", s.messageField(), MESSAGES, types, TEXTS, scope, false);
    field(out, "/severityField", s.severityField(), SEVERITIES, types, TEXTS, scope, false);
    List<Option> labels = new ArrayList<>();
    for (String l : LABELS) {
      if (types.containsKey(l) && types.get(l).stream().anyMatch(KEYWORDS::contains)) {
        labels.add(Option.of(l));
      }
    }
    out.options("/labelFields", scope, labels);
  }

  /**
   * The fields of {@code types} of a kind ({@code kinds}), the usual names first, and the one to fill in: the
   * configured field when the mappings have it, else the first usual name they have (for a timestamp also the only
   * date field). Returns that value, or {@code null}.
   */
  private static String field(Suggestions.Builder out, String pointer, String configured, List<String> usual,
      Map<String, Set<String>> types, Set<String> kinds, String scope, boolean onlyOne) {
    Set<String> fitting = new LinkedHashSet<>();
    for (String name : usual) {
      if (fits(types.get(name), kinds)) {
        fitting.add(name);
      }
    }
    types.forEach((name, t) -> {
      if (fits(t, kinds)) {
        fitting.add(name);
      }
    });
    if (fitting.isEmpty()) {
      return null;
    }
    String value = fitting.contains(configured) ? configured : usual.stream().filter(fitting::contains).findFirst()
        .orElse(onlyOne && fitting.size() == 1 ? fitting.iterator().next() : null);
    out.value(pointer, scope, value, fitting.stream().map(n -> Option.of(n, String.join(", ", types.get(n))))
        .toList());
    return value;
  }

  private static boolean fits(Set<String> types, Set<String> kinds) {
    return types != null && types.stream().anyMatch(kinds::contains);
  }

  /** {@code OpenSearch} or {@code Elasticsearch} from {@code GET /}, or {@code null} for another server. */
  static String distribution(JsonNode root) {
    if ("opensearch".equals(root.at("/version/distribution").asString(""))) {
      return "OpenSearch";
    }
    return root.at("/version/number").isString() ? "Elasticsearch" : null;
  }

  /** The other product's server: its own connector speaks its API. */
  private static void mismatch(String distribution, boolean elastic, Suggestions.Builder out) {
    if (elastic && distribution.equals("OpenSearch")) {
      out.note("This server is OpenSearch: use the OpenSearch connector, whose tools speak its API.");
    } else if (!elastic && distribution.equals("Elasticsearch")) {
      out.note("This server is Elasticsearch: use the Elasticsearch connector, whose tools speak its API.");
    }
  }

  private static JsonNode tree(String body) {
    try {
      JsonNode n = JSON.readTree(body);
      return n == null ? JSON.createObjectNode() : n;
    } catch (JacksonException e) {
      return JSON.createObjectNode();
    }
  }
}
