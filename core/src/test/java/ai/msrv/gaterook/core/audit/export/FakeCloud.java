package ai.msrv.gaterook.core.audit.export;

import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * S3, BigQuery and Google's token endpoint in the test (MockWebServer): path-style {@code PutObject} into an
 * in-memory bucket (an object written again replaces itself, like S3), {@code tabledata.insertAll} and
 * {@code tables.get} of one table, and a token for any JWT assertion. Each can be told to answer an error.
 */
public final class FakeCloud implements AutoCloseable {

  public static final String TOKEN = "ya29.fake-bigquery-token-7f3k";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One request as it came. */
  public record Request(String method, String path, Map<String, List<String>> headers, byte[] body, String url) {

    public String header(String name) {
      return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
          .map(e -> e.getValue().getFirst()).orElse(null);
    }

    public String text() {
      return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private final MockWebServer server = new MockWebServer();
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  /** The bucket: key → body of the last PUT. */
  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  private final AtomicInteger puts = new AtomicInteger();
  private volatile MockResponse s3Answer;
  private volatile MockResponse insertAnswer;
  private volatile List<String> tableColumns = List.of();

  public FakeCloud() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest r) {
        byte[] body = r.getBody() == null ? new byte[0] : r.getBody().toByteArray();
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        r.getHeaders().forEach(h -> headers.computeIfAbsent(h.getFirst(), k -> new java.util.ArrayList<>())
            .add(h.getSecond()));
        String path = r.getUrl().encodedPath();
        Request request = new Request(r.getMethod(), path, headers, body, r.getUrl().toString());
        requests.add(request);
        if (path.equals("/token")) {
          return json(200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        }
        if (path.startsWith("/bigquery/v2/")) {
          if (path.endsWith("/insertAll")) {
            MockResponse a = insertAnswer;
            return a != null ? a : json(200, "{\"kind\":\"bigquery#tableDataInsertAllResponse\"}");
          }
          ObjectNode table = JsonNodeFactory.instance.objectNode();
          tools.jackson.databind.node.ArrayNode fields = table.putObject("schema").putArray("fields");
          tableColumns.forEach(c -> fields.addObject().put("name", c));
          return json(200, table.toString());
        }
        if (r.getMethod().equals("PUT")) {
          MockResponse a = s3Answer;
          if (a != null) {
            return a;
          }
          objects.put(path, body);
          puts.incrementAndGet();
          return new MockResponse.Builder().code(200).addHeader("ETag", "\"etag\"").build();
        }
        return json(404, "{}");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** {@code http://127.0.0.1:<port>}: the S3 endpoint and the BigQuery API of the test. */
  public String url() {
    String u = server.url("/").toString();
    return u.substring(0, u.length() - 1);
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  public List<Request> requests(String method, String pathPrefix) {
    return requests.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
  }

  public Map<String, byte[]> objects() {
    return Map.copyOf(objects);
  }

  public int puts() {
    return puts.get();
  }

  /** S3 answers {@code status} with an S3 error document; {@code 0}: S3 works again. */
  public void s3Fails(int status, String code, String message) {
    s3Answer = status == 0 ? null : new MockResponse.Builder().code(status).addHeader("Content-Type",
        "application/xml").body("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code + "</Code><Message>"
        + message + "</Message><RequestId>4442587FB7D0A2F9</RequestId></Error>").build();
  }

  /** insertAll answers {@code status} with Google's error; {@code 0}: it works again. */
  public void bigQueryFails(int status, String message) {
    insertAnswer = status == 0 ? null : json(status, "{\"error\":{\"code\":" + status + ",\"message\":\""
        + message + "\",\"status\":\"PERMISSION_DENIED\"}}");
  }

  /** insertAll answers {@code status} with {@code json} as it is. */
  public void bigQueryAnswers(int status, String json) {
    insertAnswer = json(status, json);
  }

  /** insertAll answers 200 with one row refused, as BigQuery does for a value of the wrong type. */
  public void bigQueryRefusesRow(int index, String reason, String message) {
    insertAnswer = json(200, "{\"insertErrors\":[{\"index\":" + index + ",\"errors\":[{\"reason\":\"" + reason
        + "\",\"location\":\"durationMs\",\"message\":\"" + message + "\"}]}]}");
  }

  public void tableColumns(List<String> columns) {
    tableColumns = List.copyOf(columns);
  }

  /**
   * A service account key of {@code gw-audit@acme-prod.iam.gserviceaccount.com} with a fresh RSA key, its
   * {@code token_uri} Google's (the core sends it to this fake only through its test override).
   */
  public static String serviceAccountKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
          .encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
      ObjectNode key = JsonNodeFactory.instance.objectNode();
      key.put("type", "service_account");
      key.put("project_id", "acme-prod");
      key.put("private_key_id", "0123456789abcdef");
      key.put("private_key", pem);
      key.put("client_email", "gw-audit@acme-prod.iam.gserviceaccount.com");
      key.put("client_id", "112233445566778899");
      key.put("token_uri", "https://oauth2.googleapis.com/token");
      return JSON.writeValueAsString(key);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body).build();
  }

  @Override
  public void close() {
    server.close();
  }
}
