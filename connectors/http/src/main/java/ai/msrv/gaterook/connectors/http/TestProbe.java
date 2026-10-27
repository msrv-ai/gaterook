package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressDenial;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection (report 10 §5): the same guarded client as calls, nothing saved. First the request without
 * credentials — the address check, TCP, TLS (an unknown certificate stops here with "Trust this certificate", before
 * any credential leaves), and what the API answers to nobody; then the same request with the credentials. The hints
 * say what the answers mean: credentials required and accepted, an API that answers anyone, a token with write scopes
 * or near its expiry, a certificate that expires soon, a secret header that doesn't resolve.
 */
final class TestProbe {

  private static final long MAX_BYTES = 64 * 1_024;

  private TestProbe() {}

  static TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    HttpSettings s;
    PlannedRequest probe;
    GuardedEgress guard;
    try {
      s = HttpSettings.of(cfg);
      probe = probe(s);
      guard = HttpExecutor.guard(ctx, s);
    } catch (ConnectorException e) {
      return TestResult.failure("http.config", e.getMessage(), null);
    }
    String host = probe.url().host();
    List<TestResult.Check> checks = new ArrayList<>();
    CapturingTrust trust = new CapturingTrust(s.tls());

    HttpExecutor.Exchange anonymous;
    try {
      anonymous = HttpExecutor.exchange(guard, probe, s, secrets, ctx, false, MAX_BYTES, trust);
    } catch (IOException e) {
      return failed(e, probe, cfg, trust, checks);
    } catch (ConnectorException e) {
      return TestResult.failure("http.unreachable", e.getMessage(), null);
    }
    checks.add(new TestResult.Check("dns", true, 0, String.join(", ", HttpExecutor.addressTexts(
        anonymous.addresses()))));
    if (anonymous.handshake() != null) {
      checks.add(new TestResult.Check("tls", true, anonymous.connectMs(), tlsLine(anonymous)));
    }

    HttpExecutor.Exchange signed = anonymous;
    boolean signs = s.auth().type() != HttpSettings.AuthType.NONE
        || s.secretHeaders().stream().anyMatch(h -> h.appliesTo(probe.method()));
    if (signs) {
      try {
        signed = HttpExecutor.exchange(guard, probe, s, secrets, ctx, true, MAX_BYTES, null);
      } catch (SecretUnavailableException e) {
        return TestResult.failure("http.secret", e.getMessage(), null).withChecks(checks);
      } catch (ConnectorException e) {
        return TestResult.failure("http.secret", e.getMessage(), null).withChecks(checks);
      } catch (IOException e) {
        return TestResult.failure("http.unreachable", HttpExecutor.translate(e, probe, ctx).getMessage(), null)
            .withChecks(checks);
      }
    }
    int anon = anonymous.answer().status();
    int status = signed.answer().status();
    checks.add(new TestResult.Check("request", status < 500, signed.answer().elapsedMs(), probe.method() + " "
        + probe.url().encodedPath() + " → " + status + " " + signed.answer().reason()));

    if (signs && (status == 401 || status == 403)) {
      checks.add(new TestResult.Check("credentials", false, 0, status + " with the credentials"));
      return TestResult.failure("http.auth-rejected", "The " + credentialWord(s) + " was rejected (" + status + "). "
          + "Replace it under Credentials.", null).withChecks(checks);
    }
    if (!signs && (anon == 401 || anon == 403)) {
      return TestResult.failure("http.auth-required", host + " asks for credentials (" + anon + "). Set them under "
          + "Credentials.", null).withChecks(checks);
    }

    String server = serverVersion(signed);
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN, null, null, List.of(),
        null);
    List<TestResult.Hint> hints = new ArrayList<>();
    if (signs && (anon == 401 || anon == 403)) {
      checks.add(new TestResult.Check("credentials", true, 0, "required and accepted"));
      hints.add(new TestResult.Hint(TestResult.Severity.INFO, "http.auth-required", "/auth",
          "Credentials are required and accepted."));
    } else if (signs && anon >= 200 && anon < 300) {
      hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "http.no-auth-needed", "/auth",
          "This API answers without credentials."));
    }
    if (s.auth().type() == HttpSettings.AuthType.BEARER) {
      permissions = bearerHints(secrets, cfg, ctx.clock().instant(), hints, permissions);
    }
    String scopes = signed.answer().headers().entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase("X-OAuth-Scopes")).flatMap(e -> e.getValue().stream()).findFirst()
        .orElse(null);
    if (scopes != null) {
      List<String> list = List.of(OutputSanitizer.sanitize(scopes).split("\\s*,\\s*"));
      permissions = scopePermissions(list, cfg, hints, "/auth/token");
    }
    unresolvedSecretHeaders(s, secrets, probe.method(), hints);
    if (anonymous.handshake() != null) {
      certificateHints(anonymous, host, ctx, hints);
    }

    TestResult result = TestResult.success(signed.answer().elapsedMs(), server, null, permissions)
        .withChecks(checks).withDetails(details(anonymous, signed));
    for (TestResult.Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /** The test request: {@code advanced.test} under the first base URL, the connection's default headers. */
  static PlannedRequest probe(HttpSettings s) throws ConnectorException {
    HttpUrl base = s.baseUrls().getFirst();
    HttpUrl url;
    try {
      url = UrlRules.resolve(base, s.testPath().startsWith("/") ? s.testPath() : "/" + s.testPath());
    } catch (UrlRules.UrlRejectedException e) {
      throw ConnectorException.badInput("Advanced › Test path: " + e.getMessage() + ".");
    }
    List<PlannedRequest.Header> headers = new ArrayList<>();
    s.defaultHeaders().forEach((n, v) -> headers.add(new PlannedRequest.Header(n, v)));
    return new PlannedRequest(s.testMethod(), url, base, headers, null, null, null);
  }

  /** Health: the test request with the credentials; an answer is healthy, 5xx or a refused token degraded. */
  static HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      HttpSettings s = HttpSettings.of(cfg);
      PlannedRequest probe = probe(s);
      GuardedEgress guard = HttpExecutor.guard(ctx, s);
      HttpExecutor.Exchange e;
      try {
        e = HttpExecutor.exchange(guard, probe, s, secrets, ctx, true, MAX_BYTES, null);
      } catch (IOException io) {
        return HealthResult.unreachable(HttpExecutor.translate(io, probe, ctx).getMessage());
      }
      int status = e.answer().status();
      long ms = e.answer().elapsedMs();
      if (status >= 500) {
        return new HealthResult(HealthResult.State.DEGRADED, ms, "Answers " + status + " " + e.answer().reason());
      }
      if (status == 401 || status == 403) {
        return new HealthResult(HealthResult.State.DEGRADED, ms, "The " + credentialWord(s) + " was rejected ("
            + status + ")");
      }
      return HealthResult.healthy(ms);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  private static TestResult failed(IOException e, PlannedRequest probe, ConnectionConfig cfg, CapturingTrust trust,
      List<TestResult.Check> checks) {
    IOException cause = e.getCause() instanceof IOException io ? io : e;
    String host = probe.url().host();
    String target = host + ":" + probe.url().port();
    EgressDenial denial = EgressDenial.find(cause);
    if (denial != null) {
      AddressPolicy.Verdict v = denial.verdict();
      checks.add(new TestResult.Check("dns", false, 0, v.text()));
      if (v.suggestedRange().isPresent()) {
        String cidr = v.suggestedRange().get().toString();
        ObjectNode change = JsonNodeFactory.instance.objectNode();
        change.put("kind", "allow-private");
        change.put("cidr", cidr);
        change.put("connection", cfg.name());
        return TestResult.failure("http.private-address", host + " resolves to " + v.address().getHostAddress()
            + ", a private address. Allow " + cidr + " for " + cfg.name() + " on " + cfg.env().id() + ".", null)
            .withChecks(checks).withSuggestedPolicyChange(change);
      }
      return TestResult.failure("http.address-blocked", v.text(), null).withChecks(checks);
    }
    if (cause instanceof SSLHandshakeException && trust.refused() && !trust.chain().isEmpty()) {
      X509Certificate leaf = trust.chain().getFirst();
      return TestResult.confirm(certificateConfirmation(leaf, host, cfg), checks);
    }
    if (cause instanceof SSLPeerUnverifiedException) {
      return TestResult.failure("http.tls-name", "The certificate of " + host + " doesn't name " + host + " (or "
          + "breaks a pin under TLS). Check the base URL or the pins.", cause.getMessage()).withChecks(checks);
    }
    if (cause instanceof UnknownHostException) {
      return TestResult.failure("http.dns", "Can't resolve " + host + ". Check the host name and the VPN, then test "
          + "again.", null).withChecks(checks);
    }
    if (cause instanceof ConnectException) {
      return TestResult.failure("http.refused", "Can't reach " + target + ": connection refused. Check the VPN or "
          + "the host, then test again.", null).withChecks(checks);
    }
    if (cause instanceof SocketTimeoutException || "timeout".equals(cause.getMessage())) {
      return TestResult.failure("http.timeout", target + " didn't answer in time. Check the VPN or the host, then "
          + "test again.", null).withChecks(checks);
    }
    if (cause instanceof SSLHandshakeException) {
      return TestResult.failure("http.tls", "TLS with " + target + " failed: " + cause.getMessage() + ".", null)
          .withChecks(checks);
    }
    return TestResult.failure("http.unreachable", "Can't reach " + target + ": " + cause.getMessage() + ".", null)
        .withChecks(checks);
  }

  /**
   * "Trust this certificate": the server's own certificate goes into {@code tls.caCertificates} (next to what is
   * there), the core writes a Setup change and tests again. Trust on first use: a new certificate asks again.
   */
  private static TestResult.Confirmation certificateConfirmation(X509Certificate leaf, String host,
      ConnectionConfig cfg) {
    String pem = Pem.encode(List.of(leaf));
    String existing = cfg.config().path("tls").path("caCertificates").asString("");
    ObjectNode patch = JsonNodeFactory.instance.objectNode();
    patch.putObject("tls").put("caCertificates", existing.isBlank() ? pem : existing.strip() + "\n" + pem);
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("host", host);
    data.put("subject", leaf.getSubjectX500Principal().getName());
    data.put("issuer", leaf.getIssuerX500Principal().getName());
    data.put("notAfter", leaf.getNotAfter().toInstant().toString());
    data.put("fingerprint", Pem.fingerprint(leaf));
    data.put("pin", Pem.sha256Pin(leaf));
    String text = "Trust the certificate of " + host + "? " + leaf.getSubjectX500Principal().getName()
        + ", issued by " + leaf.getIssuerX500Principal().getName() + ", valid until "
        + leaf.getNotAfter().toInstant().toString().substring(0, 10) + ". SHA-256 " + Pem.fingerprint(leaf) + ".";
    return new TestResult.Confirmation("tls-cert", text, data, patch);
  }

  private static String tlsLine(HttpExecutor.Exchange e) {
    StringBuilder out = new StringBuilder(e.handshake().tlsVersion().javaName()).append(' ')
        .append(e.handshake().cipherSuite().javaName());
    List<Certificate> peers = e.handshake().peerCertificates();
    if (!peers.isEmpty() && peers.getFirst() instanceof X509Certificate leaf) {
      out.append(" · ").append(leaf.getSubjectX500Principal().getName()).append(" · ").append(Pem.sha256Pin(leaf));
    }
    return out.toString();
  }

  private static void certificateHints(HttpExecutor.Exchange e, String host, ExecContext ctx,
      List<TestResult.Hint> hints) {
    List<Certificate> peers = e.handshake().peerCertificates();
    if (peers.isEmpty() || !(peers.getFirst() instanceof X509Certificate leaf)) {
      return;
    }
    long days = Duration.between(ctx.clock().instant(), leaf.getNotAfter().toInstant()).toDays();
    if (days < 30) {
      hints.add(new TestResult.Hint(days < 7 ? TestResult.Severity.DANGER : TestResult.Severity.WARNING,
          "http.cert-expiring", "/tls", "The certificate of " + host + " expires in " + Math.max(0, days)
          + (days == 1 ? " day." : " days.")));
    }
  }

  /**
   * A bearer token that is a JWT: its payload is read (the signature is not the gateway's to check, and the token is
   * never shown) for scopes and expiry. Estimated, as the hint says.
   */
  private static TestResult.Permissions bearerHints(SecretResolver secrets, ConnectionConfig cfg, Instant now,
      List<TestResult.Hint> hints, TestResult.Permissions dflt) {
    JsonNode claims;
    try (SecretResolver.Secret token = secrets.secret(HttpSettings.TOKEN)) {
      claims = jwtClaims(token.chars());
    } catch (ConnectorException e) {
      return dflt;
    }
    if (claims == null) {
      return dflt;
    }
    TestResult.Permissions permissions = dflt;
    List<String> scopes = new ArrayList<>();
    for (String key : new String[] {"scope", "scp", "roles", "permissions"}) {
      JsonNode v = claims.get(key);
      if (v == null) {
        continue;
      }
      if (v.isArray()) {
        v.forEach(n -> scopes.add(OutputSanitizer.sanitize(n.asString(""))));
      } else if (v.isString()) {
        for (String part : v.asString().split("\\s+")) {
          if (!part.isBlank()) {
            scopes.add(OutputSanitizer.sanitize(part));
          }
        }
      }
    }
    if (!scopes.isEmpty()) {
      permissions = scopePermissions(scopes, cfg, hints, HttpSettings.TOKEN);
    }
    if (claims.path("exp").isNumber()) {
      Instant exp = Instant.ofEpochSecond(claims.path("exp").asLong());
      long days = Duration.between(now, exp).toDays();
      if (exp.isBefore(now)) {
        hints.add(new TestResult.Hint(TestResult.Severity.ERROR, "http.token-expired", HttpSettings.TOKEN,
            "The token expired on " + exp.toString().substring(0, 10) + ". Replace it under Credentials."));
      } else if (days < 7) {
        hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "http.token-expiring", HttpSettings.TOKEN,
            "The token expires in " + days + (days == 1 ? " day." : " days.")));
      }
    }
    return permissions;
  }

  private static TestResult.Permissions scopePermissions(List<String> scopes, ConnectionConfig cfg,
      List<TestResult.Hint> hints, String field) {
    List<String> writes = scopes.stream().filter(TestProbe::writeScope).toList();
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    ArrayNode list = details.putArray("scopes");
    scopes.forEach(list::add);
    details.put("estimated", true);
    if (!writes.isEmpty()) {
      hints.add(new TestResult.Hint(cfg.env() == ai.msrv.gaterook.connector.api.Env.PROD ? TestResult.Severity.DANGER
          : TestResult.Severity.WARNING, "http.write-scopes", field, "Token has write scopes: "
          + String.join(", ", writes) + ". Use a read-only token" + (cfg.env() == ai.msrv.gaterook.connector.api.Env.PROD
          ? " on prod." : " for prod.")));
      return new TestResult.Permissions(TestResult.Level.WRITE, "estimated: " + String.join(", ", scopes),
          "estimated: " + String.join(", ", writes), List.of(), details);
    }
    return new TestResult.Permissions(TestResult.Level.READ, "estimated: " + String.join(", ", scopes), null,
        List.of(), details);
  }

  static boolean writeScope(String scope) {
    String s = scope.toLowerCase(Locale.ROOT);
    return s.contains("write") || s.contains("admin") || s.endsWith(":manage") || s.equals("repo")
        || s.contains("delete") || s.endsWith(".all") && !s.contains("read");
  }

  /** The payload of a JWT, or {@code null} when the token isn't one. */
  static JsonNode jwtClaims(char[] token) {
    int first = -1;
    int second = -1;
    for (int i = 0; i < token.length; i++) {
      if (token[i] == '.') {
        if (first < 0) {
          first = i;
        } else if (second < 0) {
          second = i;
        } else {
          return null;
        }
      }
    }
    if (first <= 0 || second <= first + 1) {
      return null;
    }
    try {
      byte[] payload = Base64.getUrlDecoder().decode(new String(token, first + 1, second - first - 1)
          .getBytes(StandardCharsets.US_ASCII));
      JsonNode tree = JsonText.JSON.readTree(payload);
      return tree.isObject() ? tree : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Secret headers the test didn't send (their methods exclude the test's): each is resolved once to see that it can
   * be, so "Webhook secret unresolved" shows before the first POST fails.
   */
  private static void unresolvedSecretHeaders(HttpSettings s, SecretResolver secrets, String testMethod,
      List<TestResult.Hint> hints) {
    for (HttpSettings.SecretHeader h : s.secretHeaders()) {
      if (h.appliesTo(testMethod)) {
        continue;
      }
      if (!secrets.isSet(h.field())) {
        hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "http.secret-header-unset", h.field(),
            h.name() + " has no value: calls that need it fail until you set it."));
        continue;
      }
      try (SecretResolver.Secret ignored = secrets.secret(h.field())) {
        // resolved: nothing to say
      } catch (ConnectorException e) {
        hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "http.secret-header-unresolved", h.field(),
            h.name() + " is unresolved: " + e.getMessage() + " Calls that need it fail until it resolves."));
      }
    }
  }

  private static String serverVersion(HttpExecutor.Exchange e) {
    for (String name : new String[] {"Server", "X-Powered-By"}) {
      for (var h : e.answer().headers().entrySet()) {
        if (h.getKey().equalsIgnoreCase(name) && !h.getValue().isEmpty()) {
          String v = OutputSanitizer.sanitize(h.getValue().getFirst());
          return v.length() > 64 ? v.substring(0, 64) : v;
        }
      }
    }
    return null;
  }

  private static JsonNode details(HttpExecutor.Exchange anonymous, HttpExecutor.Exchange signed) {
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    ArrayNode addresses = d.putArray("addresses");
    HttpExecutor.addressTexts(anonymous.addresses()).forEach(addresses::add);
    d.put("status", signed.answer().status());
    d.put("statusWithoutCredentials", anonymous.answer().status());
    if (anonymous.handshake() != null) {
      ObjectNode tls = d.putObject("tls");
      tls.put("version", anonymous.handshake().tlsVersion().javaName());
      tls.put("cipher", anonymous.handshake().cipherSuite().javaName());
      List<Certificate> peers = anonymous.handshake().peerCertificates();
      if (!peers.isEmpty() && peers.getFirst() instanceof X509Certificate leaf) {
        tls.put("subject", leaf.getSubjectX500Principal().getName());
        tls.put("issuer", leaf.getIssuerX500Principal().getName());
        tls.put("notAfter", leaf.getNotAfter().toInstant().toString());
        tls.put("pin", Pem.sha256Pin(leaf));
      }
    }
    return d;
  }

  private static String credentialWord(HttpSettings s) {
    return switch (s.auth().type()) {
      case BEARER -> "token";
      case API_KEY_HEADER, API_KEY_QUERY -> "API key";
      case BASIC -> "password";
      case NONE -> "secret header";
    };
  }
}
