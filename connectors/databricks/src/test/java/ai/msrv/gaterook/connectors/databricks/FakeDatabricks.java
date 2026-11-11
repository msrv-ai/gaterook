package ai.msrv.gaterook.connectors.databricks;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Databricks workspace on this computer, over HTTPS with a test CA: the OAuth token endpoint
 * ({@code /oidc/v1/token}) and the REST APIs by method and path ({@code POST /api/2.0/sql/statements}). The connector
 * under test trusts the test CA through {@link #clientTls}, and only it.
 */
final class FakeDatabricks implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();
  /** Clearly fake credentials of the test workspace. */
  static final String PAT = "dapi-example-0123456789abcdef0123456789";
  static final String OAUTH_TOKEN = "eyJ-example-oauth-token-cnry";
  static final String CLIENT_ID = "6f0e2c9a-1b3d-4e5f-8a7b-9c0d1e2f3a4b";
  static final String CLIENT_SECRET = "dose-example-client-secret-cnry";

  final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
  private final MockWebServer server = new MockWebServer();
  final SSLContext clientTls;

  FakeDatabricks() throws IOException {
    HeldCertificate ca = new HeldCertificate.Builder().commonName("Databricks Test Root").certificateAuthority(0)
        .build();
    HeldCertificate leaf = new HeldCertificate.Builder().commonName("127.0.0.1")
        .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").signedBy(ca).build();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate()).build()
        .sslSocketFactory());
    clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate()).build().sslContext();
    on("POST /oidc/v1/token", r -> json(200, "{\"access_token\":\"" + OAUTH_TOKEN + "\",\"token_type\":\"Bearer\","
        + "\"expires_in\":3600,\"scope\":\"all-apis\"}"));
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String key = request.getMethod() + " " + request.getUrl().encodedPath();
        String best = null;
        for (String prefix : routes.keySet()) {
          if (key.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
            best = prefix;
          }
        }
        if (best == null) {
          return json(404, "{\"error_code\":\"NOT_FOUND\",\"message\":\"no fake for " + key + "\"}");
        }
        return routes.get(best).apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** The workspace URL of the connection under test. */
  String host() {
    return "https://127.0.0.1:" + server.getPort();
  }

  /** Answers every request whose {@code METHOD /path} starts with {@code prefix} (the longest prefix wins). */
  FakeDatabricks on(String prefix, Function<RecordedRequest, MockResponse> answer) {
    routes.put(prefix, answer);
    return this;
  }

  FakeDatabricks on(String prefix, String body) {
    return on(prefix, r -> json(200, body));
  }

  /** The requests whose {@code METHOD /path} starts with {@code prefix}. */
  List<RecordedRequest> to(String prefix) {
    return requests.stream().filter(r -> (r.getMethod() + " " + r.getUrl().encodedPath()).startsWith(prefix))
        .toList();
  }

  static JsonNode body(RecordedRequest r) {
    return JSON.readTree(r.getBody().utf8());
  }

  static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  static String param(RecordedRequest r, String name) {
    return r.getUrl().queryParameter(name);
  }

  /**
   * A statement that succeeded: its manifest's columns ({@code name:TYPE_NAME:type text}, separated by {@code ;}) and
   * one chunk of rows.
   */
  static String succeeded(String id, String columns, String rows, int chunks, long total, Integer nextChunk) {
    StringBuilder cols = new StringBuilder();
    int position = 0;
    for (String c : columns.split(";")) {
      String[] p = c.split(":", 3);
      cols.append(cols.isEmpty() ? "" : ",").append("{\"name\":\"").append(p[0]).append("\",\"type_name\":\"")
          .append(p[1]).append("\",\"type_text\":\"").append(p.length > 2 ? p[2] : p[1]).append("\",\"position\":")
          .append(position++).append('}');
    }
    return "{\"statement_id\":\"" + id + "\",\"status\":{\"state\":\"SUCCEEDED\"},\"manifest\":{\"format\":"
        + "\"JSON_ARRAY\",\"schema\":{\"column_count\":" + position + ",\"columns\":[" + cols + "]},"
        + "\"total_chunk_count\":" + chunks + ",\"total_row_count\":" + total + ",\"truncated\":false},\"result\":"
        + "{\"chunk_index\":0,\"row_offset\":0,\"data_array\":" + rows
        + (nextChunk == null ? "" : ",\"next_chunk_index\":" + nextChunk) + "}}";
  }

  @Override
  public void close() {
    server.close();
  }
}
