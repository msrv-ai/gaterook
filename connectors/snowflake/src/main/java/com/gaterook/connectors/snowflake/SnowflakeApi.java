package com.gaterook.connectors.snowflake;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.OutputSanitizer;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLHandshakeException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Snowflake's SQL API v2 on {@code java.net.http}: {@code POST /api/v2/statements} runs one statement, {@code GET
 * /api/v2/statements/<handle>} polls a statement that runs longer than the API waits and reads the partitions of its
 * result after the first, {@code POST …/cancel} stops one. The address is the connection's account, never one a
 * response names; redirects are never followed, no proxy is used, and nothing is retried. Every request runs against
 * the call's deadline, and an answer, decompressed, is read up to {@link #MAX_BODY}.
 *
 * <p>Each statement sends {@code MULTI_STATEMENT_COUNT=1}, so Snowflake itself refuses a text that holds more than one
 * statement, a {@code QUERY_TAG} that names GateRook and the call, and a timeout that ends before the call does.
 */
final class SnowflakeApi {

  static final String STATEMENTS = "/api/v2/statements";
  /** Snowflake cuts results into partitions of a few MB; their JSON is read whole. */
  static final long MAX_BODY = 16L * 1024 * 1024;
  static final Duration MARGIN = Duration.ofMillis(150);
  /** The SQL API waits up to 45 seconds for a statement before it answers 202; a request waits a little longer. */
  static final Duration MAX_REQUEST = Duration.ofSeconds(50);
  static final String USER_AGENT = "GateRook/" + BuiltIn.VERSION;
  private static final Pattern HANDLE = Pattern.compile("[0-9A-Za-z-]{1,128}");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.NEVER)
      .proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  /** Tests only: where the fake SQL API listens instead of the account's address. */
  private final URI testBase;

  SnowflakeApi(URI testBase) {
    this.testBase = testBase;
  }

  /** One call's way to Snowflake: the connection, its settings, the call's context and its credential. */
  record Call(ConnectionConfig cfg, SnowflakeSettings settings, ExecContext ctx, SnowflakeAuth.Credential credential,
      String tool) {}

  /**
   * One statement to run.
   *
   * @param bindings  values of its {@code ?} placeholders, in order, as SQL API bindings
   * @param database  the database it runs in instead of the connection's; {@code null}: the connection's
   * @param catalog   a statement GateRook wrote itself over INFORMATION_SCHEMA: the connection's schema isn't sent
   * @param rows      the most rows Snowflake keeps of its result ({@code ROWS_PER_RESULTSET}); 0: no limit
   * @param change    whether it changes data: a request whose answer is lost leaves the outcome unknown
   */
  record Statement(String sql, ArrayNode bindings, String database, boolean catalog, int rows, boolean change) {

    static Statement read(String sql, int rows) {
      return new Statement(sql, null, null, false, rows, false);
    }

    static Statement catalog(String sql, List<String> values, String database, int rows) {
      ArrayNode b = F.arrayNode();
      values.forEach(b::add);
      return new Statement(sql, b, database, true, rows, false);
    }

    static Statement change(String sql) {
      return new Statement(sql, null, null, false, 0, true);
    }
  }

  /** What Snowflake answered to a statement: its ResultSet, partition 0 included. */
  record Answer(JsonNode body, String handle) {

    JsonNode rowType() {
      return body.path("resultSetMetaData").path("rowType");
    }

    JsonNode data() {
      return body.path("data");
    }

    /** How many partitions the result has: the first is in this answer. */
    int partitions() {
      return Math.max(1, body.path("resultSetMetaData").path("partitionInfo").size());
    }

    /** Rows of the whole result, or -1 when Snowflake doesn't say. */
    long numRows() {
      return body.path("resultSetMetaData").path("numRows").asLong(-1);
    }

    JsonNode stats() {
      return body.path("stats");
    }

    String message() {
      return body.path("message").asString("");
    }
  }

  /** A failure with what Snowflake said: its HTTP status and code, for Test connection's verdict. */
  static final class Failure extends ConnectorException {
    private static final long serialVersionUID = 1L;
    final int status;
    final String code;
    final boolean signIn;

    Failure(Kind kind, String message, int status, String code, boolean signIn) {
      super(kind, message, null);
      this.status = status;
      this.code = code;
      this.signIn = signIn;
    }
  }

  URI base(SnowflakeSettings s) {
    return testBase != null ? testBase : s.base();
  }

  /** Stops the client: requests still running end with an error. */
  void close() {
    http.shutdownNow();
  }

  /** Runs one statement and waits for its answer, polling while Snowflake still runs it. */
  Answer run(Call c, Statement st) throws ConnectorException {
    SnowflakeSettings s = c.settings();
    ObjectNode body = F.objectNode().put("statement", st.sql());
    body.put("timeout", timeoutSeconds(c));
    String database = st.database() != null ? st.database() : s.database();
    if (database != null) {
      body.put("database", database);
    }
    if (!st.catalog() && st.database() == null && s.schema() != null) {
      body.put("schema", s.schema());
    }
    if (s.warehouse() != null) {
      body.put("warehouse", s.warehouse());
    }
    if (s.role() != null) {
      body.put("role", s.role());
    }
    if (st.bindings() != null && !st.bindings().isEmpty()) {
      ObjectNode bindings = body.putObject("bindings");
      for (int i = 0; i < st.bindings().size(); i++) {
        bindings.putObject(String.valueOf(i + 1)).put("type", "TEXT").put("value", st.bindings().get(i).asString());
      }
    }
    ObjectNode parameters = body.putObject("parameters");
    parameters.put("MULTI_STATEMENT_COUNT", "1");
    parameters.put("QUERY_TAG", tag(c));
    if (st.rows() > 0) {
      parameters.put("ROWS_PER_RESULTSET", String.valueOf(st.rows()));
    }
    URI uri = base(s).resolve(STATEMENTS + "?requestId=" + UUID.randomUUID());
    Response r = send(c, uri, JSON.writeValueAsString(body), st.change());
    String handle = null;
    long sleep = 100;
    while (true) {
      if (r.status() == 200) {
        return new Answer(r.tree(), r.tree().path("statementHandle").asString(handle));
      }
      boolean running = r.status() == 202 || (handle != null && r.status() == 429);
      if (!running) {
        throw error(r.status(), r.tree(), c, st.change());
      }
      String h = r.tree().path("statementHandle").asString(handle);
      if (h == null || !HANDLE.matcher(h).matches()) {
        throw unknownOrUnavailable(st.change(), "Snowflake accepted the statement without a handle to follow it.");
      }
      handle = h;
      Duration left = c.ctx().remaining().minus(MARGIN);
      if (left.toMillis() < sleep + 250) {
        cancel(c, handle);
        if (st.change()) {
          throw ConnectorException.outcomeUnknown("Snowflake didn't finish the change in time, so whether it ran is "
              + "not known; GateRook asked it to cancel the statement. Check the data before running it again.", null);
        }
        throw ConnectorException.timeout("Snowflake didn't finish the statement in time; GateRook canceled it. Narrow "
            + "the query, or ask a person to raise the endpoint's timeout.");
      }
      pause(sleep, st.change());
      sleep = Math.min(1_000, sleep * 2);
      r = send(c, base(s).resolve(STATEMENTS + "/" + handle), null, st.change());
    }
  }

  /** The rows of partition {@code n} (from 1) of a statement's result. */
  JsonNode partition(Call c, String handle, int n) throws ConnectorException {
    if (handle == null || !HANDLE.matcher(handle).matches()) {
      throw ConnectorException.resourceError("Snowflake gave no handle to read the rest of the result.", null);
    }
    Response r = send(c, base(c.settings()).resolve(STATEMENTS + "/" + handle + "?partition=" + n), null, false);
    if (r.status() != 200) {
      throw error(r.status(), r.tree(), c, false);
    }
    return r.tree().path("data");
  }

  /** Asks Snowflake to stop a statement; best effort, within two seconds, whatever the call's deadline. */
  void cancel(Call c, String handle) {
    if (handle == null || !HANDLE.matcher(handle).matches()) {
      return;
    }
    try {
      HttpRequest request = request(c, base(c.settings()).resolve(STATEMENTS + "/" + handle + "/cancel"),
          Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      http.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (IOException e) {
      // The statement ends at its own timeout.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** A call's statement timeout in seconds: the stricter of the settings' and the policy's, before the deadline. */
  static int timeoutSeconds(Call c) {
    int statement = c.settings().statementTimeout(c.ctx().limits().statementTimeout());
    long left = c.ctx().remaining().minus(Duration.ofSeconds(1)).toSeconds();
    return (int) Math.max(1, Math.min(statement, left));
  }

  /** {@code {"app":"GateRook","tool":…,"call":…}}: the call that ran a statement, in Snowflake's query history. */
  static String tag(Call c) {
    return F.objectNode().put("app", "GateRook").put("tool", c.tool()).put("call", c.ctx().callId()).toString();
  }

  private record Response(int status, JsonNode tree) {}

  private HttpRequest.Builder request(Call c, URI uri, Duration timeout) {
    return HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Authorization", "Bearer " + c.credential().token())
        .header("X-Snowflake-Authorization-Token-Type", c.credential().type())
        .header("Accept", "application/json")
        .header("Accept-Encoding", "gzip")
        .header("User-Agent", USER_AGENT)
        .header("Content-Type", "application/json");
  }

  private Response send(Call c, URI uri, String json, boolean change) throws ConnectorException {
    Duration left = c.ctx().remaining().minus(MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("The call ran out of time before Snowflake was asked.");
    }
    HttpRequest.Builder b = request(c, uri, left.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : left);
    b = json == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(json));
    String host = c.settings().host();
    HttpResponse<InputStream> response;
    try {
      response = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to Snowflake at " + host + " (timed out). Check the account "
          + "identifier and that this computer can reach Snowflake.", e);
    } catch (HttpTimeoutException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Snowflake didn't answer the change in time, so whether it ran is not "
            + "known. Check the data before running it again.", e);
      }
      throw ConnectorException.timeout("Snowflake didn't answer in time.");
    } catch (ConnectException | SSLHandshakeException e) {
      // Before the request was sent: nothing ran.
      throw ConnectorException.unavailable("Can't connect to Snowflake at " + host + ": " + reason(e) + ".", e);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to Snowflake failed during the change, so whether it "
            + "ran is not known. Check the data before running it again.", e);
      }
      throw ConnectorException.unavailable("The connection to Snowflake failed: " + reason(e) + ".", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (change) {
        throw ConnectorException.outcomeUnknown("The change was cancelled while Snowflake ran it, so whether it ran "
            + "is not known.", e);
      }
      throw ConnectorException.timeout("The call was cancelled.");
    }
    byte[] bytes;
    try {
      bytes = read(response);
    } catch (TooLarge e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Snowflake's answer to the change is larger than " + (MAX_BODY >> 20)
            + " MiB, so whether it ran is not known.", null);
      }
      throw ConnectorException.resourceError("Snowflake's answer is larger than " + (MAX_BODY >> 20) + " MiB: select "
          + "fewer or narrower columns, or fewer rows.", null);
    } catch (IOException e) {
      if (change) {
        throw ConnectorException.outcomeUnknown("The connection to Snowflake failed while it answered the change, so "
            + "whether it ran is not known. Check the data before running it again.", e);
      }
      throw ConnectorException.unavailable("The connection to Snowflake failed: " + reason(e) + ".", e);
    }
    JsonNode tree;
    try {
      tree = bytes.length == 0 ? F.objectNode() : JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      tree = null;
    }
    if (tree == null || !tree.isObject()) {
      int status = response.statusCode();
      if (status != 200 && status != 202) {
        // An error page of a proxy or of Snowflake's edge: the status says what happened.
        tree = F.objectNode();
      } else if (change) {
        throw ConnectorException.outcomeUnknown("Snowflake's answer to the change isn't JSON, so whether it ran is "
            + "not known.", null);
      } else {
        throw ConnectorException.resourceError("Snowflake answered " + status + " with something that isn't JSON.",
            null);
      }
    }
    return new Response(response.statusCode(), tree);
  }

  private static void pause(long ms, boolean change) throws ConnectorException {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (change) {
        throw ConnectorException.outcomeUnknown("The change was cancelled while Snowflake ran it, so whether it ran "
            + "is not known.", e);
      }
      throw ConnectorException.timeout("The call was cancelled.");
    }
  }

  /** The body, decompressed when it is gzip (later partitions are), at most {@link #MAX_BODY} bytes either way. */
  private static byte[] read(HttpResponse<InputStream> response) throws IOException {
    boolean gzip = response.headers().firstValue("Content-Encoding")
        .map(v -> v.strip().toLowerCase(Locale.ROOT).equals("gzip")).orElse(false);
    try (InputStream raw = new Limited(response.body(), MAX_BODY)) {
      PushbackInputStream in = new PushbackInputStream(raw, 2);
      int b0 = in.read();
      int b1 = b0 < 0 ? -1 : in.read();
      if (b1 >= 0) {
        in.unread(b1);
      }
      if (b0 >= 0) {
        in.unread(b0);
      }
      boolean magic = b0 == 0x1f && b1 == 0x8b;
      InputStream body = gzip || magic ? new Limited(new GZIPInputStream(in), MAX_BODY) : in;
      ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
      body.transferTo(out);
      return out.toByteArray();
    }
  }

  /** A stream that fails past {@code max} bytes. */
  private static final class Limited extends FilterInputStream {
    private final long max;
    private long count;

    Limited(InputStream in, long max) {
      super(in);
      this.max = max;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0 && ++count > max) {
        throw new TooLarge();
      }
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = super.read(buf, off, len);
      if (n > 0 && (count += n) > max) {
        throw new TooLarge();
      }
      return n;
    }
  }

  private static final class TooLarge extends IOException {
    private static final long serialVersionUID = 1L;

    TooLarge() {
      super("too large");
    }
  }

  private static ConnectorException unknownOrUnavailable(boolean change, String message) {
    return change ? ConnectorException.outcomeUnknown(message + " Whether the change ran is not known.", null)
        : ConnectorException.resourceError(message, null);
  }

  // ── Errors ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** What Snowflake said, as what the agent or the person can do about it. */
  static ConnectorException error(int status, JsonNode tree, Call c, boolean change) {
    String code = tree.path("code").asString("");
    String state = tree.path("sqlState").asString("");
    String said = said(tree, status);
    SnowflakeSettings s = c.settings();
    if (status == 401 || (code.startsWith("390") && status / 100 == 4)) {
      return new Failure(ConnectorException.Kind.RESOURCE_UNAVAILABLE, signInFailure(said, code, c), status, code,
          true);
    }
    if (status == 403) {
      return new Failure(ConnectorException.Kind.RESOURCE_UNAVAILABLE, "Snowflake refused the request (403): " + said
          + ". The account may not allow the SQL API, or a network policy blocks this computer.", status, code, true);
    }
    if (status == 404) {
      return new Failure(ConnectorException.Kind.RESOURCE_UNAVAILABLE, "There is no Snowflake SQL API at " + s.host()
          + " (404). Check the account identifier.", status, code, false);
    }
    if (status == 408 || code.equals("000630") || code.equals("000604")) {
      if (change) {
        return new Failure(ConnectorException.Kind.RESOURCE_ERROR, "Snowflake canceled the statement at its timeout, "
            + "so it didn't complete: " + said + ". Check the data before running it again.", status, code, false);
      }
      return new Failure(ConnectorException.Kind.TIMEOUT, "Snowflake canceled the statement at its timeout: " + said
          + ". Narrow the query, or ask a person to raise the statement timeout.", status, code, false);
    }
    if (status == 429) {
      return new Failure(ConnectorException.Kind.RESOURCE_ERROR, "Snowflake refused the request: too many requests "
          + "at once" + (change ? ", so the change didn't run" : "") + ". Try again in a moment.", status, code, false);
    }
    if (status / 100 == 5) {
      if (change) {
        return new Failure(ConnectorException.Kind.OUTCOME_UNKNOWN, "Snowflake failed with " + status + " during the "
            + "change, so whether it ran is not known. Check the data before running it again.", status, code, false);
      }
      return new Failure(ConnectorException.Kind.RESOURCE_UNAVAILABLE, "Snowflake failed with " + status + ": " + said
          + ". Try again in a moment.", status, code, false);
    }
    String prefix = change ? "Snowflake refused the change, so nothing changed: " : "";
    if (state.equals("42501") || code.equals("003001")) {
      return new Failure(ConnectorException.Kind.RESOURCE_ERROR, prefix + (s.role() == null ? "The user's role"
          : "The role " + s.role()) + " may not do this: " + said + ".", status, code, false);
    }
    if (code.equals("000606") || state.equals("57P03")) {
      return new Failure(ConnectorException.Kind.RESOURCE_ERROR, prefix + said + ". Set the connection's warehouse, "
          + "or give the user a default warehouse its role may use.", status, code, false);
    }
    if (code.equals("002003")) {
      return new Failure(ConnectorException.Kind.BAD_INPUT, prefix + said + ". Call snowflake_list_schemas to see the "
          + "tables.", status, code, false);
    }
    if (said.toLowerCase(Locale.ROOT).contains("does not have a current database")
        || said.toLowerCase(Locale.ROOT).contains("does not have a current schema")) {
      // Snowflake suggests USE, which GateRook never runs: the connection sets the namespace.
      return new Failure(ConnectorException.Kind.BAD_INPUT, prefix + "The session has no current database or "
          + "schema, so a name without them can't be resolved. Write DATABASE.SCHEMA.TABLE, or ask a person to set the "
          + "connection's Database and Schema.", status, code, false);
    }
    if (code.equals("000008")) {
      return new Failure(ConnectorException.Kind.BAD_INPUT, prefix + said + ". Send one statement.", status, code,
          false);
    }
    if (status == 422 && (state.startsWith("42") || state.startsWith("0A") || code.equals("001003")
        || code.equals("000904") || code.equals("002140") || code.equals("001044"))) {
      return new Failure(ConnectorException.Kind.BAD_INPUT, (change ? prefix : "Snowflake refused the statement: ")
          + said + ".", status, code, false);
    }
    if (status == 400) {
      return new Failure(ConnectorException.Kind.RESOURCE_ERROR, prefix + "Snowflake refused the request (400): "
          + said + ".", status, code, false);
    }
    return new Failure(ConnectorException.Kind.RESOURCE_ERROR, (change ? prefix : "Snowflake couldn't run it: ")
        + said + ".", status, code, false);
  }

  private static String signInFailure(String said, String code, Call c) {
    StringBuilder out = new StringBuilder("Snowflake refused the sign-in of ").append(c.settings().user())
        .append(": ").append(said).append('.');
    if (code.equals("390201") || code.equals("390189") || code.equals("390186")) {
      out.append(" Check the connection's role, warehouse and database: the user may not use them.");
    } else if (c.credential() != null && c.credential().fingerprint() != null) {
      out.append(" Check that the user's RSA_PUBLIC_KEY is this key's public key (").append(c.credential()
          .fingerprint()).append(") and that the account identifier is right.");
    } else {
      out.append(" Check that the token is valid and not expired, and that a network policy of the user allows this "
          + "computer.");
    }
    return out.toString();
  }

  /** Snowflake's message as one line without control characters, or the status when it sent none. */
  static String said(JsonNode tree, int status) {
    String m = tree.path("message").asString("");
    if (m.isBlank()) {
      return "HTTP " + status;
    }
    String line = OutputSanitizer.sanitize(m.replaceAll("\\s+", " ").strip());
    if (line.length() > 600) {
      line = line.substring(0, 599) + "…";
    }
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }

  private static String reason(Throwable e) {
    String m = e.getMessage();
    if (m == null || m.isBlank()) {
      return e.getClass().getSimpleName();
    }
    int nl = m.indexOf('\n');
    return OutputSanitizer.sanitize((nl < 0 ? m : m.substring(0, nl)).strip());
  }
}
