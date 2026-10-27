package ai.msrv.gaterook.core.audit.export;

import com.google.auth.oauth2.GoogleCredentials;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A BigQuery table through the streaming API ({@code tabledata.insertAll}, REST over {@code java.net.http}, the token
 * from google-auth): up to 500 rows and 9 MB per request, each row with its insert id — the row's id, the head's
 * {@code head-<batch>} — so a batch sent again within BigQuery's deduplication window isn't stored twice; past it, the
 * {@code id} column tells the copies apart. Unknown fields are refused rather than dropped: the table must have the
 * columns of {@link ExportRecords#FIELDS} and {@link ExportRecords#HEAD_FIELDS} (Test lists the missing ones).
 */
public final class BigQuerySender implements ExportSender {

  /** BigQuery's own scope: insertAll and tables.get. */
  public static final String SCOPE = "https://www.googleapis.com/auth/bigquery";

  /** The API's address; tests of a development build point {@code gateway.cloud.endpoints} {@code gcp.bigquery} elsewhere. */
  public static final URI API = URI.create("https://bigquery.googleapis.com");

  private static final int ROWS_PER_REQUEST = 500;

  /**
   * BigQuery refuses a request over 10 MB, and an agent's arguments are stored whole (a call may carry 1 MiB): a
   * request holds rows up to this many bytes, so no batch can be refused for its size and retried forever.
   */
  static final int MAX_REQUEST_BYTES = 9 * 1024 * 1024;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ExportSettings.BigQuery settings;
  private final GoogleCredentials credentials;
  private final URI base;
  private final HttpClient http;
  private final Consumer<CharSequence> taint;
  private final Duration timeout;

  /** @param taint receives every access token used: an echo of it in an error is masked */
  public BigQuerySender(ExportSettings.BigQuery settings, GoogleCredentials credentials, URI base, HttpClient http,
      Consumer<CharSequence> taint, Duration timeout) {
    this.settings = settings;
    this.credentials = credentials;
    this.base = base == null ? API : base;
    this.http = http;
    this.taint = taint;
    this.timeout = timeout;
  }

  @Override
  public long send(Batch batch) throws ExportException {
    List<ObjectNode> records = batch.records();
    long bytes = 0;
    int from = 0;
    while (from < records.size()) {
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      body.put("kind", "bigquery#tableDataInsertAllRequest");
      body.put("skipInvalidRows", false);
      body.put("ignoreUnknownValues", false);
      ArrayNode rows = body.putArray("rows");
      long size = JSON.writeValueAsBytes(body).length;
      int to = from;
      while (to < records.size() && to - from < ROWS_PER_REQUEST) {
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("insertId", insertId(batch, records.get(to)));
        row.set("json", ExportRecords.flat(records.get(to)));
        long rowSize = JSON.writeValueAsBytes(row).length + 1;
        if (to > from && size + rowSize > MAX_REQUEST_BYTES) {
          break;
        }
        rows.add(row);
        size += rowSize;
        to++;
      }
      byte[] payload = JSON.writeValueAsBytes(body);
      bytes += payload.length;
      JsonNode answer = call("POST", tableUri("/insertAll"), payload);
      refused(answer.path("insertErrors"), from);
      from = to;
    }
    return bytes;
  }

  /**
   * The row a refused request was refused for. With {@code skipInvalidRows=false} BigQuery lists every row of the
   * request — the valid ones as {@code stopped}, without a message — so the first entry is rarely the one at fault.
   *
   * @param from where the request starts in the batch
   */
  private static void refused(JsonNode errors, int from) throws ExportException {
    if (!errors.isArray() || errors.isEmpty()) {
      return;
    }
    JsonNode row = errors.get(0);
    JsonNode why = row.path("errors").path(0);
    search:
    for (JsonNode e : errors) {
      for (JsonNode w : e.path("errors")) {
        if (!"stopped".equals(w.path("reason").asString(""))) {
          row = e;
          why = w;
          break search;
        }
      }
    }
    String location = why.path("location").asString("");
    throw new ExportException("BigQuery refused row " + (from + row.path("index").asInt()) + " of the batch: "
        + why.path("reason").asString("invalid") + (location.isBlank() ? "" : " at " + location) + ": "
        + why.path("message").asString(""));
  }

  @Override
  public String test() throws ExportException {
    JsonNode table = call("GET", tableUri(""), null);
    Set<String> columns = new LinkedHashSet<>();
    for (JsonNode f : table.path("schema").path("fields")) {
      columns.add(f.path("name").asString());
    }
    List<String> missing = new ArrayList<>();
    List<String> wanted = new ArrayList<>(ExportRecords.FIELDS);
    wanted.addAll(ExportRecords.HEAD_FIELDS);
    for (String c : wanted) {
      if (!columns.contains(c)) {
        missing.add(c);
      }
    }
    if (!missing.isEmpty()) {
      throw new ExportException(settings.describe() + " lacks the columns " + String.join(", ", missing)
          + ": add them, or create the table from the export's schema");
    }
    return settings.describe() + " has every column the export writes";
  }

  static String insertId(Batch batch, ObjectNode record) {
    return ExportRecords.HEAD.equals(record.path("type").asString()) ? "head-" + batch.key()
        : record.path("id").asString();
  }

  private URI tableUri(String suffix) {
    String b = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
        : base.toString();
    return URI.create(b + "/bigquery/v2/projects/" + settings.project() + "/datasets/" + settings.dataset()
        + "/tables/" + settings.table() + suffix);
  }

  private JsonNode call(String method, URI uri, byte[] body) throws ExportException {
    Map<String, List<String>> auth;
    try {
      auth = CloudCredentials.requestMetadata(credentials, uri, taint);
    } catch (ConnectorException e) {
      throw new ExportException(e.getMessage(), e);
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
        .header("Accept", "application/json");
    auth.forEach((name, values) -> values.forEach(v -> request.header(name, v)));
    if (body != null) {
      request.header("Content-Type", "application/json; charset=utf-8")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> response = ExportHttp.exchange(http, request.build(), timeout);
    JsonNode answer;
    try {
      answer = response.body() == null || response.body().isBlank() ? JsonNodeFactory.instance.objectNode()
          : JSON.readTree(response.body());
    } catch (RuntimeException e) {
      answer = JsonNodeFactory.instance.objectNode();
    }
    if (response.statusCode() / 100 != 2) {
      throw new ExportException(error(response.statusCode(), answer));
    }
    return answer;
  }

  /** Google's words: {@code 403 Forbidden: Access Denied: Table acme-prod:audit.mcp_calls: Permission …}. */
  static String error(int status, JsonNode answer) {
    String phrase = switch (status) {
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 409 -> "Conflict";
      case 429 -> "Too Many Requests";
      case 500 -> "Internal Server Error";
      case 503 -> "Service Unavailable";
      default -> "Error";
    };
    String message = answer.path("error").path("message").asString("");
    return status + " " + phrase + (message.isBlank() ? "" : ": " + message);
  }
}
