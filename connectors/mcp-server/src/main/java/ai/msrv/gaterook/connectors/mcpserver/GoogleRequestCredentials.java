package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.GcpSpec;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * A Google access token on each request (ARCHITECTURE.md §7.6). Google's MCP servers
 * ({@code https://<service>.googleapis.com/mcp}) take one in place of an OAuth sign-in, which they offer only to an
 * OAuth app the person registers. It comes from the server's Google Cloud credentials: Google sign-in on this computer
 * (ADC) or a service account key, optionally impersonating a service account, through the core's
 * {@link CloudCredentials}, with {@code x-goog-user-project} for a billed project. The auth library renews the token
 * before it expires.
 */
final class GoogleRequestCredentials implements RequestCredentials {

  /** Where the settings keep the credentials; the key is the secret field {@code /auth/credentials/serviceAccountKey}. */
  static final String POINTER = "/auth/credentials";

  private final GoogleCredentials credentials;
  private final Consumer<CharSequence> taint;
  private volatile String token;

  GoogleRequestCredentials(GoogleCredentials credentials, Consumer<CharSequence> taint) {
    this.credentials = credentials;
    this.taint = taint;
  }

  /**
   * The credentials of a stored or draft server: its {@code auth.credentials}, for the scopes of {@code auth.scopes}
   * ({@code cloud-platform} by default, which every Google Cloud MCP server takes).
   *
   * @throws ConnectorException {@code BAD_INPUT} for settings GateRook can't use, {@code RESOURCE_UNAVAILABLE} when
   *                            there is no Google sign-in on this computer or the key can't be read
   */
  static GoogleRequestCredentials of(CloudCredentials cloud, ConnectionConfig cfg, SecretResolver secrets,
      Consumer<CharSequence> taint) throws ConnectorException {
    GcpSpec spec;
    try {
      spec = GcpSpec.fromConfig(cfg.config(), POINTER);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput(cfg.name() + ": " + e.getMessage());
    }
    GoogleCredentials c = cloud.gcp(new CloudCredentials.Owner(cfg.id(), cfg.version(), cfg.name()), spec,
        scopes(cfg.config()), secrets);
    return new GoogleRequestCredentials(c, taint);
  }

  /** The scopes of the token: {@code auth.scopes}, space-separated, else {@code cloud-platform}. */
  static List<String> scopes(JsonNode config) {
    String scopes = Texts.str(config.at("/auth/scopes"), null);
    return scopes == null || scopes.isBlank() ? List.of(CloudCredentials.CLOUD_PLATFORM)
        : List.of(scopes.strip().split("\\s+"));
  }

  @Override
  public Map<String, String> headers(String method, URI url, byte[] body) throws ConnectorException {
    Map<String, String> out = new LinkedHashMap<>();
    CloudCredentials.requestMetadata(credentials, url, this::issued).forEach((name, values) -> {
      if (!values.isEmpty()) {
        out.put(name, values.getFirst());
      }
    });
    return out;
  }

  private void issued(CharSequence value) {
    token = value.toString();
    taint.accept(value);
  }

  /** A token refused before it expired (revoked, or the key rotated): a new one, from the same credentials. */
  @Override
  public boolean refused() {
    try {
      credentials.refresh();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public List<String> secrets() {
    String t = token;
    return t == null ? List.of() : List.of(t);
  }

  @Override
  public String label() {
    return "Google Cloud credentials";
  }
}
