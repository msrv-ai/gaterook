package ai.msrv.gaterook.core.secrets.store.vault;

import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import ai.msrv.gaterook.core.secrets.store.SecretJson;
import ai.msrv.gaterook.core.secrets.store.StoreCall;
import ai.msrv.gaterook.core.secrets.store.StoreException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLException;
import tools.jackson.databind.JsonNode;

/**
 * The HTTP side of a Vault/OpenBao store (report 12 §6, §7.5): {@code java.net.http}, no redirects (a 307 from a
 * standby would carry the token to another host), no proxy unless the store says so, the store's own CA, the headers
 * {@code X-Vault-Request} and {@code X-Vault-Namespace}. Bodies are byte arrays the caller zeroes; error texts come
 * from Vault's {@code errors} list, which names paths, never values.
 */
final class VaultHttp implements AutoCloseable {

  /** One answer; {@link #close()} zeroes the body. */
  record Response(int status, byte[] body) implements AutoCloseable {
    JsonNode tree(String... path) {
      return SecretJson.tree(body, List.of(path));
    }

    /** Vault's {@code errors}, joined: "permission denied", "no handler for route …". */
    String errors() {
      JsonNode errors = tree("errors");
      List<String> out = new ArrayList<>();
      errors.forEach(e -> out.add(e.asString()));
      return String.join("; ", out);
    }

    boolean ok() {
      return status >= 200 && status < 300;
    }

    @Override
    public void close() {
      Arrays.fill(body, (byte) 0);
    }
  }

  private final VaultSettings settings;
  private final HttpClient client;

  VaultHttp(VaultSettings settings) {
    this.settings = settings;
    HttpClient.Builder b = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .proxy(settings.useSystemProxy() ? ProxySelector.getDefault() : HttpClient.Builder.NO_PROXY);
    if (settings.caCert() != null) {
      try {
        b.sslContext(TrustStores.sslContext(TrustStores.of(Pem.certificates(settings.caCert()), false)));
      } catch (RuntimeException e) {
        throw StoreException.invalid("The CA certificate of the store can't be read: paste it as PEM.");
      }
    }
    this.client = b.build();
  }

  /**
   * One request; {@code token} goes into {@code X-Vault-Token} (the header API wants a {@code String}, which lives as
   * long as the request).
   *
   * @param body JSON or {@code null}; the caller zeroes it
   * @throws StoreException {@code UNAVAILABLE} when Vault can't be reached or answers from a standby node
   */
  Response send(String method, String path, byte[] body, char[] token, StoreCall call) {
    URI uri = URI.create(settings.address() + "/v1/" + path);
    HttpRequest.Builder r = HttpRequest.newBuilder(uri).timeout(call.requestTimeout())
        .header("X-Vault-Request", "true")
        .header("Accept", "application/json");
    if (settings.namespace() != null) {
      r.header("X-Vault-Namespace", settings.namespace());
    }
    if (token != null) {
      r.header("X-Vault-Token", new String(token));
    }
    if (body != null) {
      r.header("Content-Type", "application/json");
      r.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    } else {
      r.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<byte[]> response;
    try {
      response = client.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpConnectTimeoutException | ConnectException e) {
      throw StoreException.unavailable("Can't reach Vault at " + settings.address() + ". Check the address and the "
          + "VPN, then test again.", e);
    } catch (HttpTimeoutException e) {
      throw StoreException.unavailable("Vault at " + settings.address() + " didn't answer in time.", e);
    } catch (SSLException e) {
      throw StoreException.unavailable("The TLS handshake with Vault at " + settings.address() + " failed: "
          + e.getMessage() + ". Add the CA certificate of Vault to the store.", e);
    } catch (IOException e) {
      throw StoreException.unavailable("Vault at " + settings.address() + " can't be reached: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StoreException.unavailable("The call was cancelled while Vault answered.", e);
    }
    int status = response.statusCode();
    if (status >= 300 && status < 400) {
      // Following it would hand the token to the host the redirect names (report 12 §6).
      throw StoreException.unavailable("Vault answered from a standby node. Use the active address or the load "
          + "balancer.", null);
    }
    return new Response(status, response.body() == null ? new byte[0] : response.body());
  }

  @Override
  public void close() {
    client.close();
  }
}
