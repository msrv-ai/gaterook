package ai.msrv.gaterook.core.admin.templates;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import okhttp3.HttpUrl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /api/templates/openapi/parse} (ARCHITECTURE.md §6 connectors): the document a person uploaded or
 * named by URL, read by the connector's importer into a proposal; nothing is saved. A URL is downloaded through the
 * egress guard (report 10 §6.5): {@code https://} only, at most 3 redirects each checked again, 20 s, 20 MB, the
 * environment's private ranges for the connection, and never this computer — so a document URL can't reach the
 * core's admin API.
 */
@Component
class OpenApiImports {

  static final int MAX_BYTES = 20 * 1_024 * 1_024;
  private static final int MAX_REDIRECTS = 3;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** Where the document came from, for the answer. */
  record Fetched(byte[] bytes, String kind, String url) {}

  private final ConnectionConfigs connections;
  private final ConnectorRegistry registry;
  private final EnvironmentPolicies policies;
  private final EgressClients egress;
  private final ObjectProvider<TemplateImporter> importers;

  OpenApiImports(ConnectionConfigs connections, ConnectorRegistry registry, EnvironmentPolicies policies,
      EgressClients egress, ObjectProvider<TemplateImporter> importers) {
    this.connections = connections;
    this.registry = registry;
    this.policies = policies;
    this.egress = egress;
    this.importers = importers;
  }

  /** The importer's proposal for a document of {@code connection}. */
  TemplateImporter.Result parse(String connection, String url, String blob, String text, JsonNode options) {
    TemplateImporter importer = importers.orderedStream().filter(i -> i.kind().equals("openapi")).findFirst()
        .orElseThrow(() -> ApiRequestException.unprocessable("OpenAPI import unavailable", "No connector of this "
            + "build imports OpenAPI files.", "/kind"));
    if (connection == null || connection.isBlank()) {
      throw ApiRequestException.unprocessable("Connection missing", "Pick the HTTP connection the tools run on.",
          "/connection");
    }
    ConnectionConfigs.Stored runsOn = connections.byName(connection).orElseThrow(() ->
        ApiRequestException.unprocessable("Connection not found", "There is no connection " + connection + ".",
            "/connection"));
    if (!runsOn.verified()) {
      throw ApiRequestException.conflict("Changed outside GateRook", runsOn.name() + " was changed outside GateRook. Review it first.");
    }
    TemplateKind kind = registry.templateKinds().get("openapi");
    if (kind == null || !kind.connectionTypes().contains(runsOn.config().type())) {
      throw ApiRequestException.unprocessable("Wrong connection", "OpenAPI templates run on HTTP API connections.",
          "/connection");
    }
    Fetched fetched = fetch(url, blob, text, runsOn);
    return importer.importDocument(fetched.bytes(), runsOn.config(), withReserved(options));
  }

  /**
   * The person's options plus the names of the built-in tools and their families: the importer proposes no tool a
   * template may not have (TemplateService refuses them again at save).
   */
  private JsonNode withReserved(JsonNode options) {
    ObjectNode out = options instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode();
    ObjectNode reserved = out.putObject("reserved");
    ArrayNode names = reserved.putArray("names");
    new TreeSet<>(registry.reservedToolNames()).forEach(names::add);
    ArrayNode prefixes = reserved.putArray("prefixes");
    new TreeSet<>(registry.reservedPrefixes()).forEach(prefixes::add);
    return out;
  }

  Fetched fetch(String url, String blob, String text, ConnectionConfigs.Stored runsOn) {
    int given = (url != null && !url.isBlank() ? 1 : 0) + (blob != null && !blob.isBlank() ? 1 : 0)
        + (text != null && !text.isBlank() ? 1 : 0);
    if (given != 1) {
      throw ApiRequestException.unprocessable("Document missing", "Send the file (blob or text) or its URL, one of "
          + "them.", "/url");
    }
    if (text != null && !text.isBlank()) {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      return new Fetched(checkSize(bytes, "/text"), "file", null);
    }
    if (blob != null && !blob.isBlank()) {
      byte[] bytes;
      try {
        bytes = Base64.getMimeDecoder().decode(blob.replace('-', '+').replace('_', '/'));
      } catch (IllegalArgumentException e) {
        throw ApiRequestException.unprocessable("File unreadable", "blob is the file in Base64.", "/blob");
      }
      return new Fetched(checkSize(bytes, "/blob"), "file", null);
    }
    return new Fetched(download(url, runsOn), "url", url);
  }

  private static byte[] checkSize(byte[] bytes, String field) {
    if (bytes.length > MAX_BYTES) {
      throw ApiRequestException.unprocessable("File too large", "The file is larger than 20 MB.", field);
    }
    return bytes;
  }

  private byte[] download(String url, ConnectionConfigs.Stored runsOn) {
    HttpUrl current = httpsUrl(url);
    List<Cidr> ranges = new ArrayList<>();
    for (String cidr : policies.allowedPrivateRanges(runsOn.config().env(), runsOn.name())) {
      ranges.add(Cidr.parse(cidr));
    }
    AddressPolicy policy = AddressPolicy.builder().neverLoopback().allowPrivate(ranges)
        .corePorts(egress.settings().corePorts().get()).build();
    EgressSpec spec = new EgressSpec("openapi-url", policy, Duration.ofSeconds(5), TIMEOUT, EgressSpec.Tls.PLATFORM);
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      ExecContext.EgressResponse response;
      try {
        response = egress.send(spec, new ExecContext.EgressRequest("GET", URI.create(current.toString()),
            Map.of("Accept", List.of("application/json, application/yaml;q=0.9, text/yaml;q=0.9, */*;q=0.5")), null,
            TIMEOUT), MAX_BYTES);
      } catch (ConnectorException e) {
        throw ApiRequestException.unprocessable("Can't download the file", e.getMessage(), "/url");
      }
      int status = response.status();
      if (status >= 300 && status < 400) {
        List<String> location = response.headers().get("location");
        HttpUrl next = location == null || location.isEmpty() ? null : current.resolve(location.getFirst());
        if (next == null) {
          throw ApiRequestException.unprocessable("Can't download the file", current.host() + " answered " + status
              + " without a usable Location.", "/url");
        }
        current = httpsUrl(next.toString());
        continue;
      }
      if (status < 200 || status >= 300) {
        throw ApiRequestException.unprocessable("Can't download the file", current.host() + " answered " + status
            + ".", "/url");
      }
      if (response.truncated()) {
        throw ApiRequestException.unprocessable("File too large", "The file is larger than 20 MB.", "/url");
      }
      return response.body();
    }
    throw ApiRequestException.unprocessable("Can't download the file", "More than " + MAX_REDIRECTS
        + " redirects.", "/url");
  }

  private static HttpUrl httpsUrl(String url) {
    HttpUrl parsed;
    try {
      parsed = UrlRules.parse(url);
    } catch (UrlRules.UrlRejectedException e) {
      throw ApiRequestException.unprocessable("Invalid URL", e.getMessage() + ".", "/url");
    }
    if (!parsed.scheme().equals("https")) {
      throw ApiRequestException.unprocessable("Invalid URL", "Documents are downloaded over https:// only.", "/url");
    }
    return parsed;
  }
}
