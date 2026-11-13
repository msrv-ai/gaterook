package com.gaterook.connectors.logs;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import software.amazon.awssdk.profiles.Profile;
import software.amazon.awssdk.profiles.ProfileFile;
import tools.jackson.databind.JsonNode;

/**
 * Help for the forms of every log and search service of this module ({@link
 * com.gaterook.connector.api.Connector#suggest}), chosen by the config's {@code backend}: Loki, OpenSearch and
 * Elasticsearch, AWS CloudWatch, GCP Cloud Logging and Azure Monitor Logs, the legacy {@code logs} type included.
 *
 * <p>{@code LOCAL} reads this computer only: the cloud CLIs' files ({@link CloudLocal}), what was typed, and a server on
 * a loopback address ({@link Loopback}: a TLS hello and one GET without credentials). {@code SERVICE} signs in as Test
 * connection does and lists what the account may read. Its clients and credentials are the suggestion's own and are
 * dropped afterwards: a draft's settings never land in the clients of a saved connection.
 */
final class LogsSuggestions {

  private LogsSuggestions() {}

  static Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope,
      CloudCredentials cloud, CloudLocal local) {
    JsonNode c = cfg.config();
    String backend = c == null || !c.isObject() ? null : text(c, "/backend");
    if (backend == null) {
      return Suggestions.none();
    }
    Suggestions.Builder out = Suggestions.builder();
    if (scope == Suggestions.Scope.LOCAL) {
      try {
        switch (backend) {
          case "loki" -> LokiSuggestions.local(cfg, ctx, out);
          case "opensearch", "elasticsearch" -> SearchSuggestions.local(cfg, ctx, local, out);
          case "cloudwatch" -> CloudWatchSuggestions.local(cfg, local, cloud, out);
          case "gcp-logging" -> GcpLoggingSuggestions.local(cfg, secrets, local, out);
          case "azure-monitor" -> AzureMonitorSuggestions.local(cfg, local, out);
          default -> { }
        }
      } catch (IllegalArgumentException e) {
        // A draft still being typed: what was found so far.
      }
      return out.build();
    }
    ConnectionConfig own = new ConnectionConfig("suggest-" + UUID.randomUUID(), cfg.name(), cfg.type(), cfg.env(),
        cfg.version(), c, cfg.secretFields(), cfg.metadata());
    try (Clients clients = new Clients()) {
      Backend.Call call = new Backend.Call(own, secrets, ctx, clients, cloud, 256);
      switch (backend) {
        case "loki" -> LokiSuggestions.service(settings(cfg, Settings.Loki.class), call, out);
        case "opensearch", "elasticsearch" -> SearchSuggestions.service(cfg, call, out);
        case "cloudwatch" -> CloudWatchSuggestions.service(cfg, call, out);
        case "gcp-logging" -> GcpLoggingSuggestions.service(cfg, secrets, local, call, out);
        case "azure-monitor" -> AzureMonitorSuggestions.service(cfg, call, out);
        default -> { }
      }
    } catch (ConnectorException e) {
      failure(out, e, c);
    } catch (IllegalArgumentException e) {
      // The settings don't hold together yet (Settings, AwsSpec, GcpSpec and AzureSpec explain themselves).
      out.note(e.getMessage());
    } finally {
      cloud.evict(own.id());
    }
    return out.build();
  }

  static <T extends Settings> T settings(ConnectionConfig cfg, Class<T> type) {
    Settings s = Settings.parse(cfg);
    if (!type.isInstance(s)) {
      throw new IllegalArgumentException("These settings are for another service.");
    }
    return type.cast(s);
  }

  /** A failed sign-in as a note: a certificate gets what to do about it, anything else the connector's own words. */
  static void failure(Suggestions.Builder out, ConnectorException e, JsonNode config) {
    Throwable cause = e.getCause();
    String url = text(config, "/url");
    if (cause != null && url != null && (Tls.wrongName(cause) || Tls.untrusted(cause))) {
      try {
        java.net.URI base = Settings.baseUrl(url, true);
        String address = Settings.hostPort(base);
        out.note(Tls.wrongName(cause) ? Tls.wrongNameNote(address, base.getHost())
            : Tls.untrustedNote(address, text(config, "/backend"), text(config, "/caCertificate") != null));
        return;
      } catch (IllegalArgumentException ignored) {
        // Not an address after all: the connector's words below.
      }
    }
    out.note(e.getMessage());
  }

  // ── Shared ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** A text of the config at {@code pointer}, stripped; {@code null} when absent, blank or not a string. */
  static String text(JsonNode config, String pointer) {
    JsonNode n = config.at(pointer);
    return n.isString() && !n.asString().isBlank() ? n.asString().strip() : null;
  }

  /** The strings of an array at {@code pointer}, stripped, blanks left out. */
  static List<String> strings(JsonNode config, String pointer) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : config.at(pointer)) {
      if (n.isString() && !n.asString().isBlank()) {
        out.add(n.asString().strip());
      }
    }
    return out;
  }

  /** {@code app, job, namespace and 3 more}: the first {@code shown} names of a list. */
  static String some(List<String> names, int shown) {
    if (names.size() <= shown) {
      return String.join(", ", names);
    }
    return String.join(", ", names.subList(0, shown)) + " and " + (names.size() - shown) + " more";
  }

  /** {@code 1 log group}, {@code 12 log groups}. */
  static String count(int n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }

  /**
   * Whether an AWS profile (or one it takes its keys from, {@code source_profile}) runs a command to get credentials
   * ({@code credential_process}). Suggestions never run one: Test connection does, after it said so.
   */
  static boolean awsProfileRunsCommand(CloudEndpoints endpoints, String profile) {
    ProfileFile file;
    try {
      file = awsProfiles(endpoints);
    } catch (RuntimeException e) {
      return false;
    }
    Set<String> seen = new HashSet<>();
    for (String name = profile; name != null && seen.add(name); ) {
      Optional<Profile> p = file.profile(name);
      if (p.isEmpty()) {
        return false;
      }
      if (p.get().property("credential_process").isPresent()) {
        return true;
      }
      name = p.get().property("source_profile").orElse(null);
    }
    return false;
  }

  private static ProfileFile awsProfiles(CloudEndpoints endpoints) {
    if (endpoints.awsConfigFile() == null && endpoints.awsCredentialsFile() == null) {
      return ProfileFile.defaultProfileFile();
    }
    ProfileFile.Aggregator aggregator = ProfileFile.aggregator();
    if (endpoints.awsConfigFile() != null && Files.exists(endpoints.awsConfigFile())) {
      aggregator.addFile(ProfileFile.builder().content(endpoints.awsConfigFile())
          .type(ProfileFile.Type.CONFIGURATION).build());
    }
    if (endpoints.awsCredentialsFile() != null && Files.exists(endpoints.awsCredentialsFile())) {
      aggregator.addFile(ProfileFile.builder().content(endpoints.awsCredentialsFile())
          .type(ProfileFile.Type.CREDENTIALS).build());
    }
    return aggregator.build();
  }
}
