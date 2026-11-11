package ai.msrv.gaterook.connectors.cosmosdb;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a request to Cosmos DB is authorized.
 *
 * <ul>
 *   <li>Account key: Cosmos DB's master-key token, {@code type=master&ver=1.0&sig=<signature>} URL-encoded, where the
 *       signature is the Base64 HMAC-SHA256, keyed with the decoded account key, of the verb, the resource type, the
 *       resource link and the date ({@code x-ms-date}), each on its own line, verb, type and date in lower case
 *       (REST API: "Access control on Cosmos DB resources"). Each request has its own date and signature.</li>
 *   <li>Microsoft Entra ID: a service principal's token for {@code https://<account host>/.default}, from the client
 *       credentials grant of its tenant's token endpoint, sent as {@code type=aad&ver=1.0&sig=<token>}. A token is
 *       kept for the connection until five minutes before it expires; the data-plane roles of the principal are the
 *       boundary behind the gateway's checks.</li>
 * </ul>
 * The key, every signature, every token and every header made from them go to the call's taint, so an echo of one in
 * an answer is masked. None of them is ever put into a message.
 */
final class CosmosAuth {

  static final String MASTER = "master";
  static final String AAD = "aad";
  /** The fallback resource of Cosmos DB's tokens, for a tenant without the account's own (AADSTS500011). */
  static final String COSMOS_SCOPE = "https://cosmos.azure.com/.default";
  /** RFC 7231's IMF-fixdate, as {@code x-ms-date} and the signature carry it: {@code Tue, 01 Nov 1994 08:12:31 GMT}. */
  static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
      Locale.US).withZone(ZoneOffset.UTC);
  /** A token is renewed this long before it expires. */
  static final Duration RENEW = Duration.ofMinutes(5);
  static final long MAX_TOKEN_ANSWER = 64L * 1024;
  private static final Pattern TOKEN_TEXT = Pattern.compile("[A-Za-z0-9._~+/=-]{16,16384}");
  private static final Pattern AADSTS = Pattern.compile("AADSTS(\\d{4,7})");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The authorization of one request: its {@code x-ms-date} and {@code authorization} headers. */
  record Signed(String date, String authorization) {

    @Override
    public String toString() {
      return "Signed[" + date + "]";
    }
  }

  private record Token(long version, String scope, String value, Instant expires) {

    @Override
    public String toString() {
      return "Token[" + expires + "]";
    }
  }

  private final Supplier<HttpClient> http;
  /** Tests only: where the fake token endpoint listens instead of the cloud's sign-in host. */
  private final URI testLogin;
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();

  CosmosAuth(Supplier<HttpClient> http, URI testLogin) {
    this.http = http;
    this.testLogin = testLogin;
  }

  /**
   * The headers that authorize one request.
   *
   * @param verb         {@code GET}, {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}
   * @param resourceType {@code dbs}, {@code colls}, {@code docs}, {@code offers}, or empty for the account
   * @param resourceLink the IDs as they are, not encoded: {@code dbs/app/colls/orders}, empty for a feed of databases
   */
  Signed authorize(ConnectionConfig cfg, CosmosSettings s, SecretResolver secrets, ExecContext ctx, String verb,
      String resourceType, String resourceLink) throws ConnectorException {
    String date = HTTP_DATE.format(ctx.clock().instant());
    String authorization;
    if (s.auth() == CosmosSettings.Auth.KEY) {
      String signature;
      try (SecretResolver.Secret key = secrets.secret(CosmosSettings.ACCOUNT_KEY)) {
        ctx.taint(key.unsafeString());
        signature = signature(key.chars(), verb, resourceType, resourceLink, date);
      } catch (SecretUnavailableException e) {
        throw ConnectorException.unavailable("The account key of " + cfg.name() + " isn't available: "
            + sentence(e.getMessage()) + ".", null);
      }
      ctx.taint(signature);
      authorization = header(MASTER, signature);
    } else {
      String token = token(cfg, s, secrets, ctx);
      authorization = header(AAD, token);
    }
    ctx.taint(authorization);
    return new Signed(date, authorization);
  }

  /** {@code type=<type>&ver=1.0&sig=<sig>}, URL-encoded. */
  static String header(String type, String sig) {
    return URLEncoder.encode("type=" + type + "&ver=1.0&sig=" + sig, StandardCharsets.UTF_8);
  }

  /**
   * The master-key signature: Base64 of HMAC-SHA256 over {@code verb\ntype\nlink\ndate\n\n}, verb, type and date in
   * lower case, keyed with the Base64-decoded key.
   */
  static String signature(char[] key, String verb, String resourceType, String resourceLink, String date)
      throws ConnectorException {
    byte[] raw = decode(key);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(raw, "HmacSHA256"));
      String payload = verb.toLowerCase(Locale.ROOT) + "\n" + resourceType.toLowerCase(Locale.ROOT) + "\n"
          + resourceLink + "\n" + date.toLowerCase(Locale.ROOT) + "\n\n";
      return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  /** The account key's bytes: Base64 as the portal shows it, white space a paste may bring left out. */
  private static byte[] decode(char[] key) throws ConnectorException {
    byte[] b64 = new byte[key.length];
    int n = 0;
    for (char c : key) {
      if (c > 0x7f) {
        Arrays.fill(b64, (byte) 0);
        throw ConnectorException.badInput("The account key isn't one: paste a key of the account's Keys page, the "
            + "Base64 text of 88 characters.");
      }
      if (!Character.isWhitespace(c)) {
        b64[n++] = (byte) c;
      }
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(Arrays.copyOf(b64, n));
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The account key isn't one: paste a key of the account's Keys page, the "
          + "Base64 text of 88 characters.");
    } finally {
      Arrays.fill(b64, (byte) 0);
    }
    if (raw.length < 16) {
      Arrays.fill(raw, (byte) 0);
      throw ConnectorException.badInput("The account key is too short to be one: paste a key of the account's Keys "
          + "page.");
    }
    return raw;
  }

  // ── Microsoft Entra ID ────────────────────────────────────────────────────────────────────────────────────────

  /** The connection's token, from the cache while it is good for five more minutes; tainted for this call. */
  String token(ConnectionConfig cfg, CosmosSettings s, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    Object lock = locks.computeIfAbsent(cfg.id(), k -> new Object());
    synchronized (lock) {
      Token t = tokens.get(cfg.id());
      Instant now = ctx.clock().instant();
      if (t == null || t.version() != cfg.version() || !t.expires().isAfter(now.plus(RENEW))) {
        t = fetch(cfg, s, secrets, ctx, s.endpoint().scope(), true);
        tokens.put(cfg.id(), t);
      }
      ctx.taint(t.value());
      return t.value();
    }
  }

  private Token fetch(ConnectionConfig cfg, CosmosSettings s, SecretResolver secrets, ExecContext ctx, String scope,
      boolean fallback) throws ConnectorException {
    URI uri = tokenUri(s);
    String form;
    try (SecretResolver.Secret secret = secrets.secret(CosmosSettings.CLIENT_SECRET)) {
      ctx.taint(secret.unsafeString());
      form = "grant_type=client_credentials&client_id=" + encode(s.clientId()) + "&scope=" + encode(scope)
          + "&client_secret=" + encode(secret.unsafeString());
    } catch (SecretUnavailableException e) {
      throw ConnectorException.unavailable("The client secret of " + cfg.name() + " isn't available: "
          + sentence(e.getMessage()) + ".", null);
    }
    Duration left = ctx.remaining().minus(CosmosHttp.MARGIN);
    if (left.isNegative() || left.isZero()) {
      throw ConnectorException.timeout("No time was left to sign in to Microsoft Entra ID.");
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(left.compareTo(CosmosHttp.MAX_REQUEST) > 0 ? CosmosHttp.MAX_REQUEST : left)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .header("User-Agent", CosmosHttp.USER_AGENT)
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build();
    String host = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    int status;
    byte[] body;
    try {
      HttpResponse<InputStream> response = http.get().send(request, HttpResponse.BodyHandlers.ofInputStream());
      status = response.statusCode();
      try (InputStream in = response.body()) {
        body = CosmosHttp.readAtMost(in, MAX_TOKEN_ANSWER);
      }
    } catch (HttpConnectTimeoutException e) {
      throw ConnectorException.unavailable("Can't connect to Microsoft Entra ID (" + host + ", timed out). Check "
          + "that this computer can reach it.", e);
    } catch (HttpTimeoutException e) {
      throw ConnectorException.timeout("Microsoft Entra ID (" + host + ") didn't answer in time.");
    } catch (ConnectException | SSLException e) {
      throw ConnectorException.unavailable("Can't connect to Microsoft Entra ID (" + host + "): "
          + CosmosHttp.reason(e) + ".", e);
    } catch (IOException e) {
      throw ConnectorException.unavailable("Signing in to Microsoft Entra ID failed: " + CosmosHttp.reason(e) + ".",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("Signing in to Microsoft Entra ID was cancelled.");
    }
    JsonNode answer;
    try {
      answer = body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
    } catch (JacksonException e) {
      throw ConnectorException.unavailable("Microsoft Entra ID answered " + status + " with something that isn't "
          + "JSON.", null);
    }
    if (status / 100 != 2) {
      String description = answer.path("error_description").asString("");
      Matcher code = AADSTS.matcher(description);
      String aadsts = code.find() ? code.group(1) : "";
      if (aadsts.equals("500011") && fallback && s.endpoint().cloud() == CosmosEndpoint.Cloud.PUBLIC) {
        return fetch(cfg, s, secrets, ctx, COSMOS_SCOPE, false);
      }
      throw ConnectorException.unavailable(signInRefused(cfg, s, answer.path("error").asString(""), aadsts,
          description), null);
    }
    String token = answer.path("access_token").asString("");
    if (!TOKEN_TEXT.matcher(token).matches()) {
      throw ConnectorException.unavailable("Microsoft Entra ID answered without an access token.", null);
    }
    ctx.taint(token);
    long seconds = Math.max(60, Math.min(24 * 3600, answer.path("expires_in").asLong(3600)));
    return new Token(cfg.version(), scope, token, ctx.clock().instant().plusSeconds(seconds));
  }

  /** What a refused sign-in means and what to do, without the secret. */
  static String signInRefused(ConnectionConfig cfg, CosmosSettings s, String error, String aadsts,
      String description) {
    String said = firstSentence(OutputSanitizer.sanitize(description));
    String what = switch (aadsts) {
      case "7000215" -> "The client secret is wrong: paste the secret's Value (not its Secret ID), or make a new one.";
      case "7000222" -> "The client secret has expired: make a new one for the application and paste it.";
      case "700016" -> "No application with this client ID is in the tenant: check the application (client) ID and "
          + "the tenant.";
      case "90002", "900023" -> "The tenant wasn't found: check the directory (tenant) ID.";
      case "500011" -> "The tenant has no resource for " + s.endpoint().host() + ": check the endpoint and that the "
          + "account belongs to this tenant.";
      default -> said.isEmpty() ? "Microsoft Entra ID refused the sign-in (" + (error.isEmpty() ? "no reason given"
          : OutputSanitizer.sanitize(error)) + ")." : said;
    };
    return "Microsoft Entra ID refused the service principal of " + cfg.name() + (aadsts.isEmpty() ? ""
        : " (AADSTS" + aadsts + ")") + ". " + what;
  }

  private URI tokenUri(CosmosSettings s) {
    String path = "/" + CosmosIds.encode(s.tenant()) + "/oauth2/v2.0/token";
    if (testLogin != null) {
      return testLogin.resolve(path);
    }
    return URI.create("https://" + s.endpoint().cloud().login + path);
  }

  /** The sign-in host of the connection, for Test connection and the manifest. */
  String loginHost(CosmosSettings s) {
    return testLogin != null ? testLogin.getHost() : s.endpoint().cloud().login;
  }

  void evict(String connectionId) {
    tokens.remove(connectionId);
    locks.remove(connectionId);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static String sentence(String text) {
    String t = text == null ? "" : text.strip();
    while (t.endsWith(".")) {
      t = t.substring(0, t.length() - 1);
    }
    return t;
  }

  /** The first sentence or line of {@code text}, at most 300 characters. */
  static String firstSentence(String text) {
    if (text == null) {
      return "";
    }
    String t = text.strip();
    int nl = t.indexOf('\n');
    if (nl >= 0) {
      t = t.substring(0, nl).strip();
    }
    int cr = t.indexOf("\\r");
    if (cr >= 0) {
      t = t.substring(0, cr).strip();
    }
    if (t.length() > 300) {
      t = t.substring(0, 299) + "…";
    }
    return t;
  }
}
