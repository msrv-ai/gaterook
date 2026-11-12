package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.support.Glob;
import ai.msrv.gaterook.connectors.support.Pem;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * What every vector database and search engine here shares: the connection's endpoint, its credentials as headers, its
 * list of collections or indexes, and how a request goes out. A backend is one per connector type and holds no state
 * of a connection beyond caches it drops in {@link #forget}.
 */
abstract class StoreBackend implements AutoCloseable {

  static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** The connector type: {@code qdrant}. */
  abstract String type();

  /** The type's name: {@code Qdrant}. */
  abstract String name();

  /** The card's text. */
  abstract String description();

  /** The config field of the connection's list of names: {@code collections} or {@code indexes}. */
  abstract String listField();

  /** The connection's address for texts and the guard, without I/O. */
  abstract ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException;

  /** The headers that sign a request in, the secrets resolved and tainted; empty without credentials. */
  abstract Map<String, List<String>> auth(Call c) throws ConnectorException;

  /**
   * The headers that sign {@code r} in: {@link #auth(Call)}, the same for every request, unless a backend checks
   * where each request goes first (Azure AI Search's Entra ID tokens).
   */
  Map<String, List<String>> auth(Call c, ServiceHttp.Request r) throws ConnectorException {
    return auth(c);
  }

  /** What a backend does with an answer before it is read: Azure AI Search drops an Entra ID token refused with 401. */
  void answered(Call c, ServiceHttp.Response r) {}

  /** The credential headers as an approver sees them: {@code api-key: ‹secret›}. */
  abstract List<String> authShown(ConnectionConfig cfg);

  /** The store's error answer, in its own words, as one sentence; {@code null} when it said nothing usable. */
  abstract String said(ServiceHttp.Response r);

  /** Why {@code name} can't be a collection or index here, or {@code null}: no I/O. */
  abstract String nameProblem(String name);

  /** Test connection: the server, the credentials and what agents will see. */
  abstract TestResult test(Call c);

  /** What describe_connection says of the connection, without I/O. */
  abstract JsonNode describeConnection(ConnectionConfig cfg);

  /** Drops what a backend keeps of a connection. */
  void forget(String connectionId) {}

  @Override
  public void close() {}

  /** Whether the connection's list allows {@code name} (empty: every name). */
  final boolean allows(ConnectionConfig cfg, String name) {
    List<String> listed = cfg.strings("/" + listField());
    return listed.isEmpty() || Glob.anyText(listed, name);
  }

  /** Sends {@code r} to the connection's endpoint with its credentials. */
  final ServiceHttp.Response send(Call c, ServiceHttp.Request r, long maxBytes) throws ConnectorException {
    return send(c, endpoint(c.cfg()), r, maxBytes);
  }

  /** Sends {@code r} to {@code endpoint} with the connection's credentials. */
  final ServiceHttp.Response send(Call c, ServiceHttp.Endpoint endpoint, ServiceHttp.Request r, long maxBytes)
      throws ConnectorException {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    if (r.headers() != null) {
      headers.putAll(r.headers());
    }
    headers.putAll(auth(c, r));
    ServiceHttp.Response response = ServiceHttp.send(c.ctx(), endpoint, new ServiceHttp.Request(r.method(), r.uri(),
        headers, r.body(), r.what(), r.change()), maxBytes);
    answered(c, response);
    return response;
  }

  /** Sends {@code r} and returns its JSON answer; another status than 2xx is an error in the store's words. */
  final JsonNode json(Call c, ServiceHttp.Request r, long maxBytes) throws ConnectorException {
    ServiceHttp.Response response = send(c, r, maxBytes);
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    return ServiceHttp.json(response, r.what());
  }

  /**
   * An error answer as the exception the core counts, with the store's words. Azure AI Search words 401 and 403 of an
   * Entra ID sign-in itself: they name the roles the identity lacks.
   */
  ConnectorException failure(ConnectionConfig cfg, ServiceHttp.Request r, ServiceHttp.Response response) {
    String words = said(response);
    String text = name() + " refused " + r.what() + " (" + response.status() + ")"
        + (words == null ? "." : ": " + ServiceHttp.sentence(words));
    return switch (response.status()) {
      case 401 -> ConnectorException.denied(name() + " refused the credentials of " + cfg.name() + " ("
          + response.status() + ")" + (words == null ? "" : ": " + words) + ". Check them under Credentials.");
      case 403 -> ConnectorException.denied(text + (r.change() ? " The credentials may not change data: nothing "
          + "changed." : " The credentials may not do this."));
      case 404 -> ConnectorException.badInput(text);
      case 408, 504 -> r.change() ? ConnectorException.outcomeUnknown(text + " Whether it changed anything is not "
          + "known: read the records back before trying again.", null) : ConnectorException.timeout(text);
      case 429 -> ConnectorException.unavailable(text + " The server is rate limiting: try again later.", null);
      default -> response.status() >= 500
          ? (r.change() ? ConnectorException.outcomeUnknown(text + " Whether it changed anything is not known: read "
              + "the records back before trying again.", null) : ConnectorException.unavailable(text, null))
          : ConnectorException.badInput(text);
    };
  }

  /** A secret of the connection as text, tainted; {@code what} names it for the person. */
  static String secret(Call c, String field, String what) throws ConnectorException {
    if (!c.secrets().isSet(field)) {
      throw ConnectorException.unavailable("The connection has no stored " + what + ": set it under Credentials.",
          null);
    }
    try (SecretResolver.Secret secret = c.secrets().secret(field)) {
      String value = secret.unsafeString().strip();
      if (value.isEmpty() || value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
        throw ConnectorException.unavailable("The stored " + what + " is empty or holds characters a key doesn't "
            + "have: store it again under Credentials.", null);
      }
      c.ctx().taint(value);
      return value;
    }
  }

  /** The connection's private CA ({@code advanced.caCertificate}), or none. */
  static List<X509Certificate> ca(ConnectionConfig cfg) throws ConnectorException {
    String pem = cfg.string("/advanced/caCertificate");
    if (pem == null || pem.isBlank()) {
      return List.of();
    }
    try {
      return Pem.certificates(pem);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The CA certificate under Advanced can't be read: "
          + ServiceHttp.sentence(e.getMessage()));
    }
  }

  /** {@code text} cut to {@code max} characters with an ellipsis. */
  static String shorten(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
  }

  /** {@code n noun(s)}. */
  static String count(long n, String noun) {
    return count(n, noun, noun + "s");
  }

  /** {@code n noun} or {@code n plural}. */
  static String count(long n, String noun, String plural) {
    return n + " " + (n == 1 ? noun : plural);
  }
}
