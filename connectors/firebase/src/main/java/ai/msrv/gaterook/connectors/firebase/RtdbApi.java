package ai.msrv.gaterook.connectors.firebase;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The Realtime Database's REST API: {@code https://<database host>/<path>.json}, read with {@code GET}, written with
 * one {@code PUT}, {@code PATCH} or {@code DELETE} and {@code print=silent}; the Google access token in
 * {@code Authorization} and nothing else of google-auth's headers. Its scopes are {@code firebase.database} and
 * {@code userinfo.email} for a key or an impersonated service account; a person's own sign-in keeps the scopes it was
 * given. A development build's test endpoint {@code gcp.firebase-rtdb} replaces the database's origin, with the test
 * credentials that go with it; a token goes only where {@code CloudCredentials.sendsGoogleTokensToRealtimeDatabase}
 * allows.
 */
final class RtdbApi {

  static final String FIREBASE_DATABASE = "https://www.googleapis.com/auth/firebase.database";
  static final String USERINFO_EMAIL = "https://www.googleapis.com/auth/userinfo.email";
  static final List<String> SCOPES = List.of(FIREBASE_DATABASE, USERINFO_EMAIL);
  static final String WHAT = "The Realtime Database";

  private final GoogleHttp http;

  RtdbApi(GoogleHttp http) {
    this.http = http;
  }

  /** The database's origin: the test endpoint of a development build, else the connection's database. */
  URI base(RtdbSettings s) {
    URI test = http.cloud().endpoints().service("gcp.firebase-rtdb");
    return test != null ? test : s.database();
  }

  /** {@code <origin>/<path>.json[?<query>]}. */
  URI uri(RtdbSettings s, RtdbPath path, String query) {
    String base = base(s).toString().replaceAll("/$", "");
    return URI.create(base + path.url() + (query == null || query.isEmpty() ? "" : "?" + query));
  }

  /** {@code Authorization} alone, refused before any token exists for a host that isn't a database's. */
  Map<String, List<String>> headers(GoogleHttp.Caller c, URI uri) throws ConnectorException {
    return http.token(c, uri, http.cloud()::sendsGoogleTokensToRealtimeDatabase, false);
  }

  /**
   * Reads one answer token by token, strings cut to {@code maxChars}, at most {@code maxOutput} bytes as the agent
   * would get them.
   */
  JsonNode get(GoogleHttp.Caller c, URI uri, long maxOutput, int maxChars, boolean personal)
      throws ConnectorException, BoundedJson.TooLarge {
    Map<String, List<String>> headers = headers(c, uri);
    try (GoogleHttp.Answer a = http.send(c.ctx(), "GET", uri, headers, null, WHAT, false)) {
      if (!a.ok()) {
        throw error(a.status, a.error(), c, false, personal);
      }
      return BoundedJson.read(a.body, GoogleHttp.MAX_BODY, maxOutput, maxChars, WHAT);
    }
  }

  /** One approved write; a lost answer leaves its outcome unknown and is never retried. */
  void write(GoogleHttp.Caller c, String method, URI uri, byte[] body, boolean personal) throws ConnectorException {
    Map<String, List<String>> headers = headers(c, uri);
    try (GoogleHttp.Answer a = http.send(c.ctx(), method, uri, headers, body, WHAT, true)) {
      if (!a.ok()) {
        throw error(a.status, a.error(), c, true, personal);
      }
    }
  }

  /** What a refusal of the database means, and what to do about it. */
  static ConnectorException error(int status, JsonNode tree, GoogleHttp.Caller c, boolean change, boolean personal) {
    String said = tree == null ? "" : GoogleHttp.sentence(GoogleHttp.excerpt(tree.path("error").asString("")));
    if (said.isBlank()) {
      said = "no reason given";
    }
    String identity = c.spec().identity();
    if (change && status >= 500) {
      return ConnectorException.outcomeUnknown(WHAT + " failed with " + status + " during the change, so whether it "
          + "ran is not known. Check the data before retrying.", null);
    }
    return switch (status) {
      case 400 -> said.contains("Index not defined")
          ? ConnectorException.badInput(WHAT + " has no index for this orderBy: " + said + ". Order by $key, or add "
              + "\".indexOn\" for that child to the database's rules.")
          : said.contains("exceeds the maximum size")
              ? ConnectorException.resourceError(WHAT + " won't send that much at once: " + said + ". Use "
                  + "shallow=true, or orderBy with limitToFirst, or a path further down.", null)
              : ConnectorException.badInput(WHAT + " refused the request: " + said + ".");
      case 401 -> ConnectorException.unavailable(WHAT + " refused the token of " + c.cfg().name() + ": " + said + ". "
          + (personal ? "Your own Google sign-in keeps the scopes it was given: impersonate a service account, or "
              + "sign in with the scopes " + FIREBASE_DATABASE + " and " + USERINFO_EMAIL + " added (gcloud auth "
              + "application-default login --scopes=openid," + USERINFO_EMAIL + ","
              + "https://www.googleapis.com/auth/cloud-platform," + FIREBASE_DATABASE + ")."
              : "Grant " + identity + " roles/firebasedatabase.viewer to read, roles/firebasedatabase.admin to "
                  + "change data, then test again."), null);
      case 403 -> ConnectorException.resourceError(WHAT + " denied " + identity + ": " + said + ". Grant "
          + "roles/firebasedatabase.viewer to read, roles/firebasedatabase.admin to change data.", null);
      case 404 -> ConnectorException.resourceError("There is no database at " + c.cfg().string("/databaseUrl")
          + ": " + said + ". Check the database URL in the Firebase console.", null);
      case 412 -> ConnectorException.resourceError(WHAT + " refused the change's condition, so nothing changed: "
          + said + ".", null);
      case 423 -> ConnectorException.unavailable(WHAT + " is locked or disabled: " + said + ".", null);
      case 429 -> ConnectorException.resourceError(WHAT + " has too many requests now: " + said + ". Try again in a "
          + "minute.", null);
      default -> status >= 500
          ? ConnectorException.unavailable(WHAT + " failed (" + status + "): " + said + ".", null)
          : ConnectorException.resourceError(WHAT + " answered " + status + ": " + said + ".", null);
    };
  }
}
