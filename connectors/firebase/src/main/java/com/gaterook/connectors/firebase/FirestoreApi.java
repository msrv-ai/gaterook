package com.gaterook.connectors.firebase;

import com.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Firestore's REST API v1: {@code POST https://firestore.googleapis.com/v1/<name>:<method>} with
 * {@code listCollectionIds}, {@code batchGet}, {@code runQuery} and {@code commit}, and for the form's suggestions
 * {@code GET} of the project's databases; nothing else. {@code <name>} is the connection's database or one of its
 * document paths, each ID checked and percent-encoded ({@link FirestorePath}). A development build's test endpoint
 * {@code gcp.firestore} replaces the base, with the test credentials that go with it; a token goes only where
 * {@code CloudCredentials.sendsGoogleTokensTo} allows. The streaming answers of {@code runQuery} and {@code batchGet},
 * a JSON array, are read one element at a time within a cap.
 */
final class FirestoreApi {

  static final URI API = URI.create("https://firestore.googleapis.com");
  /** Datastore and Firestore only: a key's or an impersonated token reaches no other Google API. */
  static final String SCOPE = "https://www.googleapis.com/auth/datastore";
  static final String WHAT = "Firestore";

  private final GoogleHttp http;

  FirestoreApi(GoogleHttp http) {
    this.http = http;
  }

  GoogleHttp http() {
    return http;
  }

  /** The API's base: the test endpoint of a development build, else Google's. */
  URI base() {
    URI test = http.cloud().endpoints().service("gcp.firestore");
    return test != null ? test : API;
  }

  /** {@code <base>/v1/projects/p/databases/d/documents[/<path>]:<method>}. */
  URI uri(FirestoreSettings s, FirestorePath path, String method) {
    String base = base().toString().replaceAll("/$", "");
    return URI.create(base + "/v1/" + s.documents() + (path.root() ? "" : "/" + path.encoded()) + ":" + method);
  }

  /** {@code <base>/v1/projects/p/databases/d/documents:<method>}: the database's own methods. */
  URI uri(FirestoreSettings s, String method) {
    return uri(s, FirestorePath.ROOT, method);
  }

  /** {@code <base>/v1/projects/p/databases}: the project's databases ({@code databases.list}). */
  URI databases(FirestoreSettings s) {
    return URI.create(base().toString().replaceAll("/$", "") + "/v1/projects/" + s.project() + "/databases");
  }

  Map<String, List<String>> headers(GoogleHttp.Caller c, URI uri) throws ConnectorException {
    return http.token(c, uri, http.cloud()::sendsGoogleTokensTo, true);
  }

  /** A listing ({@code GET}) whose answer is read whole, up to {@link GoogleHttp#MAX_BODY}. */
  JsonNode get(GoogleHttp.Caller c, URI uri) throws ConnectorException {
    Map<String, List<String>> headers = headers(c, uri);
    try (GoogleHttp.Answer a = http.send(c.ctx(), "GET", uri, headers, null, WHAT, false)) {
      if (!a.ok()) {
        throw error(a.status, a.error(), c, false);
      }
      return a.json(GoogleHttp.MAX_BODY);
    }
  }

  /** A request whose answer is read whole, up to {@link GoogleHttp#MAX_BODY}. */
  JsonNode post(GoogleHttp.Caller c, URI uri, ObjectNode body, boolean change) throws ConnectorException {
    return post(c, uri, body.toString().getBytes(StandardCharsets.UTF_8), change);
  }

  /** As {@link #post(GoogleHttp.Caller, URI, ObjectNode, boolean)} with the exact bytes to send. */
  JsonNode post(GoogleHttp.Caller c, URI uri, byte[] body, boolean change) throws ConnectorException {
    Map<String, List<String>> headers = headers(c, uri);
    try (GoogleHttp.Answer a = http.send(c.ctx(), "POST", uri, headers, body, WHAT, change)) {
      if (!a.ok()) {
        throw error(a.status, a.error(), c, change);
      }
      if (change) {
        // Committed: an answer cut short now changes nothing about that.
        try {
          return a.json(GoogleHttp.MAX_BODY);
        } catch (ConnectorException e) {
          return null;
        }
      }
      return a.json(GoogleHttp.MAX_BODY);
    }
  }

  /** The elements of a streaming answer, read one at a time; the raw answer read at most {@code maxRaw} bytes. */
  Elements stream(GoogleHttp.Caller c, URI uri, ObjectNode body, long maxRaw) throws ConnectorException {
    Map<String, List<String>> headers = headers(c, uri);
    GoogleHttp.Answer a = http.send(c.ctx(), "POST", uri, headers, body.toString().getBytes(StandardCharsets.UTF_8),
        WHAT, false);
    try {
      if (!a.ok()) {
        throw error(a.status, a.error(), c, false);
      }
      a.body.cap(maxRaw);
      JsonParser p = GoogleHttp.JSON.createParser(a.body);
      if (p.nextToken() != JsonToken.START_ARRAY) {
        throw ConnectorException.resourceError("Firestore answered with something that isn't a list.", null);
      }
      Elements elements = new Elements(a, p, c);
      a = null;
      return elements;
    } catch (JacksonException e) {
      throw ConnectorException.resourceError("Firestore answered with something that isn't JSON.", null);
    } finally {
      if (a != null) {
        a.close();
      }
    }
  }

  /** The elements of one streaming answer. */
  final class Elements implements AutoCloseable {
    private final GoogleHttp.Answer answer;
    private final JsonParser parser;
    private final GoogleHttp.Caller caller;
    private boolean exceeded;

    Elements(GoogleHttp.Answer answer, JsonParser parser, GoogleHttp.Caller caller) {
      this.answer = answer;
      this.parser = parser;
      this.caller = caller;
    }

    /** The next element, or {@code null}: the end of the answer, or its cap ({@link #exceeded}). */
    JsonNode next() throws ConnectorException {
      if (exceeded) {
        return null;
      }
      try {
        JsonToken t = parser.nextToken();
        if (t == null || t == JsonToken.END_ARRAY) {
          return null;
        }
        if (t != JsonToken.START_OBJECT) {
          throw ConnectorException.resourceError("Firestore answered with a list of something else than results.",
              null);
        }
        JsonNode e = parser.readValueAsTree();
        if (e.has("error")) {
          throw error(e.path("error").path("code").asInt(500), e, caller, false);
        }
        return e;
      } catch (JacksonException e) {
        if (answer.body.exceeded) {
          exceeded = true;
          return null;
        }
        throw ConnectorException.unavailable("Firestore broke off its answer: " + GoogleHttp.reason(e) + ".", null);
      }
    }

    boolean exceeded() {
      return exceeded;
    }

    @Override
    public void close() {
      try {
        parser.close();
      } catch (JacksonException e) {
        // The answer is abandoned either way.
      }
      answer.close();
    }
  }

  /** What a refusal of Firestore means, and what to do about it. */
  static ConnectorException error(int status, JsonNode tree, GoogleHttp.Caller c, boolean change) {
    JsonNode body = tree == null ? null : tree.isArray() ? tree.path(0) : tree;
    JsonNode error = body == null ? null : body.path("error");
    String message = error == null ? "" : GoogleHttp.excerpt(error.path("message").asString(""));
    String code = error == null ? "" : error.path("status").asString("");
    String reason = "";
    String consumer = "";
    if (error != null) {
      for (JsonNode d : error.path("details")) {
        if (d.path("@type").asString("").endsWith("google.rpc.ErrorInfo")) {
          reason = d.path("reason").asString("");
          consumer = d.at("/metadata/consumer").asString("");
        }
      }
    }
    String said = message.isBlank() ? (code.isBlank() ? "no reason given" : code) : GoogleHttp.sentence(message);
    String identity = c.spec().identity();
    if (status == 403 && (reason.equals("SERVICE_DISABLED") || reason.equals("API_DISABLED"))) {
      String project = consumer.startsWith("projects/") ? consumer.substring("projects/".length())
          : c.cfg().string("/project");
      return ConnectorException.resourceError("The Cloud Firestore API is turned off for project " + project
          + ". Run: gcloud services enable firestore.googleapis.com --project=" + project, null);
    }
    if (change && (status >= 500 || status == 499)) {
      return ConnectorException.outcomeUnknown("Firestore failed with " + status + " during the change, so whether "
          + "it ran is not known. Check the document before retrying.", null);
    }
    return switch (status) {
      case 400 -> code.equals("FAILED_PRECONDITION")
          ? ConnectorException.resourceError("Firestore can't run this: " + said + ".", null)
          : ConnectorException.badInput("Firestore refused the request: " + said + ".");
      case 401 -> ConnectorException.unavailable("Google refused the token of " + c.cfg().name() + ": " + said
          + ". Sign in again (gcloud auth application-default login) or check the key, then test again.", null);
      case 403 -> ConnectorException.resourceError("Firestore denied " + identity + ": " + said + ". Grant "
          + "roles/datastore.viewer to read, roles/datastore.user to change documents.", null);
      case 404 -> change
          ? ConnectorException.resourceError("The document doesn't exist, so nothing changed: " + said + ".", null)
          : ConnectorException.resourceError("Firestore found nothing there: " + said + ".", null);
      case 409 -> code.equals("ALREADY_EXISTS")
          ? ConnectorException.resourceError("The document already exists, so nothing changed.", null)
          : ConnectorException.resourceError("Another change of this document came first, so nothing changed: "
              + said + ". Try again.", null);
      case 429 -> ConnectorException.resourceError("Firestore is out of quota (" + (code.isBlank() ? "429" : code)
          + "): " + said + ". Try again in a minute.", null);
      case 504 -> ConnectorException.timeout("Firestore didn't answer in time: " + said + ".");
      default -> status >= 500
          ? ConnectorException.unavailable("Firestore failed (" + status + "): " + said + ".", null)
          : ConnectorException.resourceError("Firestore answered " + status + ": " + said + ".", null);
    };
  }
}
