package com.gaterook.core.secrets.store.azure;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.cloudauth.AzureCloud;
import com.gaterook.connectors.cloudauth.AzureCloud.Audience;
import com.gaterook.connectors.cloudauth.AzureCredentials;
import com.gaterook.connectors.cloudauth.AzureSpec;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.core.secrets.SecretStores;
import com.gaterook.core.secrets.internal.persistence.StoreKind;
import com.gaterook.core.secrets.store.RemoteValue;
import com.gaterook.core.secrets.store.SecretJson;
import com.gaterook.core.secrets.store.SecretRef;
import com.gaterook.core.secrets.store.SecretStore;
import com.gaterook.core.secrets.store.StoreCall;
import com.gaterook.core.secrets.store.StoreException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An Azure Key Vault store (report 12 §4.3, §5): one Key Vault REST {@code GET /secrets/<name>[/<version>]} per read,
 * with an Entra ID token for Key Vault from {@link CloudCredentials#azure()}, signed in as the store's service
 * principal with a client secret or a certificate stored here. A store reads one vault, at the host the cloud's table
 * gives ({@code <vault>.vault.azure.net}, or that of Azure Government or Azure China): the token goes to that host
 * only, and a reference of another vault is refused before anything is sent. The token of every request goes to the
 * call's taint; response bodies are zeroed.
 *
 * <p>The Azure CLI and code sign-ins of the Azure connectors aren't offered for stores yet: both need a step of the
 * person's (allowing the exact CLI command, entering a code) that testing a store doesn't have.
 */
public final class AzureKvStore implements SecretStore {

  /** The secret field of a service principal's client secret. */
  public static final String CLIENT_SECRET = "/credentials/clientSecret";
  /** The secret field of a service principal's certificate: its RSA private key and certificate as PEM. */
  public static final String CERTIFICATE = "/credentials/certificate";

  /** The Key Vault REST API version (the Azure connector's too). */
  static final String API_VERSION = "7.5";
  /** More than any answer: Key Vault keeps at most 25 KB in a secret. */
  private static final int MAX_BODY = 1 << 20;
  private static final Pattern AKV_CODE = Pattern.compile("AKV\\d{5}");
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9.]{1,64}");

  /**
   * @param vault the key vault's name ({@code acme-kv}): the store reads its secrets only
   */
  public record Settings(String vault, AzureSpec credentials, int cacheTtlSeconds) {

    public static Settings of(JsonNode config) {
      String vault = config.path("vault").asString("").strip();
      if (!SecretRef.AZURE_VAULT.matcher(vault).matches()) {
        throw StoreException.invalid(vault.contains(".") ? "Enter the key vault's name, like acme-kv, not its "
            + "address." : "Enter the name of the key vault, like acme-kv: 3 to 24 lower-case letters, digits and "
            + "hyphens, starting with a letter.");
      }
      AzureSpec spec;
      try {
        spec = AzureSpec.fromConfig(config, "/credentials");
      } catch (IllegalArgumentException e) {
        throw StoreException.invalid(e.getMessage());
      }
      if (spec.source() != AzureSpec.Source.CLIENT_SECRET && spec.source() != AzureSpec.Source.CLIENT_CERTIFICATE) {
        throw StoreException.invalid("A Key Vault store signs in as a service principal, with a client secret or a "
            + "certificate. The Azure CLI and signing in with a code aren't available for stores yet.");
      }
      int ttl = config.path("cacheTtlSeconds").asInt(60);
      if (ttl < 0 || ttl > 300) {
        throw StoreException.invalid("Keep a resolved value for 0 to 300 seconds.");
      }
      return new Settings(vault, spec, ttl);
    }

    /** {@code acme-kv.vault.azure.net}: the only host the store's tokens go to. */
    public String host() {
      return credentials.cloud().keyVaultHost(vault);
    }
  }

  private final Settings settings;
  private final CloudCredentials cloud;
  private final CloudCredentials.Owner owner;
  private final Supplier<? extends SecretResolver> signIn;
  private final HttpClient http;

  /**
   * @param signIn the store's own secrets for one use (the client secret or the certificate); closed after it
   */
  public AzureKvStore(Settings settings, CloudCredentials cloud, CloudCredentials.Owner owner,
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
  public StoreKind kind() {
    return StoreKind.AZURE_KV;
  }

  @Override
  public Duration cacheTtl() {
    return Duration.ofSeconds(settings.cacheTtlSeconds());
  }

  public Settings settings() {
    return settings;
  }

  @Override
  public RemoteValue read(SecretRef reference, StoreCall call) {
    SecretRef.AzureKv ref = (SecretRef.AzureKv) reference;
    if (!ref.vault().equals(settings.vault())) {
      throw StoreException.invalid(ref.uri() + " is in key vault " + ref.vault() + ", and this store reads "
          + settings.vault() + " only. Connect " + ref.vault() + " as a store of its own.");
    }
    URI uri = url("/secrets/" + ref.name() + (ref.version() == null ? "" : "/" + ref.version()), null);
    byte[] data = null;
    try (Answer answer = get(uri, call)) {
      if (answer.status() != 200) {
        throw failure(answer, ref);
      }
      JsonNode attributes = SecretJson.tree(answer.body(), List.of("attributes"));
      if (attributes.path("enabled").isBoolean() && !attributes.path("enabled").asBoolean()) {
        throw disabled(ref);
      }
      SecretJson.Picked picked = SecretJson.pick(answer.body(), List.of(), "value");
      if (picked.value() == null || !picked.string()) {
        picked.clear();
        throw StoreException.notFound(ref.name() + " has no value in key vault " + settings.vault() + ".");
      }
      data = picked.value();
      String version = version(SecretJson.tree(answer.body(), List.of("id")).asString(""));
      return value(ref, data, version, instant(attributes.path("created")));
    } catch (IllegalArgumentException e) {
      throw StoreException.unavailable("Key vault " + settings.vault() + " answered with something that isn't "
          + "JSON.", null);
    } finally {
      if (data != null) {
        Arrays.fill(data, (byte) 0);
      }
    }
  }

  /** The value of {@code data} for {@code ref}: the whole secret or one key of its JSON. */
  static RemoteValue value(SecretRef ref, byte[] data, String version, Instant created) {
    boolean object = SecretJson.isObject(data);
    List<String> keys = List.of();
    if (object) {
      SecretJson.Picked all = SecretJson.pickTop(data, null);
      keys = all.keys();
      all.clear();
    }
    if (ref.key() == null) {
      return new RemoteValue(data.clone(), version, created, object ? "json" : "string", keys);
    }
    if (!object) {
      throw StoreException.notFound(ref.display() + " isn't JSON, so it has no key \"" + ref.key()
          + "\". Remove #" + ref.key() + " from the reference.");
    }
    SecretJson.Picked picked = SecretJson.pickTop(data, ref.key());
    if (picked.value() == null) {
      throw StoreException.missingKey(ref.key());
    }
    return new RemoteValue(picked.value(), version, created, picked.string() ? "string" : "json", picked.keys());
  }

  /** Seconds since the epoch, as Key Vault writes its dates; {@code null} when absent or out of range. */
  private static Instant instant(JsonNode seconds) {
    if (!seconds.isIntegralNumber()) {
      return null;
    }
    try {
      return Instant.ofEpochSecond(seconds.asLong());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The version an answer's {@code id} names: {@code https://<vault host>/secrets/<name>/<version>}. */
  static String version(String id) {
    int at = id.indexOf("/secrets/");
    if (at < 0) {
      return null;
    }
    String[] rest = id.substring(at + "/secrets/".length()).split("/", -1);
    return rest.length == 2 && SecretRef.AZURE_VERSION.matcher(rest[1]).matches() ? rest[1] : null;
  }

  @Override
  public SecretStores.TestResult test(List<SecretRef> refs, StoreCall call) {
    long t0 = System.nanoTime();
    List<SecretStores.Check> checks = new ArrayList<>();
    List<SecretStores.Hint> hints = new ArrayList<>();
    ObjectNode identity = JsonNodeFactory.instance.objectNode();
    identity.put("vault", settings.vault());
    identity.put("host", settings.host());
    identity.put("cloud", settings.credentials().cloud().id());
    identity.put("tenant", settings.credentials().tenantId());
    identity.put("identity", settings.credentials().identity());
    try {
      long s = System.nanoTime();
      AzureCredentials.AccessToken token = token(call);
      identity.put("account", token.account());
      if (token.tenantId() != null) {
        identity.put("tenantId", token.tenantId());
      }
      checks.add(new SecretStores.Check("token", true, ms(s), "Signed in as " + settings.credentials().identity()
          + (token.tenantId() == null ? "" : " in tenant " + token.tenantId())));
      s = System.nanoTime();
      try (Answer list = get(url("/secrets", "maxresults=1"), call)) {
        String inner = list.status() == 403 ? code(list, "innererror") : "";
        if (list.status() == 200) {
          checks.add(new SecretStores.Check("list", true, ms(s), "Can list the secret names of " + settings.vault()));
        } else if (inner.equals("ForbiddenByRbac") || inner.equals("AccessDenied")) {
          // Get alone is all a reference needs: an identity that can't list names is the well-cut one.
          checks.add(new SecretStores.Check("list", true, ms(s), "Can't list the secret names of " + settings.vault()
              + " (expected when the identity may only get secrets)"));
        } else {
          throw failure(list, null);
        }
      }
      for (SecretRef r : refs) {
        if (r instanceof SecretRef.AzureKv ref && !ref.vault().equals(settings.vault())) {
          hints.add(new SecretStores.Hint("danger", "store.other-vault", ref.uri() + " is in key vault " + ref.vault()
              + ", which this store doesn't read. Connect " + ref.vault() + " as a store of its own."));
        }
      }
      return new SecretStores.TestResult(true, ms(t0), null, identity, List.of(), hints, checks, null, null);
    } catch (StoreException e) {
      checks.add(new SecretStores.Check("failed", false, ms(t0), e.getMessage()));
      return new SecretStores.TestResult(false, ms(t0), null, identity, List.of(), hints, checks, e.getMessage(),
          e.reason().name().toLowerCase(Locale.ROOT));
    }
  }

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code https://<vault host><path>?api-version=7.5[&query]}, or the same under the test endpoint of Key Vault. */
  private URI url(String path, String query) {
    URI base;
    try {
      base = cloud.azure().dataPlane(settings.credentials().cloud(), Audience.KEY_VAULT, settings.host());
    } catch (IllegalArgumentException e) {
      throw StoreException.invalid(e.getMessage());
    }
    String b = base.toString();
    return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path + "?api-version=" + API_VERSION
        + (query == null ? "" : "&" + query));
  }

  /** The store's Key Vault token, handed to the call's taint. */
  private AzureCredentials.AccessToken token(StoreCall call) {
    SecretResolver secrets = signIn.get();
    try {
      AzureCredentials.AccessToken token = cloud.azure().token(owner, settings.credentials(), Audience.KEY_VAULT,
          secrets, call.requestTimeout());
      call.taint().accept(token.value());
      return token;
    } catch (ConnectorException e) {
      throw e.kind() == ConnectorException.Kind.BAD_INPUT ? StoreException.invalid(e.getMessage())
          : StoreException.unavailable(e.getMessage(), e);
    } finally {
      closeQuietly(secrets);
    }
  }

  /** One answer of Key Vault; {@link #close()} zeroes the body. */
  private record Answer(int status, byte[] body) implements AutoCloseable {
    @Override
    public void close() {
      Arrays.fill(body, (byte) 0);
    }
  }

  private Answer get(URI uri, StoreCall call) {
    AzureCloud azure = settings.credentials().cloud();
    if (!cloud.azure().sendsTokensTo(azure, Audience.KEY_VAULT, uri)) {
      throw StoreException.invalid("GateRook sends Key Vault tokens to " + settings.host() + " only.");
    }
    AzureCredentials.AccessToken token = token(call);
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(call.requestTimeout())
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + token.value())
        .header("x-ms-client-request-id", UUID.randomUUID().toString())
        .GET().build();
    byte[] body;
    int status;
    try {
      HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      status = response.statusCode();
      try (InputStream in = response.body()) {
        body = in.readNBytes(MAX_BODY + 1);
      }
    } catch (HttpConnectTimeoutException | ConnectException e) {
      throw StoreException.unavailable("Can't reach key vault " + settings.vault() + ": check its name and the "
          + "network, then test again.", e);
    } catch (HttpTimeoutException e) {
      throw StoreException.unavailable("Key vault " + settings.vault() + " didn't answer in time.", e);
    } catch (IOException e) {
      throw StoreException.unavailable("Key vault " + settings.vault() + " can't be reached: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StoreException.unavailable("The call was cancelled while key vault " + settings.vault() + " answered.",
          e);
    }
    if (body.length > MAX_BODY) {
      Arrays.fill(body, (byte) 0);
      throw StoreException.unavailable("Key vault " + settings.vault() + " answered with more than 1 MB.", null);
    }
    if (status == 401) {
      // The next request signs in again: a token of another tenant or a revoked one isn't asked for twice.
      cloud.azure().invalidate(owner.id(), Audience.KEY_VAULT);
    }
    return new Answer(status, body);
  }

  /** Key Vault's error as words to act on; its message (addresses, object IDs) isn't repeated. */
  private StoreException failure(Answer answer, SecretRef.AzureKv ref) {
    String vault = settings.vault();
    String what = ref == null ? null : ref.name() + (ref.pinned() ? " version " + ref.version() : "");
    String code = code(answer, null);
    int status = answer.status();
    if (status == 404 && ref != null) {
      return StoreException.notFound("No secret " + what + " in key vault " + vault + ".");
    }
    if (status == 403) {
      String inner = code(answer, "innererror");
      return switch (inner) {
        case "SecretDisabled" -> ref != null ? disabled(ref) : StoreException.gone("Key vault " + vault
            + " refused a disabled secret.");
        case "ForbiddenByFirewall" -> StoreException.denied("The firewall of key vault " + vault + " blocks this "
            + "computer: allow its address under Networking in the vault, then test again.");
        case "ForbiddenByConnection" -> StoreException.denied("Key vault " + vault + " takes no requests from public "
            + "networks: allow this computer's network under Networking in the vault, or reach it through a private "
            + "endpoint.");
        default -> StoreException.denied("The gateway's identity (" + settings.credentials().identity() + ") can't "
            + "read " + (what == null ? "the secrets of key vault " + vault : what) + ": assign Key Vault Secrets User "
            + "on the secret or vault" + (inner.equals("AccessDenied") ? ", or allow Get on secrets in the vault's "
            + "access policy" : "") + ".");
      };
    }
    if (status == 401) {
      Matcher akv = AKV_CODE.matcher(SecretJson.tree(answer.body(), List.of("error")).path("message").asString(""));
      return StoreException.unavailable("Key vault " + vault + " refused the gateway's token" + (akv.find() ? " ("
          + akv.group() + ")" : "") + ". Check that the vault belongs to tenant " + settings.credentials().tenantId()
          + ", then test again.", null);
    }
    if (status == 429) {
      return StoreException.unavailable("Key vault " + vault + " is throttling requests (HTTP 429). Try again in a "
          + "moment.", null);
    }
    if (status >= 500) {
      return StoreException.unavailable("Key vault " + vault + " is unavailable (HTTP " + status + "). Test again.",
          null);
    }
    return StoreException.invalid("Key vault " + vault + " refused the request (HTTP " + status + (code.isEmpty() ? ""
        : ", " + code) + ").");
  }

  private StoreException disabled(SecretRef.AzureKv ref) {
    return StoreException.gone((ref.pinned() ? "Version " + ref.version() + " of " + ref.name() : ref.name())
        + " is disabled in key vault " + settings.vault() + ". Enable it there, or point to another version.");
  }

  /** {@code error.code} or {@code error.<nested>.code} of an answer, when it looks like a code; else empty. */
  private static String code(Answer answer, String nested) {
    JsonNode error = SecretJson.tree(answer.body(), List.of("error"));
    String code = (nested == null ? error : error.path(nested)).path("code").asString("");
    return ERROR_CODE.matcher(code).matches() ? code : "";
  }

  private static void closeQuietly(SecretResolver secrets) {
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
