package ai.msrv.gaterook.e2e.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A remote MCP server added by its URL and signed in to in the browser, as in Claude Desktop, Cursor or Codex, with
 * GateRook — not the AI app — holding the tokens (ARCHITECTURE.md §7.5): the review finds the OAuth sign-in, GateRook
 * registers itself and listens on this computer for the redirect, the code comes back to it, the server is added with
 * the sign-in, a token refused early is renewed (rotating the refresh token) and the call goes once more, a revoked
 * sign-in asks for a new one, signing in again reuses the registration, a pasted address finishes a sign-in whose
 * redirect didn't arrive, sign-out revokes, and no token reaches an answer, the Secrets list or a log. Where the
 * authorization server takes Client ID Metadata Documents, GateRook registers nothing: its client ID is its published
 * document's URL, on any free port, for the sign-in, its renewals and the revocation. A URL that carries the server's
 * key is warned about in the review and still connects, and neither the review, the list nor the log shows the key.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerSignInIT {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  /** The person's browser: it follows nothing by itself, the test reads each redirect. */
  private static final HttpClient BROWSER = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private static CoreProcess core;
  /** Dynamic registration only. */
  private static OAuthMcpServer tracker;
  /** Client ID Metadata Documents beside dynamic registration, as Linear's, Notion's or Sentry's today. */
  private static OAuthMcpServer board;

  @BeforeAll
  static void start() throws Exception {
    Path work = Files.createDirectories(Path.of("target", "mcp-sign-in-e2e").toAbsolutePath());
    tracker = new OAuthMcpServer(Files.createTempDirectory(work, "tomcat-"));
    board = new OAuthMcpServer(Files.createTempDirectory(work, "tomcat-"), true);
    core = CoreProcess.start(CoreProcess.newHome("mcp-sign-in-e2e"), CoreProcess.freePort(), List.of()).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
    // The fake runs on this computer: dev may reach 127.0.0.1, as a person allows for a server under development.
    JsonNode policy = json(admin("GET", "/api/policies/dev", null), 200);
    ObjectNode update = CoreProcess.JSON.createObjectNode().put("version", policy.path("version").asLong());
    update.putObject("sections").putObject("http").putArray("allowedPrivateRanges").addObject().put("cidr",
        "127.0.0.1/32").putArray("connections");
    json(admin("PUT", "/api/policies/dev", update.toString()), 200);
  }

  @AfterAll
  static void stop() throws Exception {
    if (core != null) {
      core.close();
    }
    if (tracker != null) {
      tracker.close();
    }
    if (board != null) {
      board.close();
    }
  }

  private static ObjectNode config(boolean oauth) {
    return config("tracker-mcp", tracker, oauth);
  }

  private static ObjectNode config(String name, OAuthMcpServer server, boolean oauth) {
    ObjectNode c = CoreProcess.JSON.createObjectNode().put("name", name).put("transport", "http")
        .put("url", server.base + "/mcp");
    if (oauth) {
      c.putObject("auth").put("type", "oauth");
    }
    return c;
  }

  @Test
  @Order(1)
  void theReviewFindsTheOAuthSignIn() throws Exception {
    ObjectNode review = CoreProcess.JSON.createObjectNode();
    review.set("config", config(false));
    JsonNode r = json(admin("POST", "/api/mcp-servers/review", review.toString()), 200);
    assertThat(r.path("transport").asString()).isEqualTo("http");
    assertThat(r.at("/auth/kind").asString()).as(r.toString()).isEqualTo("oauth");
    assertThat(r.at("/auth/issuer").asString()).isEqualTo(tracker.base);
    assertThat(r.at("/auth/registration").asString()).isEqualTo("dynamic");
    assertThat(r.at("/auth/scope").asString()).isEqualTo("issues:read offline_access");
    assertThat(tracker.registrations).as("a review registers nothing").isEmpty();
  }

  @Test
  @Order(2)
  void theBrowserSignInComesBackToGateRookAndTheServerIsAddedWithIt() throws Exception {
    ObjectNode request = CoreProcess.JSON.createObjectNode();
    request.set("config", config(true));
    JsonNode started = json(admin("POST", "/api/mcp-sign-ins", request.toString()), 201);
    String id = started.path("id").asString();
    assertThat(started.path("state").asString()).isEqualTo("waiting");
    assertThat(started.path("redirectUri").asString()).matches("http://localhost:\\d+/callback");
    JsonNode registration = tracker.registrations.getFirst();
    assertThat(registration.path("client_name").asString()).isEqualTo("GateRook");
    assertThat(registration.path("application_type").asString()).isEqualTo("native");

    // Electron asks for the page by the sign-in's id; it is the one the window shows for copying.
    JsonNode page = json(admin("GET", "/internal/mcp-sign-ins/" + id + "/authorization", null), 200);
    assertThat(page.path("url").asString()).isEqualTo(started.path("authorizationUrl").asString())
        .startsWith(tracker.base + "/authorize?");

    HttpResponse<String> landed = signInInTheBrowser(page.path("url").asString());
    assertThat(landed.statusCode()).as(landed.body()).isEqualTo(200);
    assertThat(landed.body()).contains("Signed in to tracker-mcp");
    assertThat(landed.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
    assertThat(json(admin("GET", "/api/mcp-sign-ins/" + id, null), 200).path("state").asString())
        .isEqualTo("signed-in");
    Map<String, String> exchange = tracker.tokenRequests.getFirst();
    assertThat(exchange).containsEntry("grant_type", "authorization_code")
        .containsEntry("resource", tracker.base + "/mcp").containsKey("code_verifier");

    ObjectNode add = request.deepCopy().put("signIn", id).put("acknowledge", true).put("reason", "issue tracker");
    JsonNode added = json(admin("POST", "/api/mcp-servers", add.toString()), 201);
    assertThat(added.path("tools").asInt()).isEqualTo(1);
    assertThat(admin("GET", "/api/mcp-sign-ins/" + id, null).status()).as("a sign-in is used once").isEqualTo(404);

    JsonNode status = json(admin("GET", "/api/mcp-servers/tracker-mcp/sign-in", null), 200);
    assertThat(status.path("state").asString()).isEqualTo("signed-in");
    assertThat(status.path("issuer").asString()).isEqualTo(tracker.base);
    assertThat(status.path("renewable").asBoolean()).isTrue();
    assertThat(status.path("registered").asBoolean()).isTrue();
    assertThat(json(admin("GET", "/api/mcp-servers/tracker-mcp/tools", null), 200)).extracting(t -> t.path("name")
        .asString()).containsExactly("tracker_list_issues");
  }

  @Test
  @Order(3)
  void aTokenRefusedEarlyIsRenewedWithARotatedRefreshTokenAndTheRequestGoesAgain() throws Exception {
    assertThat(json(admin("POST", "/api/mcp-servers/tracker-mcp/test", null), 200).path("tools").asInt())
        .isEqualTo(1);
    assertThat(tracker.refresh).hasSize(1);
    String firstRefresh = tracker.refresh.iterator().next();

    tracker.expireAccessTokens();
    tracker.bearersSeen.clear();
    Answer restarted = admin("POST", "/api/mcp-servers/tracker-mcp/restart", null);
    assertThat(restarted.status()).as(restarted.body() + "\n" + trace()).isEqualTo(200);

    Map<String, String> renewal = tracker.tokenRequests.getLast();
    assertThat(renewal).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", firstRefresh)
        .containsEntry("resource", tracker.base + "/mcp");
    assertThat(json(admin("GET", "/api/mcp-servers/tracker-mcp/sign-in", null), 200).path("state").asString())
        .isEqualTo("signed-in");

    // The renewed refresh token was stored: the next renewal uses it, not the consumed one.
    tracker.expireAccessTokens();
    json(admin("POST", "/api/mcp-servers/tracker-mcp/restart", null), 200);
    assertThat(tracker.tokenRequests.getLast().get("refresh_token")).isNotEqualTo(firstRefresh);
    assertThat(tracker.usedRefresh).hasSize(2);
  }

  @Test
  @Order(4)
  void aRevokedSignInAsksForANewOneAndSigningInAgainReusesTheRegistration() throws Exception {
    tracker.revokeEverything();
    Answer failed = admin("POST", "/api/mcp-servers/tracker-mcp/restart", null);
    assertThat(failed.status()).isEqualTo(422);
    assertThat(failed.body()).contains("needs you to sign in again").contains("invalid_grant");
    JsonNode status = json(admin("GET", "/api/mcp-servers/tracker-mcp/sign-in", null), 200);
    assertThat(status.path("state").asString()).isEqualTo("needs-sign-in");

    int registrations = tracker.registrations.size();
    JsonNode again = json(admin("POST", "/api/mcp-servers/tracker-mcp/sign-in", null), 201);
    HttpResponse<String> landed = signInInTheBrowser(again.path("authorizationUrl").asString());
    assertThat(landed.statusCode()).as(landed.body()).isEqualTo(200);
    assertThat(tracker.registrations).as("the client GateRook registered is used again").hasSize(registrations);
    assertThat(json(admin("GET", "/api/mcp-servers/tracker-mcp/sign-in", null), 200).path("state").asString())
        .isEqualTo("signed-in");
    assertThat(json(admin("POST", "/api/mcp-servers/tracker-mcp/test", null), 200).path("tools").asInt())
        .isEqualTo(1);
  }

  @Test
  @Order(5)
  void aPastedAddressFinishesASignInWhoseRedirectDidntArrive() throws Exception {
    JsonNode started = json(admin("POST", "/api/mcp-servers/tracker-mcp/sign-in", null), 201);
    String id = started.path("id").asString();
    HttpResponse<String> consent = BROWSER.send(HttpRequest.newBuilder(URI.create(started.path("authorizationUrl")
        .asString())).GET().build(), HttpResponse.BodyHandlers.ofString());
    String address = consent.headers().firstValue("Location").orElseThrow();
    // The browser was on another device: the person copies the address instead.
    String forged = address.replaceAll("state=[^&]+", "state=forged");
    assertThat(admin("POST", "/api/mcp-sign-ins/" + id + "/address", "{\"address\":\"" + forged + "\"}").status())
        .isEqualTo(422);
    JsonNode done = json(admin("POST", "/api/mcp-sign-ins/" + id + "/address", "{\"address\":\"" + address + "\"}"),
        200);
    assertThat(done.path("state").asString()).isEqualTo("signed-in");
    assertThat(json(admin("POST", "/api/mcp-servers/tracker-mcp/test", null), 200).path("tools").asInt())
        .isEqualTo(1);
  }

  @Test
  @Order(6)
  void signOutRevokesAndNoTokenShowsAnywhere() throws Exception {
    JsonNode secrets = json(admin("GET", "/api/secrets", null), 200);
    assertThat(secrets.toString()).contains("tracker-mcp/oauth-sign-in");
    assertNoToken(secrets.toString());
    assertNoToken(json(admin("GET", "/api/connections/tracker-mcp", null), 200).toString());
    Answer replaced = admin("PUT", "/api/secrets/" + secretId(secrets) + "/value", "{\"value\":\"x\"}");
    assertThat(replaced.status()).isIn(400, 409, 422);
    assertThat(replaced.body()).contains("Sign in to the server again");

    JsonNode out = json(admin("POST", "/api/mcp-servers/tracker-mcp/sign-out", "{\"reason\":\"done\"}"), 200);
    assertThat(out.path("state").asString()).isEqualTo("signed-out");
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (tracker.revocations.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(tracker.revocations).anySatisfy(r -> assertThat(r).containsEntry("token_type_hint", "refresh_token"));
    Answer refused = admin("POST", "/api/mcp-servers/tracker-mcp/test", null);
    assertThat(refused.body()).contains("isn't signed in");

    Answer deleted = admin("DELETE", "/api/mcp-servers/tracker-mcp", "{\"reason\":\"done\"}");
    assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
    assertThat(json(admin("GET", "/api/secrets", null), 200).toString()).doesNotContain("tracker-mcp");
    assertNoToken(core.coreLog());
    Path serverLog = core.home.resolve("logs/mcp/tracker-mcp.log");
    if (Files.exists(serverLog)) {
      assertNoToken(Files.readString(serverLog));
    }
  }

  @Test
  @Order(7)
  void whereTheServerTakesMetadataDocumentsGateRookSignsInByItsOwnAndRegistersNothing() throws Exception {
    ObjectNode review = CoreProcess.JSON.createObjectNode();
    review.set("config", config("board-mcp", board, false));
    JsonNode r = json(admin("POST", "/api/mcp-servers/review", review.toString()), 200);
    assertThat(r.at("/auth/kind").asString()).as(r.toString()).isEqualTo("oauth");
    assertThat(r.at("/auth/registration").asString()).isEqualTo("metadata-document");

    ObjectNode request = CoreProcess.JSON.createObjectNode();
    request.set("config", config("board-mcp", board, true));
    JsonNode started = json(admin("POST", "/api/mcp-sign-ins", request.toString()), 201);
    String id = started.path("id").asString();
    assertThat(started.path("redirectUri").asString()).matches("http://localhost:\\d+/callback");
    String authorization = started.path("authorizationUrl").asString();
    assertThat(param(authorization, "client_id")).isEqualTo(OAuthMcpServer.GATEROOK_CLIENT);
    assertThat(param(authorization, "redirect_uri")).isEqualTo(started.path("redirectUri").asString());

    HttpResponse<String> landed = signInInTheBrowser(authorization);
    assertThat(landed.statusCode()).as(landed.body()).isEqualTo(200);
    assertThat(landed.body()).contains("Signed in to board-mcp");
    assertThat(board.documentsRead).containsExactly(OAuthMcpServer.GATEROOK_CLIENT);
    assertThat(board.registrations).as("nothing registered beside the document").isEmpty();
    assertThat(board.tokenRequests.getFirst()).containsEntry("grant_type", "authorization_code")
        .containsEntry("client_id", OAuthMcpServer.GATEROOK_CLIENT).doesNotContainKey("client_secret");

    ObjectNode add = request.deepCopy().put("signIn", id).put("acknowledge", true).put("reason", "issue board");
    JsonNode added = json(admin("POST", "/api/mcp-servers", add.toString()), 201);
    assertThat(added.path("tools").asInt()).isEqualTo(1);
    JsonNode status = json(admin("GET", "/api/mcp-servers/board-mcp/sign-in", null), 200);
    assertThat(status.path("state").asString()).isEqualTo("signed-in");
    assertThat(status.path("registered").asBoolean()).as("its document, not a registration").isFalse();
    assertThat(json(admin("GET", "/api/mcp-servers/board-mcp/tools", null), 200)).extracting(t -> t.path("name")
        .asString()).containsExactly("board_list_issues");
  }

  @Test
  @Order(8)
  void theDocumentsSignInIsRenewedAsItAndSigningInAgainTakesAnotherFreePort() throws Exception {
    board.expireAccessTokens();
    Answer restarted = admin("POST", "/api/mcp-servers/board-mcp/restart", null);
    assertThat(restarted.status()).as(restarted.body()).isEqualTo(200);
    // The authorization server binds the refresh token to the client it was issued to: the document's URL.
    assertThat(board.tokenRequests.getLast()).containsEntry("grant_type", "refresh_token")
        .containsEntry("client_id", OAuthMcpServer.GATEROOK_CLIENT);

    JsonNode again = json(admin("POST", "/api/mcp-servers/board-mcp/sign-in", null), 201);
    assertThat(again.path("redirectUri").asString()).matches("http://localhost:\\d+/callback");
    HttpResponse<String> landed = signInInTheBrowser(again.path("authorizationUrl").asString());
    assertThat(landed.statusCode()).as(landed.body()).isEqualTo(200);
    assertThat(board.documentsRead).hasSize(2);
    assertThat(board.registrations).isEmpty();
    assertThat(json(admin("POST", "/api/mcp-servers/board-mcp/test", null), 200).path("tools").asInt())
        .isEqualTo(1);
  }

  @Test
  @Order(9)
  void signOutRevokesAsTheDocumentAndNoTokenShowsInTheLog() throws Exception {
    JsonNode out = json(admin("POST", "/api/mcp-servers/board-mcp/sign-out", "{\"reason\":\"done\"}"), 200);
    assertThat(out.path("state").asString()).isEqualTo("signed-out");
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (board.revocations.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(board.revocations).hasSize(2).allSatisfy(revocation -> assertThat(revocation)
        .containsEntry("client_id", OAuthMcpServer.GATEROOK_CLIENT));

    Answer deleted = admin("DELETE", "/api/mcp-servers/board-mcp", "{\"reason\":\"done\"}");
    assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
    assertNoToken(core.coreLog());
  }

  @Test
  @Order(10)
  void aKeyInTheUrlIsWarnedAboutAndTheServerStillConnectsWithIt() throws Exception {
    ObjectNode review = CoreProcess.JSON.createObjectNode();
    review.set("config", CoreProcess.JSON.createObjectNode().put("name", "keyed-mcp").put("transport", "http")
        .put("url", tracker.base + "/mcp?apiKey=" + OAuthMcpServer.URL_KEY));
    JsonNode r = json(admin("POST", "/api/mcp-servers/review", review.toString()), 200);
    assertThat(r.path("url").asString()).as("the review shows the URL without its key")
        .isEqualTo(tracker.base + "/mcp?apiKey=‹masked›");
    assertThat(r.path("warnings")).extracting(JsonNode::asString)
        .anySatisfy(w -> assertThat(w).startsWith("The URL carries a key."));

    ObjectNode add = review.deepCopy().put("acknowledge", true);
    JsonNode added = json(admin("POST", "/api/mcp-servers", add.toString()), 201);
    assertThat(added.path("tools").asInt()).as("it answered with the key in its URL").isEqualTo(1);
    assertThat(json(admin("POST", "/api/mcp-servers/keyed-mcp/test", null), 200).path("tools").asInt()).isEqualTo(1);

    String listed = null;
    for (JsonNode row : json(admin("GET", "/api/connections", null), 200).path("items")) {
      if ("keyed-mcp".equals(row.path("name").asString())) {
        listed = row.path("host").asString();
      }
    }
    assertThat(listed).as("where it connects, without the key").isEqualTo(tracker.base + "/mcp");
    assertThat(core.coreLog()).doesNotContain(OAuthMcpServer.URL_KEY);
    Answer deleted = admin("DELETE", "/api/mcp-servers/keyed-mcp", "{\"reason\":\"done\"}");
    assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
  }

  @Test
  @Order(11)
  void aServerWhoseSignInIsOptionalConnectsWithoutOneAndSignsInLaterFromItsRow() throws Exception {
    tracker.anonymous = true;
    try {
      ObjectNode review = CoreProcess.JSON.createObjectNode();
      review.set("config", config("open-mcp", tracker, false));
      JsonNode r = json(admin("POST", "/api/mcp-servers/review", review.toString()), 200);
      assertThat(r.at("/auth/kind").asString()).as(r.toString()).isEqualTo("optional");
      assertThat(r.at("/auth/cloud").isNull()).isTrue();

      // Connect without signing in: the sign-in stays possible, from the server's row.
      ObjectNode add = CoreProcess.JSON.createObjectNode().put("acknowledge", true).put("reason", "open tracker");
      add.set("config", config("open-mcp", tracker, true));
      ((ObjectNode) add.path("config").path("auth")).put("optional", true);
      JsonNode added = json(admin("POST", "/api/mcp-servers", add.toString()), 201);
      assertThat(added.path("tools").asInt()).isEqualTo(1);
      JsonNode status = json(admin("GET", "/api/mcp-servers/open-mcp/sign-in", null), 200);
      assertThat(status.path("state").asString()).isEqualTo("signed-out");
      assertThat(status.path("message").asString()).contains("answers without a sign-in");
      assertThat(json(admin("POST", "/api/mcp-servers/open-mcp/test", null), 200).path("tools").asInt()).isEqualTo(1);

      JsonNode started = json(admin("POST", "/api/mcp-servers/open-mcp/sign-in", null), 201);
      HttpResponse<String> landed = signInInTheBrowser(started.path("authorizationUrl").asString());
      assertThat(landed.statusCode()).as(landed.body()).isEqualTo(200);
      assertThat(json(admin("GET", "/api/mcp-servers/open-mcp/sign-in", null), 200).path("state").asString())
          .isEqualTo("signed-in");
      int bearers = tracker.bearersSeen.size();
      assertThat(json(admin("POST", "/api/mcp-servers/open-mcp/test", null), 200).path("tools").asInt()).isEqualTo(1);
      assertThat(tracker.bearersSeen.subList(bearers, tracker.bearersSeen.size())).as(trace())
          .isNotEmpty().allMatch(b -> b.startsWith("Bearer trk-at-"));
      Answer deleted = admin("DELETE", "/api/mcp-servers/open-mcp", "{\"reason\":\"done\"}");
      assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
    } finally {
      tracker.anonymous = false;
    }
  }

  @Test
  @Order(12)
  void cloudSignInsAreCheckedBeforeAnythingRuns() throws Exception {
    // AWS signs for a service in a region; a host that names neither needs them said.
    ObjectNode aws = config("cloud-mcp", tracker, false);
    aws.putObject("auth").put("type", "aws").putObject("credentials").put("source", "profile").put("profile",
        "default");
    ObjectNode review = CoreProcess.JSON.createObjectNode();
    review.set("config", aws);
    Answer unnamed = admin("POST", "/api/mcp-servers/review", review.toString());
    assertThat(unnamed.status()).as(unnamed.body()).isEqualTo(422);
    assertThat(unnamed.body()).contains("Service and region missing");

    // A service account key is a secret of the server: adding needs it, beside the config.
    ObjectNode google = config("cloud-mcp", tracker, false);
    google.putObject("auth").put("type", "google").putObject("credentials").put("source", "serviceAccountKey");
    ObjectNode add = CoreProcess.JSON.createObjectNode().put("acknowledge", true);
    add.set("config", google);
    Answer keyless = admin("POST", "/api/mcp-servers", add.toString());
    assertThat(keyless.status()).as(keyless.body()).isEqualTo(422);
    assertThat(keyless.body()).contains("Enter the service account key");

    // One sign-in at a time: cloud credentials send the Authorization header themselves.
    ObjectNode both = config("cloud-mcp", tracker, false);
    both.putObject("auth").put("type", "google").putObject("credentials").put("source", "adc");
    both.putArray("headers").addObject().put("name", "Authorization");
    review.set("config", both);
    Answer twice = admin("POST", "/api/mcp-servers/review", review.toString());
    assertThat(twice.status()).as(twice.body()).isEqualTo(422);
    assertThat(twice.body()).contains("Google Cloud credentials send the Authorization header");

    // Each cloud's sources only.
    ObjectNode mixed = config("cloud-mcp", tracker, false);
    mixed.putObject("auth").put("type", "google").putObject("credentials").put("source", "accessKey");
    review.set("config", mixed);
    Answer wrong = admin("POST", "/api/mcp-servers/review", review.toString());
    assertThat(wrong.status()).as(wrong.body()).isEqualTo(422);
    assertThat(wrong.body()).contains("isn't where Google Cloud credentials come from");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** One query parameter of {@code url}, decoded, or {@code null}. */
  private static String param(String url, String name) {
    for (String pair : URI.create(url).getRawQuery().split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private record Answer(int status, String body) {}

  /** The person allows access at the provider; the browser follows the redirect to GateRook on this computer. */
  private static HttpResponse<String> signInInTheBrowser(String authorizationUrl) throws Exception {
    HttpResponse<String> consent = BROWSER.send(HttpRequest.newBuilder(URI.create(authorizationUrl)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(consent.statusCode()).as(consent.body()).isEqualTo(302);
    String location = consent.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith("http://localhost:").contains("code=").contains("iss=");
    return BROWSER.send(HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofSeconds(30)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** What the fake saw, tokens replaced by their order of issue: for a failure's description. */
  private static String trace() {
    StringBuilder out = new StringBuilder("bearers: ");
    tracker.bearersSeen.forEach(b -> out.append(b.startsWith("Bearer trk-at-") ? "AT" + tracker.issued.indexOf(b
        .substring(7)) : b).append(' '));
    out.append("\ntoken grants: ");
    tracker.tokenRequests.forEach(r -> out.append(r.get("grant_type")).append(' '));
    return out.toString();
  }

  private static void assertNoToken(String text) {
    assertThat(text).doesNotContain("trk-at-").doesNotContain("trk-rt-").doesNotContain("trk-code-");
  }

  private static String secretId(JsonNode secrets) {
    JsonNode items = secrets.isArray() ? secrets : secrets.path("items");
    for (JsonNode s : items) {
      if (s.path("name").asString("").equals("tracker-mcp/oauth-sign-in")) {
        return s.path("id").asString();
      }
    }
    throw new AssertionError("No sign-in secret in " + secrets);
  }

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  /** A request of the window (or of Electron's main process, for /internal): signed with the launch secret. */
  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(60))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(), nonce()))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
