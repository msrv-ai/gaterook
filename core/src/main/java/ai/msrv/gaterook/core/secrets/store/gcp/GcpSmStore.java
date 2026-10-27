package ai.msrv.gaterook.core.secrets.store.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import ai.msrv.gaterook.core.secrets.store.RemoteValue;
import ai.msrv.gaterook.core.secrets.store.SecretJson;
import ai.msrv.gaterook.core.secrets.store.SecretRef;
import ai.msrv.gaterook.core.secrets.store.SecretStore;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A GCP Secret Manager store (report 12 §4.3, §5, §7.6): one REST {@code GET …/versions/<v>:access} per read, with a
 * token from {@link CloudCredentials} — Google sign-in on this computer impersonating the store's service account
 * ("from your gcloud credentials. No key file"), or a service account key stored here. Regional secrets
 * ({@code locations/<l>}) go to {@code secretmanager.<l>.rep.googleapis.com}. The access token of every request goes
 * to the call's taint; the payload's CRC32C is checked when the service sends one.
 */
public final class GcpSmStore implements SecretStore {

  /** The secret field of a service account key. */
  public static final String KEY = "/credentials/serviceAccountKey";

  private static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
  private static final List<String> TEST_PERMISSIONS = List.of("secretmanager.versions.access",
      "secretmanager.versions.add", "secretmanager.versions.destroy", "secretmanager.secrets.update",
      "secretmanager.secrets.delete", "secretmanager.secrets.setIamPolicy");
  private static final JsonFactory JSON = JsonFactory.builder().build();

  /**
   * @param project         the store's project ({@code acme-stage}): references of other projects are read too
   * @param endpointOverride test endpoint ({@code gateway.cloud.gcp.secretmanager-endpoint}, never in a release)
   */
  public record Settings(String project, GcpSpec credentials, int cacheTtlSeconds, URI endpointOverride) {

    public static Settings of(JsonNode config, URI endpointOverride) {
      String project = config.path("project").asString("").strip();
      if (!PROJECT.matcher(project).matches()) {
        throw StoreException.invalid("Enter the project ID of the secrets, like acme-stage.");
      }
      GcpSpec spec;
      try {
        spec = GcpSpec.fromConfig(config, "/credentials");
      } catch (IllegalArgumentException e) {
        throw StoreException.invalid(e.getMessage());
      }
      int ttl = config.path("cacheTtlSeconds").asInt(60);
      if (ttl < 0 || ttl > 300) {
        throw StoreException.invalid("Keep a resolved value for 0 to 300 seconds.");
      }
      return new Settings(project, spec, ttl, endpointOverride);
    }
  }

  private final Settings settings;
  private final CloudCredentials cloud;
  private final CloudCredentials.Owner owner;
  private final Supplier<? extends SecretResolver> signIn;
  private final HttpClient http;

  /**
   * @param signIn the store's own secrets for one use (the key of {@link GcpSpec.Source#SERVICE_ACCOUNT_KEY});
   *               closed after it
   */
  public GcpSmStore(Settings settings, CloudCredentials cloud, CloudCredentials.Owner owner,
      Supplier<? extends SecretResolver> signIn) {
    this.settings = settings;
    this.cloud = cloud;
    this.owner = owner;
    this.signIn = signIn;
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
        .proxy(HttpClient.Builder.NO_PROXY).build();
  }

  @Override
  public Duration cacheTtl() {
    return Duration.ofSeconds(settings.cacheTtlSeconds());
  }

  @Override
  public StoreKind kind() {
    return StoreKind.GCP_SM;
  }

  public Settings settings() {
    return settings;
  }

  @Override
  public RemoteValue read(SecretRef reference, StoreCall call) {
    SecretRef.GcpSm ref = (SecretRef.GcpSm) reference;
    URI uri = endpoint(ref.location()).resolve("/v1/" + ref.resourceName() + ":access");
    byte[] data = null;
    try (Answer answer = get(uri, call)) {
      byte[] body = answer.body();
      if (answer.status() != 200) {
        throw failure(body, answer.status(), ref);
      }
      JsonNode root = SecretJson.tree(body, List.of());
      String name = root.path("name").asString("");
      SecretJson.Picked payload = SecretJson.pick(body, List.of("payload"), "data");
      if (payload.value() == null) {
        throw StoreException.notFound(ref.secret() + " has no payload.");
      }
      data = Base64.getDecoder().decode(payload.value());
      payload.clear();
      JsonNode crc = root.path("payload").path("dataCrc32c");
      if (!crc.isMissingNode() && !crc.isNull()) {
        CRC32C c = new CRC32C();
        c.update(data);
        long expected = crc.isNumber() ? crc.asLong() : Long.parseUnsignedLong(crc.asString());
        if (expected != c.getValue()) {
          throw StoreException.unavailable("The payload of " + ref.secret() + " arrived damaged (CRC32C mismatch). "
              + "Test again.", null);
        }
      }
      String version = name.contains("/versions/") ? name.substring(name.lastIndexOf('/') + 1) : null;
      return value(ref, data, version);
    } catch (NumberFormatException e) {
      throw StoreException.unavailable("The payload checksum of " + ref.secret() + " isn't a number.", null);
    } finally {
      if (data != null) {
        Arrays.fill(data, (byte) 0);
      }
    }
  }

  /** The value of {@code data} for {@code ref}: the whole payload or one key of its JSON. */
  static RemoteValue value(SecretRef ref, byte[] data, String version) {
    boolean object = SecretJson.isObject(data);
    List<String> keys = object ? SecretJson.pickTop(data, null).keys() : List.of();
    if (ref.key() == null) {
      return new RemoteValue(data.clone(), version, null, object ? "json" : utf8(data) ? "string" : "binary", keys);
    }
    if (!object) {
      throw StoreException.notFound(ref.display() + " isn't JSON, so it has no key \"" + ref.key()
          + "\". Remove #" + ref.key() + " from the reference.");
    }
    SecretJson.Picked picked = SecretJson.pickTop(data, ref.key());
    if (picked.value() == null) {
      throw StoreException.notFound(ref.display() + " has no key \"" + ref.key() + "\". Keys: "
          + String.join(", ", picked.keys()));
    }
    return new RemoteValue(picked.value(), version, null, picked.string() ? "string" : "json", picked.keys());
  }

  private static boolean utf8(byte[] data) {
    try {
      java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data));
      return true;
    } catch (java.nio.charset.CharacterCodingException e) {
      return false;
    }
  }

  /** The created time of a version ({@code versions.get}); needs more than secretAccessor, so it may be absent. */
  public Instant created(SecretRef.GcpSm ref, StoreCall call) {
    try (Answer answer = get(endpoint(ref.location()).resolve("/v1/" + ref.resourceName()), call)) {
      if (answer.status() != 200) {
        return null;
      }
      return Instant.parse(SecretJson.tree(answer.body(), List.of()).path("createTime").asString(""));
    } catch (StoreException | DateTimeParseException e) {
      return null;
    }
  }

  @Override
  public SecretStores.TestResult test(List<SecretRef> refs, StoreCall call) {
    long t0 = System.nanoTime();
    List<SecretStores.Check> checks = new ArrayList<>();
    List<SecretStores.Hint> hints = new ArrayList<>();
    List<SecretStores.Permission> permissions = new ArrayList<>();
    ObjectNode identity = JsonNodeFactory.instance.objectNode();
    identity.put("project", settings.project());
    identity.put("identity", settings.credentials().identity());
    try {
      long s = System.nanoTime();
      headers(endpoint(null).resolve("/v1/projects/" + settings.project() + "/secrets"), call);
      checks.add(new SecretStores.Check("token", true, ms(s), settings.credentials().impersonate() != null
          ? "Impersonates " + settings.credentials().impersonate() : "Signed in as " + settings.credentials()
          .identity()));
      s = System.nanoTime();
      try (Answer list = get(endpoint(null).resolve("/v1/projects/" + settings.project() + "/secrets?pageSize=1"),
          call)) {
        // secretAccessor has no secrets.list: a refusal here is the sign of a well-cut identity, not a failure.
        checks.add(new SecretStores.Check("list", true, ms(s), list.status() == 200 ? "Can list the secrets of "
            + settings.project() : "Can't list the secrets of " + settings.project() + " (expected with "
            + "roles/secretmanager.secretAccessor)"));
      }
      for (SecretRef r : refs) {
        if (!(r instanceof SecretRef.GcpSm ref)) {
          continue;
        }
        s = System.nanoTime();
        List<String> granted = testIamPermissions(ref, call);
        permissions.add(new SecretStores.Permission(ref.uri(), granted));
        if (!granted.contains("secretmanager.versions.access")) {
          hints.add(new SecretStores.Hint("danger", "store.cant-read", "This identity can't read " + ref.secret()
              + ": grant it roles/secretmanager.secretAccessor on the secret."));
        }
        if (granted.stream().anyMatch(p -> !p.equals("secretmanager.versions.access"))) {
          hints.add(new SecretStores.Hint("warning", "store.can-write", "This identity can add versions to "
              + ref.secret() + ". Grant only roles/secretmanager.secretAccessor."));
        }
        checks.add(new SecretStores.Check("permissions", true, ms(s), ref.secret()));
      }
      return new SecretStores.TestResult(true, ms(t0), null, identity, permissions, hints, checks, null, null);
    } catch (StoreException e) {
      checks.add(new SecretStores.Check("failed", false, ms(t0), e.getMessage()));
      return new SecretStores.TestResult(false, ms(t0), null, identity, permissions, hints, checks, e.getMessage(),
          e.reason().name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  private List<String> testIamPermissions(SecretRef.GcpSm ref, StoreCall call) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator g = JSON.createGenerator(ObjectWriteContext.empty(), out)) {
      g.writeStartObject();
      g.writeArrayPropertyStart("permissions");
      for (String p : TEST_PERMISSIONS) {
        g.writeString(p);
      }
      g.writeEndArray();
      g.writeEndObject();
    }
    try (Answer answer = send("POST", endpoint(ref.location()).resolve("/v1/" + ref.secretName()
        + ":testIamPermissions"), out.toByteArray(), call)) {
      if (answer.status() != 200) {
        throw failure(answer.body(), answer.status(), ref);
      }
      List<String> granted = new ArrayList<>();
      SecretJson.tree(answer.body(), List.of("permissions")).forEach(p -> granted.add(p.asString()));
      return granted;
    }
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────────────────

  private URI endpoint(String location) {
    if (settings.endpointOverride() != null) {
      return settings.endpointOverride();
    }
    return URI.create(location == null ? "https://secretmanager.googleapis.com"
        : "https://secretmanager." + location + ".rep.googleapis.com");
  }

  private Map<String, List<String>> headers(URI uri, StoreCall call) {
    SecretResolver secrets = signIn.get();
    try {
      GoogleCredentials credentials = cloud.gcp(owner, settings.credentials(), List.of(CloudCredentials.CLOUD_PLATFORM),
          secrets);
      return CloudCredentials.requestMetadata(credentials, uri, call.taint());
    } catch (ConnectorException e) {
      throw StoreException.unavailable(e.getMessage(), e);
    } finally {
      closeQuietly(secrets);
    }
  }

  /** One answer of the service; {@link #close()} zeroes the body. */
  private record Answer(int status, byte[] body) implements AutoCloseable {
    @Override
    public void close() {
      Arrays.fill(body, (byte) 0);
    }
  }

  private Answer get(URI uri, StoreCall call) {
    return send("GET", uri, null, call);
  }

  private Answer send(String method, URI uri, byte[] body, StoreCall call) {
    HttpRequest.Builder r = HttpRequest.newBuilder(uri).timeout(call.requestTimeout())
        .header("Accept", "application/json");
    headers(uri, call).forEach((name, values) -> values.forEach(v -> r.header(name, v)));
    if (body != null) {
      r.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    } else {
      r.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<byte[]> response;
    try {
      response = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpConnectTimeoutException | ConnectException e) {
      throw StoreException.unavailable("Can't reach GCP Secret Manager: check the network, then test again.", e);
    } catch (HttpTimeoutException e) {
      throw StoreException.unavailable("GCP Secret Manager didn't answer in time.", e);
    } catch (IOException e) {
      throw StoreException.unavailable("GCP Secret Manager can't be reached: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StoreException.unavailable("The call was cancelled while GCP Secret Manager answered.", e);
    }
    return new Answer(response.statusCode(), response.body() == null ? new byte[0] : response.body());
  }

  private static StoreException failure(byte[] body, int status, SecretRef ref) {
    JsonNode error = SecretJson.tree(body, List.of("error"));
    String code = error.path("status").asString("");
    String what = ref instanceof SecretRef.GcpSm g ? g.secret() + (g.pinned() ? " version " + g.version() : "")
        : ref.display();
    return switch (code) {
      case "NOT_FOUND" -> StoreException.notFound("No secret " + what + " in Secret Manager"
          + (ref instanceof SecretRef.GcpSm g ? " of project " + g.project() : "") + ".");
      case "FAILED_PRECONDITION" -> StoreException.gone("Version " + (ref instanceof SecretRef.GcpSm g ? g.version()
          : "") + " of " + ref.display() + " is disabled or destroyed.");
      case "PERMISSION_DENIED" -> StoreException.denied("The gateway's identity can't read " + ref.display()
          + ": grant it roles/secretmanager.secretAccessor on the secret.");
      case "UNAUTHENTICATED" -> StoreException.unavailable("Google refused the gateway's token: sign in again with "
          + "gcloud auth application-default login.", null);
      default -> status >= 500 || status == 429
          ? StoreException.unavailable("GCP Secret Manager is unavailable (HTTP " + status + "). Test again.", null)
          : StoreException.invalid("GCP Secret Manager refused the request (HTTP " + status
              + (error.path("message").isString() ? ": " + error.path("message").asString() : "") + ").");
    };
  }

  static void closeQuietly(SecretResolver secrets) {
    if (secrets instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // Zeroing never fails.
      }
    }
  }

  private static long ms(long since) {
    return (System.nanoTime() - since) / 1_000_000;
  }

  @Override
  public void close() {
    cloud.evict(owner.id());
    http.close();
  }
}
