package ai.msrv.gaterook.core.secrets.store;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

/**
 * A Vault 2.2.1 as far as the gateway talks to it (answers copied from a real one, report 12 §6): AppRole sign-in,
 * the mounts preflight, KV v2 ({@code kv/}) and v1 ({@code kv1/}), database credentials, leases, the SSH CA, health,
 * lookup-self and capabilities-self. Values are canaries ({@code cnry-…}) so a leak test can look for them.
 */
public final class FakeVault implements AutoCloseable {

  public static final String ROLE_ID = "4f1c2d3e-role";
  public static final String SECRET_ID = "cnry-approle-secret-id";
  public static final String DB_PASSWORD = "cnry-db-password-7Hq2";
  public static final String DYNAMIC_PASSWORD_PREFIX = "cnry-dyn-";

  public final MockWebServer server = new MockWebServer();
  public final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  public final AtomicInteger logins = new AtomicInteger();
  public final AtomicInteger issued = new AtomicInteger();
  public final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();
  public final Set<String> revokedLeases = ConcurrentHashMap.newKeySet();
  public final AtomicInteger renewSelf = new AtomicInteger();
  public volatile boolean standby;
  /** Where a standby redirects to. */
  public volatile String standbyLocation = "http://127.0.0.1:9";
  public volatile boolean sealed;
  /** Lease duration of a sign-in, in seconds. */
  public volatile long tokenTtl = 3600;
  public volatile boolean signRejectsKeyId;
  public volatile String capabilities = "[\"read\"]";
  public final Map<String, String> listings = new ConcurrentHashMap<>(Map.of(
      "kv/metadata", "[\"stage/\"]", "kv/metadata/stage", "[\"db\",\"db/\"]",
      "kv1", "[\"app\"]"));
  public final Set<String> deniedPaths = ConcurrentHashMap.newKeySet();
  public final Map<String, String> responses = new ConcurrentHashMap<>();
  public volatile String mountListing = "{\"kv/\":{\"type\":\"kv\",\"options\":{\"version\":\"2\"}},"
      + "\"kv1/\":{\"type\":\"kv\",\"options\":{\"version\":\"1\"}},\"database/\":{\"type\":\"database\"},"
      + "\"ssh-client-signer/\":{\"type\":\"ssh\"}}";

  public FakeVault() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        return answer(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  /** {@code http://127.0.0.1:<port>} */
  public String address() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  public String token(int n) {
    return "hvs.cnry-token-" + n;
  }

  public List<RecordedRequest> to(String pathPrefix) {
    return requests.stream().filter(r -> r.getUrl().encodedPath().startsWith("/v1/" + pathPrefix)).toList();
  }

  private MockResponse answer(RecordedRequest r) {
    String path = r.getUrl().encodedPath();
    String query = r.getUrl().encodedQuery();
    String token = r.getHeaders().get("X-Vault-Token");
    if (standby) {
      return new MockResponse.Builder().code(307).addHeader("Location", standbyLocation + path).build();
    }
    if (path.equals("/v1/sys/health")) {
      return sealed ? json(503, "{\"initialized\":true,\"sealed\":true,\"standby\":true,\"version\":\"2.2.1\"}")
          : json(200, "{\"initialized\":true,\"sealed\":false,\"standby\":false,\"version\":\"2.2.1\"}");
    }
    if (path.equals("/v1/auth/approle/login")) {
      String body = r.getBody().utf8();
      if (!body.contains("\"role_id\":\"" + ROLE_ID + "\"") || !body.contains("\"secret_id\":\"" + SECRET_ID + "\"")) {
        return json(400, "{\"errors\":[\"invalid role or secret ID\"]}");
      }
      int n = logins.incrementAndGet();
      return json(200, "{\"auth\":{\"client_token\":\"" + token(n) + "\",\"accessor\":\"acc" + n + "\",\"policies\":"
          + "[\"default\",\"gaterook\"],\"lease_duration\":" + tokenTtl + ",\"renewable\":true}}");
    }
    if (token == null || !token.startsWith("hvs.cnry-token-") || revokedTokens.contains(token)) {
      return json(403, "{\"errors\":[\"permission denied\"]}");
    }
    if (deniedPaths.contains(path.substring("/v1/".length()))) {
      return json(403, "{\"errors\":[\"permission denied\"]}");
    }
    String override = responses.get(path.substring("/v1/".length()));
    if (override != null) {
      return json(200, override);
    }
    if (path.equals("/v1/auth/token/lookup-self")) {
      return json(200, "{\"data\":{\"policies\":[\"default\",\"gaterook\"],\"ttl\":" + tokenTtl + ",\"renewable\":"
          + "true,\"meta\":{\"role_name\":\"gaterook\"}}}");
    }
    if (path.equals("/v1/auth/token/renew-self")) {
      renewSelf.incrementAndGet();
      return json(200, "{\"auth\":{\"client_token\":\"" + token + "\",\"lease_duration\":" + tokenTtl
          + ",\"renewable\":true}}");
    }
    if (path.equals("/v1/sys/capabilities-self")) {
      String body = r.getBody().utf8();
      StringBuilder out = new StringBuilder("{\"data\":{");
      int i = body.indexOf("[");
      String[] paths = body.substring(i + 1, body.indexOf(']', i)).replace("\"", "").split(",");
      for (int k = 0; k < paths.length; k++) {
        out.append(k == 0 ? "" : ",").append('"').append(paths[k]).append("\":").append(capabilities);
      }
      return json(200, out.append("}}").toString());
    }
    if (path.equals("/v1/sys/internal/ui/mounts")) {
      return json(200, "{\"data\":{\"secret\":" + mountListing + "}}");
    }
    if (path.startsWith("/v1/sys/internal/ui/mounts/")) {
      String of = path.substring("/v1/sys/internal/ui/mounts/".length());
      if (of.startsWith("kv/")) {
        return json(200, "{\"data\":{\"path\":\"kv/\",\"type\":\"kv\",\"options\":{\"version\":\"2\"}}}");
      }
      if (of.startsWith("kv1/")) {
        return json(200, "{\"data\":{\"path\":\"kv1/\",\"type\":\"kv\",\"options\":{\"version\":\"1\"}}}");
      }
      if (of.startsWith("database/")) {
        return json(200, "{\"data\":{\"path\":\"database/\",\"type\":\"database\",\"options\":null}}");
      }
      if (of.startsWith("ssh-client-signer/")) {
        return json(200, "{\"data\":{\"path\":\"ssh-client-signer/\",\"type\":\"ssh\",\"options\":null}}");
      }
      if (of.startsWith("locked/")) {
        return json(403, "{\"errors\":[\"preflight capability check returned 403, please ensure client's policies "
            + "grant access to path \\\"locked/\\\"\"]}");
      }
      return json(400, "{\"errors\":[\"no mount for path\"]}");
    }
    if ("LIST".equals(r.getMethod())) {
      if (path.equals("/v1/kv/metadata/forbidden")) {
        return json(403, "{\"errors\":[\"permission denied\"]}");
      }
      String keys = listings.get(path.substring("/v1/".length()));
      return keys == null ? json(404, "{\"errors\":[]}") : json(200, "{\"data\":{\"keys\":" + keys + "}}");
    }
    if (path.equals("/v1/kv/data/stage/db")) {
      if ("version=2".equals(query)) {
        return json(404, "{\"data\":{\"data\":null,\"metadata\":{\"created_time\":\"2026-10-01T10:00:00Z\","
            + "\"deletion_time\":\"\",\"destroyed\":true,\"version\":2}}}");
      }
      if ("version=3".equals(query)) {
        return json(404, "{\"data\":{\"data\":null,\"metadata\":{\"created_time\":\"2026-10-02T10:00:00Z\","
            + "\"deletion_time\":\"2026-10-03T10:00:00Z\",\"destroyed\":false,\"version\":3}}}");
      }
      return json(200, "{\"request_id\":\"r\",\"lease_id\":\"\",\"data\":{\"data\":{\"password\":\"" + DB_PASSWORD
          + "\",\"user\":\"app\"},\"metadata\":{\"created_time\":\"2026-10-16T09:00:00Z\",\"deletion_time\":\"\","
          + "\"destroyed\":false,\"version\":4}}}");
    }
    if (path.equals("/v1/kv/data/forbidden")) {
      return json(403, "{\"errors\":[\"1 error occurred:\\n\\t* permission denied\\n\\n\"]}");
    }
    if (path.equals("/v1/kv1/app")) {
      return json(200, "{\"data\":{\"api_key\":\"cnry-kv1-api-key\"},\"lease_duration\":2764800}");
    }
    if (path.equals("/v1/database/creds/prod-replica-readonly")) {
      int n = issued.incrementAndGet();
      return json(200, "{\"lease_id\":\"database/creds/prod-replica-readonly/lease" + n + "\",\"lease_duration\":3600,"
          + "\"renewable\":true,\"data\":{\"username\":\"v-approle-prod-rep-" + n + "\",\"password\":\""
          + DYNAMIC_PASSWORD_PREFIX + n + "\"}}");
    }
    if (path.startsWith("/v1/sys/leases/revoke/")) {
      revokedLeases.add(path.substring("/v1/sys/leases/revoke/".length()));
      return new MockResponse.Builder().code(204).build();
    }
    if (path.equals("/v1/sys/leases/renew")) {
      return json(200, "{\"lease_id\":\"x\",\"lease_duration\":3600,\"renewable\":true}");
    }
    if (path.equals("/v1/ssh-client-signer/sign/gw-runbooks")) {
      String body = r.getBody().utf8();
      if (signRejectsKeyId && body.contains("\"key_id\"")) {
        return json(400, "{\"errors\":[\"setting key_id is not allowed by role\"]}");
      }
      return json(200, "{\"data\":{\"serial_number\":\"5b:0f:21\",\"signed_key\":\"ssh-ed25519-cert-v01@openssh.com "
          + "AAAAcnryCERT\\n\"}}");
    }
    return json(404, "{\"errors\":[]}");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }

  @Override
  public void close() {
    server.close();
  }
}
