package gaterook.plugins.clickhouse;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Queries over ClickHouse's HTTP interface for one tool call: through the host's guarded client ({@code ctx.egress()}:
 * only the connection's host on the ports the manifest declares, no redirects, no address the policy closes), the
 * statement in the body of a POST, the answer as {@code JSONCompact}.
 *
 * <p>Every query carries the same settings: {@code readonly=1} (ClickHouse refuses anything that writes and any change
 * of settings inside the query), {@code allow_ddl=0}, {@code max_execution_time}, {@code max_result_rows} with
 * {@code result_overflow_mode=break} (a big result is cut, not refused). Values go as query parameters
 * ({@code {name:String}} placeholders with {@code param_name}), never pasted into SQL.
 *
 * <p>Credentials go in {@code X-ClickHouse-User} and {@code X-ClickHouse-Key}: the password is asked of the core only
 * when the first request goes out.
 */
final class ClickHouseClient {

  /** ClickHouse's answers are read up to this size; a larger one is cut and refused. */
  static final long MAX_BYTES = 8L * 1024 * 1024;

  /** {@code Code: 60. DB::Exception: …} — how ClickHouse reports an error in the body. */
  private static final Pattern EXCEPTION = Pattern.compile("Code:\\s*(\\d+)\\.\\s*DB::Exception:\\s*(.*)", Pattern.DOTALL);

  /** Error codes about the account (ClickHouse {@code ErrorCodes.cpp}). */
  static final int UNKNOWN_USER = 192;
  static final int REQUIRED_PASSWORD = 194;
  static final int AUTHENTICATION_FAILED = 516;
  static final int READONLY = 164;
  static final int TIMEOUT_EXCEEDED = 159;
  static final int ACCESS_DENIED = 497;

  /** A table of rows as ClickHouse answered it. */
  record Table(List<String> names, List<String> types, ArrayNode rows, long rowsBeforeLimit, Long rowsRead,
      long elapsedMs) {}

  /** What ClickHouse said when it refused a query: its error code and its words. */
  record Failure(int code, String message, int status) {

    boolean account() {
      return code == UNKNOWN_USER || code == REQUIRED_PASSWORD || code == AUTHENTICATION_FAILED;
    }
  }

  /** One answer: a table, or ClickHouse's refusal. */
  record Answer(Table table, Failure failure) {

    boolean ok() {
      return table != null;
    }
  }

  private final ClickHouseConfig config;
  private final SecretResolver secrets;
  private final ExecContext ctx;
  private String password;

  ClickHouseClient(ClickHouseConfig config, SecretResolver secrets, ExecContext ctx) {
    this.config = config;
    this.secrets = secrets;
    this.ctx = ctx;
  }

  static ClickHouseClient of(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    return new ClickHouseClient(ClickHouseConfig.of(cfg), secrets, ctx);
  }

  ClickHouseConfig config() {
    return config;
  }

  /**
   * Runs {@code sql} with the connection's limits and at most {@code maxRows} rows ({@code maxRows + 1} are asked
   * for, so a cut result is told apart from one that just fits).
   *
   * @param params the values of {@code {name:Type}} placeholders
   */
  Answer query(String sql, Map<String, String> params, int maxRows) throws ConnectorException {
    URI uri = uri(params, maxRows);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Accept", List.of("application/json"));
    headers.put("Content-Type", List.of("text/plain; charset=utf-8"));
    headers.put("X-ClickHouse-User", List.of(config.user()));
    headers.put("X-ClickHouse-Key", List.of(password()));
    long started = System.nanoTime();
    ExecContext.EgressResponse r = ctx.egress().send(new ExecContext.EgressRequest("POST", uri, headers,
        sql.getBytes(StandardCharsets.UTF_8), timeout()), MAX_BYTES);
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    if (r.truncated()) {
      throw ConnectorException.resourceError("ClickHouse's answer is larger than 8 MB: ask for fewer rows or columns.",
          null);
    }
    String text = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
    if (r.status() < 200 || r.status() >= 300) {
      Failure failure = failure(r.status(), text, header(r, "X-ClickHouse-Exception-Code"));
      if (failure != null) {
        return new Answer(null, failure);
      }
      if (r.status() >= 500) {
        throw ConnectorException.unavailable("ClickHouse answered HTTP " + r.status() + ".", null);
      }
      if (r.status() == 401 || r.status() == 403) {
        return new Answer(null, new Failure(AUTHENTICATION_FAILED, "HTTP " + r.status(), r.status()));
      }
      throw ConnectorException.resourceError("ClickHouse answered HTTP " + r.status() + " at " + config.host() + ".",
          null);
    }
    JsonNode json;
    try {
      json = Manifests.JSON.readTree(text);
    } catch (JacksonException e) {
      // An error after the answer had begun: ClickHouse appends it to the stream. A successful answer is read as JSON
      // first, so a value that only looks like an error ("Code: 60. DB::Exception: …" selected as text) stays data.
      Failure late = failure(r.status(), text, null);
      if (late != null) {
        return new Answer(null, late);
      }
      throw ConnectorException.resourceError("ClickHouse's answer isn't JSON. A FORMAT clause in the query changes "
          + "the answer: leave it out.", null);
    }
    List<String> names = new ArrayList<>();
    List<String> types = new ArrayList<>();
    for (JsonNode m : json.path("meta")) {
      names.add(m.path("name").asString(""));
      types.add(m.path("type").asString(""));
    }
    ArrayNode rows = json.path("data") instanceof ArrayNode a ? a : Manifests.JSON.createArrayNode();
    long before = json.path("rows_before_limit_at_least").asLong(rows.size());
    JsonNode stats = json.path("statistics");
    Long read = stats.path("rows_read").isNumber() ? stats.get("rows_read").asLong() : null;
    long ms = stats.path("elapsed").isNumber() ? Math.round(stats.get("elapsed").asDouble() * 1000) : elapsed;
    return new Answer(new Table(List.copyOf(names), List.copyOf(types), rows, before, read, ms), null);
  }

  /** The URL of a query: the database, the format, the limits and the parameters. */
  URI uri(Map<String, String> params, int maxRows) {
    StringBuilder q = new StringBuilder(config.base().toString()).append('?');
    q.append("database=").append(enc(config.database()));
    q.append("&default_format=JSONCompact");
    q.append("&readonly=1");
    q.append("&allow_ddl=0");
    q.append("&max_execution_time=").append(config.maxExecutionSeconds());
    q.append("&max_result_rows=").append(maxRows + 1L);
    q.append("&result_overflow_mode=break");
    if (ctx.callId() != null && ctx.callId().matches("[A-Za-z0-9_-]{1,64}")) {
      q.append("&query_id=").append(enc("mcp-gateway-" + ctx.callId()));
    }
    for (Map.Entry<String, String> p : params.entrySet()) {
      q.append("&param_").append(enc(p.getKey())).append('=').append(enc(p.getValue()));
    }
    return URI.create(q.toString());
  }

  /** ClickHouse's refusal in the body (or its header), else {@code null}. */
  static Failure failure(int status, String body, String headerCode) {
    Matcher m = EXCEPTION.matcher(body);
    if (m.find()) {
      return new Failure(Integer.parseInt(m.group(1)), clean(m.group(2)), status);
    }
    if (headerCode != null && headerCode.matches("\\d{1,6}")) {
      return new Failure(Integer.parseInt(headerCode), clean(body.isBlank() ? "HTTP " + status : body), status);
    }
    return null;
  }

  /** ClickHouse's words without the stack trace, the version and line noise, at most 600 characters. */
  static String clean(String message) {
    String s = message;
    int trace = s.indexOf("Stack trace");
    if (trace >= 0) {
      s = s.substring(0, trace);
    }
    int version = s.lastIndexOf("(version ");
    if (version > 0) {
      s = s.substring(0, version);
    }
    s = s.replaceAll("\\s+", " ").strip();
    return s.length() <= 600 ? s : s.substring(0, 600) + "…";
  }

  /** What remains of the call, at most the query's time limit and a little more, and some left to answer in. */
  private Duration timeout() {
    Duration left = ctx.remaining().minusMillis(200);
    Duration configured = Duration.ofSeconds(config.maxExecutionSeconds() + 5L);
    if (left.isNegative() || left.isZero()) {
      return Duration.ofMillis(100);
    }
    return left.compareTo(configured) < 0 ? left : configured;
  }

  private String password() throws ConnectorException {
    if (password != null) {
      return password;
    }
    try (SecretResolver.Secret secret = secrets.secret(ClickHouseConfig.PASSWORD)) {
      // An HTTP header can't be zeroed: the value lives as long as this call's requests need it.
      password = secret.unsafeString();
      return password;
    }
  }

  private static String header(ExecContext.EgressResponse r, String name) {
    if (r.headers() == null) {
      return null;
    }
    for (Map.Entry<String, List<String>> e : r.headers().entrySet()) {
      if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
        return e.getValue().getFirst();
      }
    }
    return null;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
