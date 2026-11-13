package com.gaterook.connectors.firebase;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connectors.cloudauth.GcpSpec;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.UserCredentials;
import java.net.URI;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Who a connection is to Google, as Test connection shows it: the impersonated service account, the service account of
 * a key or of a sign-in file, or for a person's own Google sign-in the email of Google's token information
 * ({@code POST oauth2.googleapis.com/tokeninfo}, the token in its {@code Authorization} header) when it answers, else
 * what the credentials say of themselves ({@link GcpSpec#identity}).
 */
final class GoogleIdentity {

  static final URI TOKEN_INFO = URI.create("https://oauth2.googleapis.com/tokeninfo");
  private static final long MAX_INFO = 16 * 1024;

  private GoogleIdentity() {}

  /** Whether the credentials are a person's own sign-in: their permissions are that person's, without expiry. */
  static boolean personal(GoogleCredentials credentials) {
    return credentials instanceof UserCredentials;
  }

  /**
   * @param authorization the {@code Authorization} headers of a request the credentials just made: the token info
   *                      is asked with them, for a person's sign-in only
   */
  static String of(GoogleHttp http, GoogleHttp.Caller c, GoogleCredentials credentials,
      Map<String, List<String>> authorization) {
    GcpSpec spec = c.spec();
    if (spec.impersonate() != null) {
      return spec.impersonate();
    }
    if (credentials instanceof ServiceAccountCredentials key && key.getClientEmail() != null) {
      return key.getClientEmail();
    }
    if (credentials instanceof ImpersonatedCredentials impersonated && impersonated.getAccount() != null) {
      return impersonated.getAccount();
    }
    if (personal(credentials)) {
      String email = email(http, c, authorization);
      if (email != null) {
        return email;
      }
    }
    return spec.identity();
  }

  /** The email of the token, or {@code null}: no answer, no email scope, or a test without a fake of it. */
  private static String email(GoogleHttp http, GoogleHttp.Caller c, Map<String, List<String>> authorization) {
    URI base = http.cloud().endpoints().service("gcp.oauth2");
    URI uri = base == null ? TOKEN_INFO : URI.create(base.toString().replaceAll("/$", "") + "/tokeninfo");
    if (!http.cloud().sendsGoogleTokensTo(uri) || authorization.isEmpty()) {
      return null;
    }
    try (GoogleHttp.Answer a = http.send(c.ctx(), "POST", uri, authorization, null, "Google's token information",
        false)) {
      if (!a.ok()) {
        return null;
      }
      JsonNode info = a.json(MAX_INFO);
      String email = info.path("email").asString(null);
      return email == null || email.isBlank() || email.length() > 320 ? null : GoogleHttp.excerpt(email);
    } catch (ConnectorException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The hint of credentials that last on prod: a person's own sign-in (all of that person's permissions, no expiry)
   * or a stored key, where an impersonated service account would lend a token for minutes.
   *
   * @param role the predefined role to give the service account instead
   */
  static TestResult.Hint longLived(String code, GcpSpec spec, boolean personal, String role) {
    return new TestResult.Hint(TestResult.Severity.WARNING, code, "/credentials/impersonate", personal
        ? "On prod, impersonate a service account that has " + role + ": your own Google sign-in carries all of your "
            + "permissions and doesn't expire."
        : "On prod, impersonate a service account that has " + role + ": a stored key doesn't expire, a borrowed "
            + "token lasts " + spec.lifetimeSeconds() / 60 + " min.");
  }
}
