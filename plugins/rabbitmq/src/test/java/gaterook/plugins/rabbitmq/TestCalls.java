package gaterook.plugins.rabbitmq;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** What a call of the plugin gets from the host, in this JVM: a password, an egress on java.net.http, a context. */
final class TestCalls {

  static final String PASSWORD = "cnry-rabbit-pass-90b3f7";

  private TestCalls() {}

  static JsonNode manifest() {
    try (InputStream in = TestCalls.class.getResourceAsStream("/mcp-gateway-plugin.json")) {
      if (in != null) {
        return Manifests.JSON.readTree(in);
      }
      return Manifests.JSON.readTree(Path.of("src/main/resources/mcp-gateway-plugin.json").toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A connection to the management API at {@code 127.0.0.1:port} without TLS (MockWebServer), vhost /. */
  static ConnectionConfig local(int port) {
    ObjectNode c = Manifests.JSON.createObjectNode();
    c.put("host", "127.0.0.1");
    c.put("port", port);
    c.put("vhost", "/");
    c.put("user", "gw-monitor");
    c.putObject("options").put("timeoutMs", 5000);
    return new ConnectionConfig("c_rmq", "orders-rabbit", "rabbitmq", Env.DEV, 1, c, Set.of(RabbitConfig.PASSWORD),
        null);
  }

  static ToolCall call(String tool, String json) {
    return new ToolCall("tc_test", tool, "orders-rabbit", (ObjectNode) Manifests.JSON.readTree(json), null);
  }

  /** The connection's password, as the core would hand it out for the call. */
  static final class Secrets implements SecretResolver {
    final List<String> asked = new CopyOnWriteArrayList<>();

    @Override
    public Secret secret(String field) throws SecretUnavailableException {
      asked.add(field);
      if (!RabbitConfig.PASSWORD.equals(field)) {
        throw new SecretUnavailableException(field + " is not a secret field of this connection", null);
      }
      char[] value = PASSWORD.toCharArray();
      return new Secret() {
        @Override
        public char[] chars() {
          return value;
        }

        @Override
        public byte[] bytes() {
          return PASSWORD.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String unsafeString() {
          return new String(value);
        }

        @Override
        public String version() {
          return "v1";
        }

        @Override
        public Optional<Instant> notAfter() {
          return Optional.empty();
        }

        @Override
        public void close() {
          java.util.Arrays.fill(value, '\0');
        }
      };
    }

    @Override
    public Login login(String field) throws SecretUnavailableException {
      throw new SecretUnavailableException("no login", null);
    }

    @Override
    public boolean isSet(String field) {
      return RabbitConfig.PASSWORD.equals(field);
    }

    @Override
    public Meta meta(String field) {
      return new Meta("local", "v1", null);
    }

    @Override
    public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
        throws SecretUnavailableException {
      throw new SecretUnavailableException("no Vault", null);
    }
  }

  /** A call's context: plain java.net.http as the host's egress, the taint it reports, approved or not. */
  static class Context implements ExecContext {
    final boolean approved;
    final List<String> tainted = new CopyOnWriteArrayList<>();
    private final Instant deadline = Instant.now().plusSeconds(10);
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    Context(boolean approved) {
      this.approved = approved;
    }

    @Override
    public String callId() {
      return "tc_test";
    }

    @Override
    public String traceId() {
      return null;
    }

    @Override
    public String traceparent() {
      return null;
    }

    @Override
    public Env env() {
      return Env.DEV;
    }

    @Override
    public Limits limits() {
      return new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, Duration.ofSeconds(5),
          Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));
    }

    @Override
    public Duration timeout() {
      return Duration.ofSeconds(10);
    }

    @Override
    public Instant deadline() {
      return deadline;
    }

    @Override
    public boolean cancelled() {
      return false;
    }

    @Override
    public void onCancel(Runnable action) {}

    @Override
    public boolean approved() {
      return approved;
    }

    @Override
    public Optional<String> approvalId() {
      return approved ? Optional.of("ap_test") : Optional.empty();
    }

    @Override
    public Optional<JsonNode> preparedState() {
      return Optional.empty();
    }

    @Override
    public Optional<JsonNode> approvedPayload() {
      return Optional.empty();
    }

    @Override
    public System.Logger log() {
      return System.getLogger("rabbitmq-test");
    }

    @Override
    public Clock clock() {
      return Clock.systemUTC();
    }

    @Override
    public HandleCodec handles() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean acquire(String remoteQuotaKey, int permitsPerMinute) {
      return true;
    }

    @Override
    public Path workDir() {
      return Path.of("target");
    }

    @Override
    public Egress egress() {
      return (request, maxBytes) -> {
        HttpRequest.Builder b = HttpRequest.newBuilder(request.uri()).timeout(request.timeout())
            .method(request.method(), request.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach((k, values) -> values.forEach(v -> b.header(k, v)));
        try {
          HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
          byte[] body = r.body();
          boolean cut = body.length > maxBytes;
          return new EgressResponse(r.statusCode(), Map.copyOf(r.headers().map()),
              cut ? java.util.Arrays.copyOf(body, (int) maxBytes) : body, cut);
        } catch (IOException e) {
          throw ConnectorException.unavailable(request.uri().getHost() + " can't be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw ConnectorException.timeout("cancelled");
        }
      };
    }

    @Override
    public void taint(CharSequence value) {
      tainted.add(value.toString());
    }
  }
}
