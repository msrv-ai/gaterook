package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.clients.internal.ClientsConfiguration;
import ai.msrv.gaterook.core.clients.internal.JsonEntryEditor;
import ai.msrv.gaterook.core.clients.internal.TomlBlockEditor;
import ai.msrv.gaterook.core.endpoints.ClientGrants;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.SetupProgress;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Clients one click (ARCHITECTURE.md A7, §7.2, K1) on a whole core whose clients live in a sandbox home under
 * {@code target/}: what Connect shows and writes for each of the four clients, backups and their permissions, a file
 * changed between preview and Confirm, the bridge tokens of a grant and everything that refuses one, the first prod
 * token waiting for the person, Disconnect as a reverse edit that keeps the person's own changes, drift, endpoint
 * removal, and in the end: no answer of the admin API holds a token (§9).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClientsIT {

  private static final Pattern NAMED = Pattern.compile("gwt_[A-Za-z0-9_-]{43}");
  private static final Pattern BRIDGE = Pattern.compile("gwb_[A-Za-z0-9_-]{43}");
  private static final Pattern GRANT = Pattern.compile("gr_[a-z0-9]{12}");
  /** Short enough to see a token expire; a release always issues 15 minutes. */
  private static final Duration TTL = Duration.ofSeconds(4);

  private static Path userHome;
  private static TestCore core;
  private static AdminMvc api;
  private static int port;
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  /** What the core logs while this class runs. */
  private static final ListAppender<ILoggingEvent> LOGGED = new ListAppender<>();

  private static String cursorBefore;
  private static String codexBefore;
  private static String cursorLink;
  private static String cursorToken;
  private static String codexLink;
  private static String codexGrant;
  private static String claudeProdLink;

  @BeforeAll
  static void start() throws IOException {
    Path parent = Files.createDirectories(Path.of("target", "client-homes").toAbsolutePath());
    userHome = Files.createTempDirectory(parent, "user-");
    cursorBefore = golden("json/kit.before");
    codexBefore = golden("toml/kit.before");
    Path cursor = Files.createDirectories(userHome.resolve(".cursor")).resolve("mcp.json");
    Files.writeString(cursor, cursorBefore);
    Files.setPosixFilePermissions(cursor, PosixFilePermissions.fromString("rw-r--r--"));
    Files.writeString(Files.createDirectories(userHome.resolve(".codex")).resolve("config.toml"), codexBefore);
    Files.createDirectories(userHome.resolve(".config/Claude"));
    core = TestCore.start("clients", "--gateway.clients.user-home=" + userHome, "--gateway.clients.os=linux",
        "--gateway.clients.bridge-token-ttl=" + TTL);
    // After the start: Boot resets logging while a context starts.
    LOGGED.start();
    rootLogger().addAppender(LOGGED);
    port = core.bean(LocalListener.class).port();
    api = new AdminMvc(core.context(), port);
    EndpointService endpoints = core.bean(EndpointService.class);
    EndpointConnections wiring = core.bean(EndpointConnections.class);
    core.connection("dev-db", "pgfake", Env.DEV, "db.test");
    core.connection("prod-db", "pgfake", Env.PROD, "db.test");
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("dev", "dev-db", null);
    endpoints.create("incident-prod", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("incident-prod", "prod-db", null);
  }

  @AfterAll
  static void stop() {
    rootLogger().detachAppender(LOGGED);
    LOGGED.stop();
    if (core != null) {
      core.close();
    }
  }

  // ── The page ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void theScanFindsTheClientsOfTheSandboxHome() {
    JsonNode page = api.expect(200, HttpMethod.POST, "/api/clients/scan", null);
    assertThat(page.path("clients").findValuesAsString("client")).containsExactly("claude-desktop", "codex", "cursor",
        "antigravity");
    assertThat(card(page, "cursor").path("status").asString()).isEqualTo("detected");
    assertThat(card(page, "cursor").path("configDisplay").asString()).isEqualTo("~/.cursor/mcp.json");
    assertThat(card(page, "codex").path("status").asString()).isEqualTo("detected");
    assertThat(card(page, "claude-desktop").path("status").asString()).isEqualTo("detected");
    assertThat(card(page, "antigravity").path("status").asString()).isEqualTo("not-installed");
    JsonNode token = method(card(page, "cursor"), "token");
    assertThat(token.path("prodAllowed").asBoolean()).isFalse();
    assertThat(token.path("reason").asString()).contains("prod");
    assertThat(method(card(page, "codex"), "helper").path("prodAllowed").asBoolean()).isTrue();
    assertThat(method(card(page, "claude-desktop"), "mcpb").path("prodAllowed").asBoolean()).isTrue();
  }

  // ── Cursor: a named token in the file ───────────────────────────────────────────────────────────────────────

  @Test
  @Order(10)
  void cursorShowsTheChangeThenWritesOneEntryWithAToken() throws Exception {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect/preview", """
        {"endpoint":"dev","method":"token","token":{"name":"cursor-laptop","ttlDays":7}}""");
    JsonNode file = preview.path("files").get(0);
    assertThat(file.path("kind").asString()).isEqualTo("config");
    assertThat(file.path("display").asString()).isEqualTo("~/.cursor/mcp.json");
    // The kit's "What will change": 7 additions, 1 deletion, the token as a placeholder.
    assertThat(file.path("diff").path("added").asInt()).isEqualTo(7);
    assertThat(file.path("diff").path("removed").asInt()).isEqualTo(1);
    assertThat(file.path("diff").path("hunks").get(0).path("header").asString()).isEqualTo("@@ -1,8 +1,14 @@");
    assertThat(file.toString()).contains("Bearer ‹new token›").doesNotContain("gwt_");
    assertThat(file.path("modeChange").asString()).contains("rw-r--r--").contains("rw-------");
    assertThat(file.path("backupPath").asString()).isEqualTo("~/.cursor/mcp.json.bak-" + LocalDate.now());
    assertThat(preview.path("link").asString()).startsWith("cursor://anysphere.cursor-deeplink/mcp/install?name="
        + "gateway-dev&config=");
    assertThat(preview.path("notes").toString()).contains("cursor-laptop").contains("plain text");
    assertThat(preview.path("repair").asBoolean()).isFalse();

    JsonNode connected = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    JsonNode link = connected.path("link");
    cursorLink = link.path("id").asString();
    assertThat(link.path("status").asString()).isEqualTo("needs-restart");
    assertThat(link.path("tokenName").asString()).isEqualTo("cursor-laptop");
    assertThat(link.path("entryName").asString()).isEqualTo("gateway-dev");

    Path cursor = userHome.resolve(".cursor/mcp.json");
    String after = Files.readString(cursor);
    Matcher m = NAMED.matcher(after);
    assertThat(m.find()).isTrue();
    cursorToken = m.group();
    assertThat(after).isEqualTo(expectedCursor(cursorToken));
    // Only our entry: taking it out gives the original back byte for byte.
    assertThat(JsonEntryEditor.remove(after, "mcpServers", "gateway-dev")).isEqualTo(cursorBefore);
    assertThat(mode(cursor)).isEqualTo("rw-------");
    // The copy next to the file keeps the original's permissions; the app's own copies are owner-only.
    Path bak = userHome.resolve(".cursor/mcp.json.bak-" + LocalDate.now());
    assertThat(Files.readString(bak)).isEqualTo(cursorBefore);
    assertThat(mode(bak)).isEqualTo("rw-r--r--");
    Path backups = core.home().resolve("backups/cursor");
    assertThat(mode(backups)).isEqualTo("rwx------");
    assertThat(mode(core.home().resolve("backups"))).isEqualTo("rwx------");
    List<Path> copies = list(backups);
    assertThat(copies).singleElement().satisfies(p -> {
      assertThat(mode(p)).isEqualTo("rw-------");
      assertThat(Files.readString(p)).isEqualTo(cursorBefore);
    });
    assertThat(api.ok("/api/clients/cursor/backups")).singleElement().satisfies(b -> assertThat(b.path("file")
        .asString()).endsWith("-mcp.json"));

    // The token works on its endpoint, and the link turns connected once it did.
    assertThat(initialize("dev", cursorToken).statusCode()).isEqualTo(200);
    assertThat(initialize("incident-prod", cursorToken).statusCode()).isEqualTo(401);
    TestCore.await(() -> linkOf("cursor", cursorLink).path("status").asString().equals("connected"),
        "the cursor link to turn connected");
    assertThat(setupChanges("client", "Cursor → dev")).contains("Not connected → Connected");
  }

  /** The golden {@code kit.after} with this core's port and the token Connect issued. */
  private static String expectedCursor(String token) throws IOException {
    return golden("json/kit.after").replace("127.0.0.1:7465", "127.0.0.1:" + port).replace("‹new token›", token);
  }

  @Test
  @Order(11)
  void aTokenIsNotOfferedOnProd() {
    JsonNode refused = api.expect(422, HttpMethod.POST, "/api/clients/cursor/connect/preview", """
        {"endpoint":"incident-prod","method":"token"}""");
    assertThat(refused.toString()).contains("outlives the incident");
    api.expect(422, HttpMethod.POST, "/api/clients/antigravity/connect/preview", """
        {"endpoint":"incident-prod","method":"oauth"}""");
  }

  @Test
  @Order(12)
  void aPreviewOfAnEndpointThatReachedProdSinceIsCheckedAgain() throws Exception {
    // A preview creates no token and no grant, so an empty endpoint may take its first prod connection while a Cursor
    // token waits for Confirm (review K1-3): Confirm checks the endpoint again and issues and writes nothing.
    core.bean(EndpointService.class).create("edge", null, EndpointService.StartFrom.EMPTY, null);
    Path cursor = userHome.resolve(".cursor/mcp.json");
    String before = Files.readString(cursor);
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect/preview", """
        {"endpoint":"edge","method":"token","token":{"name":"cursor-edge","ttlDays":7}}""");
    core.bean(EndpointConnections.class).add("edge", "prod-db", null);
    JsonNode refused = api.expect(409, HttpMethod.POST, "/api/clients/cursor/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    assertThat(refused.path("detail").asString()).contains("edge reaches prod now");
    assertThat(Files.readString(cursor)).isEqualTo(before);
    assertThat(core.jdbc().queryForObject("select count(*) from api_token where name = 'cursor-edge'",
        Integer.class)).isZero();
    // Checked again, prod gets no token in a file.
    api.expect(422, HttpMethod.POST, "/api/clients/cursor/connect/preview", """
        {"endpoint":"edge","method":"token","token":{"name":"cursor-edge","ttlDays":7}}""");
    core.bean(EndpointService.class).delete("edge", "edge", null);
  }

  // ── Codex: the headers helper and its grant ─────────────────────────────────────────────────────────────────

  @Test
  @Order(20)
  void aFileChangedAfterThePreviewIsNotOverwritten() throws Exception {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/codex/connect/preview", """
        {"endpoint":"dev","method":"helper","grantLabel":"codex-laptop"}""");
    Path config = userHome.resolve(".codex/config.toml");
    String edited = codexBefore + "\n# the person's note\n";
    Files.writeString(config, edited);
    JsonNode stale = api.expect(409, HttpMethod.POST, "/api/clients/codex/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    assertThat(stale.path("title").asString()).isEqualTo("The file changed");
    assertThat(Files.readString(config)).isEqualTo(edited);
    assertThat(core.jdbc().queryForObject("select count(*) from client_grant where label = 'codex-laptop'",
        Integer.class)).as("no grant for a Connect that didn't happen").isZero();
    // A preview is used once.
    api.expect(404, HttpMethod.POST, "/api/clients/codex/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    Files.writeString(config, codexBefore);
  }

  @Test
  @Order(21)
  void codexGetsAHelperAndNoToken() throws Exception {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/codex/connect/preview", """
        {"endpoint":"dev","method":"helper","grantLabel":"codex-laptop","codex":{"approvalMode":"writes"}}""");
    JsonNode diff = preview.path("files").get(0).path("diff");
    assertThat(diff.path("removed").asInt()).isZero();
    assertThat(preview.toString()).contains("http_headers_helper").contains("default_tools_approval_mode")
        .doesNotContain("gwb_").doesNotContain("gwt_");
    JsonNode connected = api.expect(200, HttpMethod.POST, "/api/clients/codex/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\",\"reason\":\"laptop\"}");
    codexLink = connected.path("link").path("id").asString();
    assertThat(connected.path("link").path("grantLabel").asString()).isEqualTo("codex-laptop");
    assertThat(connected.path("link").path("method").asString()).isEqualTo("helper");

    String after = Files.readString(userHome.resolve(".codex/config.toml"));
    assertThat(after).startsWith(codexBefore).contains(TomlBlockEditor.startMarker("gateway-dev"))
        .contains("[mcp_servers.gateway-dev]").contains("url = \"http://127.0.0.1:" + port + "/mcp/dev\"")
        .contains("default_tools_approval_mode = \"writes\"").contains("startup_timeout_sec = 20")
        .contains("tool_timeout_sec = 60").doesNotContain("gwb_").doesNotContain("gwt_");
    Matcher helper = Pattern.compile("http_headers_helper = (.+)").matcher(after);
    assertThat(helper.find()).isTrue();
    assertThat(helper.group(1)).contains("bin/gw-bridge").contains(" headers --endpoint dev --grant gr_");
    Matcher g = GRANT.matcher(helper.group(1));
    assertThat(g.find()).isTrue();
    codexGrant = g.group();
    assertThat(TomlBlockEditor.remove(after, "gateway-dev")).as("Disconnect gives the file back").isEqualTo(codexBefore);
    assertThat(setupChanges("grant", "Grant codex-laptop (codex) for dev")).contains("Active");
  }

  @Test
  @Order(22)
  void theGrantGetsBridgeTokensForItsOwnEndpointAndClientOnly() throws Exception {
    JsonNode ok = bridgeToken("codex", "dev", codexGrant);
    assertThat(ok.path("ok").asBoolean()).as(ok.toString()).isTrue();
    String token = ok.path("token").asString();
    assertThat(token).matches(BRIDGE.pattern());
    assertThat(ok.path("url").asString()).isEqualTo("http://127.0.0.1:" + port + "/mcp/dev");
    assertThat(Instant.parse(ok.path("expiresAt").asString())).isBefore(Instant.now().plus(TTL).plusSeconds(1));
    HttpResponse<String> init = initialize("dev", token);
    assertThat(init.statusCode()).isEqualTo(200);
    assertThat(init.headers().firstValue(ClientsConfiguration.INSTANCE_HEADER)).hasValue(ok.path("instance")
        .asString());
    // Only the SHA-256 of a bridge token is stored.
    assertThat(core.jdbc().queryForObject("select count(*) from api_token where hash like ?", Integer.class,
        "%" + token.substring(4, 20) + "%")).isZero();

    assertThat(bridgeToken("codex", "incident-prod", codexGrant).path("error").asString()).isEqualTo("grant_mismatch");
    assertThat(bridgeToken("cursor", "dev", codexGrant).path("error").asString()).isEqualTo("grant_mismatch");
    assertThat(bridgeToken("codex", "dev", "gr_000000000000").path("error").asString()).isEqualTo("grant_unknown");
    assertThat(bridgeToken("codex", "dev", "not-a-grant").path("error").asString()).isEqualTo("bad_request");
    assertThat(api.expect(200, HttpMethod.POST, "/internal/bridge/tokens", """
        {"v":2,"op":"token","client":"codex","endpoint":"dev","grant":"%s"}""".formatted(codexGrant))
        .path("error").asString()).isEqualTo("bad_request");
    // Not signed by the launch secret: what any other program could send.
    assertThat(api.sendUnsigned(HttpMethod.POST, "/internal/bridge/tokens", "{}").status()).isEqualTo(401);

    // At most four live tokens per grant: a fifth retires the oldest.
    for (int i = 0; i < 4; i++) {
      assertThat(bridgeToken("codex", "dev", codexGrant).path("ok").asBoolean()).isTrue();
    }
    assertThat(liveTokens(codexGrant)).isEqualTo(ClientGrants.MAX_LIVE_TOKENS);
    assertThat(initialize("dev", token).statusCode()).as("the oldest was superseded").isEqualTo(401);
    assertThat(core.jdbc().queryForObject("select count(*) from api_token where grant_id = ? and revoke_reason = "
        + "'superseded'", Integer.class, codexGrant)).isEqualTo(1);
  }

  @Test
  @Order(23)
  void anExpiredTokenIsRefusedAndTheNextOneWorks() throws Exception {
    // What Codex does: the helper's token expires, the core answers 401, Codex runs the helper again.
    String first = bridgeToken("codex", "dev", codexGrant).path("token").asString();
    assertThat(initialize("dev", first).statusCode()).isEqualTo(200);
    Thread.sleep(TTL.toMillis() + 600);
    HttpResponse<String> expired = initialize("dev", first);
    assertThat(expired.statusCode()).isEqualTo(401);
    assertThat(expired.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v)
        .startsWith("Bearer"));
    String second = bridgeToken("codex", "dev", codexGrant).path("token").asString();
    assertThat(second).isNotEqualTo(first);
    assertThat(initialize("dev", second).statusCode()).isEqualTo(200);
    assertThat(linkOf("codex", codexLink).path("status").asString()).isEqualTo("connected");
  }

  @Test
  @Order(24)
  void aGrantGetsTwentyTokensAMinute() {
    String grant = core.bean(ClientGrants.class).create(null, "cursor", "dev", "rate-test", null).id();
    for (int i = 0; i < ClientGrants.TOKENS_PER_MINUTE; i++) {
      assertThat(bridgeToken("cursor", "dev", grant).path("ok").asBoolean()).isTrue();
    }
    assertThat(bridgeToken("cursor", "dev", grant).path("error").asString()).isEqualTo("rate_limited");
    assertThat(liveTokens(grant)).isEqualTo(ClientGrants.MAX_LIVE_TOKENS);
    core.bean(ClientGrants.class).revoke(grant, "test");
    assertThat(bridgeToken("cursor", "dev", grant).path("error").asString()).isEqualTo("grant_revoked");
  }

  @Test
  @Order(25)
  void checkWalksTheChainAndNamesWhatIsMissing() {
    JsonNode check = api.expect(200, HttpMethod.POST, "/api/clients/links/" + codexLink + "/check", null);
    List<String> steps = check.path("steps").findValuesAsString("id");
    assertThat(steps).contains("config", "shim", "broker", "grant", "bridge", "tools", "calls");
    // No Electron here: no broker, so the helper can't be run.
    assertThat(step(check, "broker").path("state").asString()).isEqualTo("error");
    assertThat(step(check, "grant").path("state").asString()).isEqualTo("ok");
    assertThat(step(check, "config").path("state").asString()).isEqualTo("ok");
    assertThat(check.path("summary").asString()).isEqualTo("Something needs you");
  }

  @Test
  @Order(26)
  void aGrantChangedOutsideTheAppGetsNothing() throws Exception {
    // Grants are signed rows (§9 integrity): one pointed at prod in the database file is no grant for prod.
    String grant = core.bean(ClientGrants.class).create(null, "cursor", "dev", "tamper-test", null).id();
    String token = bridgeToken("cursor", "dev", grant).path("token").asString();
    assertThat(initialize("dev", token).statusCode()).isEqualTo(200);
    String dev = core.jdbc().queryForObject("select endpoint_id from client_grant where id = ?", String.class, grant);
    String prod = core.jdbc().queryForObject("select id from endpoint where slug = 'incident-prod'", String.class);
    core.jdbc().update("update client_grant set endpoint_id = ? where id = ?", prod, grant);
    assertThat(bridgeToken("cursor", "incident-prod", grant).path("error").asString()).isEqualTo("grant_changed");
    core.jdbc().update("update client_grant set endpoint_id = ?, label = 'renamed' where id = ?", dev, grant);
    assertThat(bridgeToken("cursor", "dev", grant).path("error").asString()).isEqualTo("grant_changed");
    assertThat(initialize("dev", token).statusCode()).as("its live token stops too").isEqualTo(401);
  }

  // ── Claude Desktop: an extension per endpoint, prod waits for the person ────────────────────────────────────

  @Test
  @Order(30)
  void claudeDesktopGetsAnExtensionAndItsFirstProdTokenWaitsForThePerson() throws Exception {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect/preview", """
        {"endpoint":"incident-prod","method":"mcpb","grantLabel":"claude-incident"}""");
    assertThat(preview.path("files").get(0).path("kind").asString()).isEqualTo("mcpb");
    assertThat(preview.path("warnings").toString()).contains("reaches prod");
    JsonNode connected = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    claudeProdLink = connected.path("link").path("id").asString();
    Path mcpb = Path.of(connected.path("mcpbPath").asString());
    assertThat(mcpb).isEqualTo(core.home().resolve("clients/claude-desktop/mcp-gateway-incident-prod.mcpb"));
    assertThat(mode(mcpb)).isEqualTo("rw-------");
    assertThat(api.ok("/internal/clients/links/" + claudeProdLink + "/mcpb").path("path").asString())
        .isEqualTo(mcpb.toRealPath().toString());
    api.expect(404, HttpMethod.GET, "/internal/clients/links/" + cursorLink + "/mcpb", null);

    String manifest;
    try (ZipFile zip = new ZipFile(mcpb.toFile())) {
      manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
      assertThat(zip.getEntry("server/gw-bridge.mjs")).isNotNull();
    }
    JsonNode m = TestCore.json(manifest);
    assertThat(m.path("name").asString()).isEqualTo("mcp-gateway-incident-prod");
    assertThat(m.path("display_name").asString()).isEqualTo("GateRook · incident-prod (prod)");
    assertThat(manifest).doesNotContain("gwb_").doesNotContain("gwt_");
    Matcher g = GRANT.matcher(m.path("server").path("mcp_config").path("args").toString());
    assertThat(g.find()).isTrue();
    String grant = g.group();

    try (EventBus.Subscription events = core.bean(EventBus.class).subscribe(OptionalLong.empty())) {
      JsonNode waiting = bridgeToken("claude-desktop", "incident-prod", grant);
      assertThat(waiting.path("error").asString()).isEqualTo("confirmation_required");
      // The window hears of it with the grant's prefix only, as every list and log shows it.
      EventBus.GatewayEvent asked = next(events, "bridge.confirm");
      assertThat(asked.data().path("grantPrefix").asString()).isEqualTo(grant.substring(0, 7) + "…");
      assertThat(asked.data().path("text").asString()).isEqualTo("Claude Desktop wants to use incident-prod");
      assertThat(asked.data().toString()).doesNotContain(grant);
    }
    api.expect(422, HttpMethod.POST, "/internal/bridge/confirm", "{\"grant\":\"" + grant + "\",\"decision\":\"ok\"}");
    api.expect(204, HttpMethod.POST, "/internal/bridge/confirm", "{\"grant\":\"" + grant + "\",\"decision\":\"deny\"}");
    assertThat(bridgeToken("claude-desktop", "incident-prod", grant).path("error").asString())
        .isEqualTo("confirmation_denied");
    api.expect(204, HttpMethod.POST, "/internal/bridge/confirm", "{\"grant\":\"" + grant + "\",\"decision\":\"allow\"}");
    JsonNode ok = bridgeToken("claude-desktop", "incident-prod", grant);
    assertThat(ok.path("ok").asBoolean()).as(ok.toString()).isTrue();
    assertThat(initialize("incident-prod", ok.path("token").asString()).statusCode()).isEqualTo(200);
    // Allowed for this run of the core: the next token doesn't ask again.
    assertThat(bridgeToken("claude-desktop", "incident-prod", grant).path("ok").asBoolean()).isTrue();
    assertThat(linkOf("claude-desktop", claudeProdLink).path("status").asString()).isEqualTo("connected");
  }

  @Test
  @Order(31)
  void claudeDesktopCanUseTheBridgeEntryInItsConfigInstead() throws Exception {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect/preview", """
        {"endpoint":"dev","method":"config-bridge","grantLabel":"claude-dev"}""");
    JsonNode file = preview.path("files").get(0);
    assertThat(file.path("exists").asBoolean()).isFalse();
    assertThat(file.path("diff").path("removed").asInt()).isZero();
    String id = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}").path("link").path("id").asString();
    Path config = userHome.resolve(".config/Claude/claude_desktop_config.json");
    JsonNode entry = TestCore.json(Files.readString(config)).path("mcpServers").path("gateway-dev");
    assertThat(entry.path("command").asString()).endsWith("bin/gw-bridge");
    assertThat(entry.path("args").toString()).contains("stdio").contains("--endpoint").contains("dev")
        .contains("--client").contains("claude-desktop");
    assertThat(mode(config)).as("a new file is owner-only").isEqualTo("rw-------");
    JsonNode back = disconnect(id);
    assertThat(back.path("entryRemoved").asBoolean()).isTrue();
    assertThat(TestCore.json(Files.readString(config)).path("mcpServers").has("gateway-dev")).isFalse();
  }

  @Test
  @Order(32)
  void checkRunsOnlyTheCommandMcpGatewayWrote() throws Exception {
    Path codex = userHome.resolve(".codex/config.toml");
    String codexOurs = Files.readString(codex);
    Path marker = userHome.resolve("a-stranger-ran");
    try (PingBroker broker = new PingBroker(core.home().resolve("run/broker.json"))) {
      // Our own helper runs (this build has no bridge, so the shim says so with exit 1).
      JsonNode check = api.expect(200, HttpMethod.POST, "/api/clients/links/" + codexLink + "/check", null);
      assertThat(step(check, "broker").path("state").asString()).as(check.toString()).isEqualTo("ok");
      assertThat(step(check, "bridge").path("text").asString()).contains("exit code 1");

      // A program of this account rewrites the helper: Codex would run it, Check doesn't.
      Files.writeString(codex, codexOurs.replaceFirst("http_headers_helper = \".*\"", Matcher.quoteReplacement(
          "http_headers_helper = \"touch " + marker + "\"")));
      check = api.expect(200, HttpMethod.POST, "/api/clients/links/" + codexLink + "/check", null);
      assertThat(step(check, "bridge").path("state").asString()).isEqualTo("error");
      assertThat(step(check, "bridge").path("text").asString()).contains("isn't the one GateRook wrote");
      assertThat(marker).doesNotExist();

      // The same for a stdio entry: Claude Desktop's bridge entry made to start another program.
      JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect/preview", """
          {"endpoint":"dev","method":"config-bridge","grantLabel":"claude-dev"}""");
      String id = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect",
          "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}").path("link").path("id").asString();
      Path claude = userHome.resolve(".config/Claude/claude_desktop_config.json");
      String claudeOurs = Files.readString(claude);
      String command = TestCore.json(claudeOurs).path("mcpServers").path("gateway-dev").path("command").asString();
      Files.writeString(claude, claudeOurs.replace("\"" + command + "\"", "\"/bin/sh\"").replace("\"stdio\"", "\"-c\"")
          .replace("\"--endpoint\"", "\"touch " + marker + "\""));
      check = api.expect(200, HttpMethod.POST, "/api/clients/links/" + id + "/check", null);
      assertThat(step(check, "bridge").path("state").asString()).as(check.toString()).isEqualTo("error");
      assertThat(step(check, "bridge").path("text").asString()).contains("isn't the one GateRook wrote");
      assertThat(marker).doesNotExist();
      Files.writeString(claude, claudeOurs);
      assertThat(disconnect(id).path("entryRemoved").asBoolean()).isTrue();
    } finally {
      Files.writeString(codex, codexOurs);
    }
  }

  @Test
  @Order(33)
  void checkDoesntAskForTheFirstProdTokenInTheClientsPlace() throws Exception {
    // Its broker would show "Cursor wants to use incident-prod" for what is a Check, and an Allow would hold for any
    // program with the grant's id for the rest of the session (review K1-15).
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect/preview", """
        {"endpoint":"incident-prod","method":"config-bridge","grantLabel":"cursor-incident"}""");
    String id = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}").path("link").path("id").asString();
    try (PingBroker broker = new PingBroker(core.home().resolve("run/broker.json"))) {
      JsonNode check = api.expect(200, HttpMethod.POST, "/api/clients/links/" + id + "/check", null);
      assertThat(step(check, "grant").path("text").asString()).contains("waits for you to allow it");
      assertThat(step(check, "bridge").path("state").asString()).as(check.toString()).isEqualTo("skipped");
      assertThat(step(check, "bridge").path("text").asString()).contains("Check doesn't ask in its place");
    } finally {
      assertThat(disconnect(id).path("entryRemoved").asBoolean()).isTrue();
    }
  }

  // ── Antigravity: a file that doesn't exist yet ──────────────────────────────────────────────────────────────

  @Test
  @Order(40)
  void antigravityGetsANewOwnerOnlyFile() throws Exception {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/antigravity/connect/preview", """
        {"endpoint":"dev","method":"token","token":{"name":"antigravity-laptop","ttlDays":1}}""");
    JsonNode file = preview.path("files").get(0);
    assertThat(file.path("exists").asBoolean()).isFalse();
    assertThat(file.path("display").asString()).isEqualTo("~/.gemini/config/mcp_config.json");
    assertThat(file.path("diff").path("hunks").get(0).path("header").asString()).startsWith("@@ -0,0 +1,");
    assertThat(file.path("modeChange").asString()).contains("0600");
    String id = api.expect(200, HttpMethod.POST, "/api/clients/antigravity/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}").path("link").path("id").asString();
    Path config = userHome.resolve(".gemini/config/mcp_config.json");
    assertThat(mode(config)).isEqualTo("rw-------");
    JsonNode entry = TestCore.json(Files.readString(config)).path("mcpServers").path("gateway-dev");
    assertThat(entry.path("serverUrl").asString()).isEqualTo("http://127.0.0.1:" + port + "/mcp/dev");
    String token = entry.path("headers").path("Authorization").asString().substring("Bearer ".length());
    assertThat(token).matches(NAMED.pattern());
    disconnect(id);
    assertThat(initialize("dev", token).statusCode()).as("Disconnect revokes the token").isEqualTo(401);
    assertThat(TestCore.json(Files.readString(config)).path("mcpServers").has("gateway-dev")).isFalse();
  }

  @Test
  @Order(41)
  void aSignInThroughTheBrowserTurnsConnectedWithTheClientsFirstCall() {
    // No access is written for a sign-in: the client gets one when it signs in, so its calls are found by the
    // client's label on the endpoint (review U9 #7: such a link stayed "needs restart" for good).
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/antigravity/connect/preview", """
        {"endpoint":"dev","method":"oauth"}""");
    String id = api.expect(200, HttpMethod.POST, "/api/clients/antigravity/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}").path("link").path("id").asString();
    try {
      JsonNode before = linkOf("antigravity", id);
      assertThat(before.path("status").asString()).isEqualTo("needs-restart");
      assertThat(before.path("lastCallAt").isNull()).isTrue();
      assertThat(card(api.ok("/api/clients"), "antigravity").path("status").asString()).isEqualTo("needs-restart");

      // Review API #3: a call under a token of the endpoint whose client calls itself Antigravity. The call path
      // writes "antigravity" as its client (the token implies none, so the name the client gives itself stands), but
      // any holder of a token could send that name: it proves nothing about the sign-in.
      ObjectNode reportsItself = JsonNodeFactory.instance.objectNode().put("name", "Antigravity");
      logCall(new Caller(Caller.Kind.LOCAL_TOKEN, "tok_other", "inspector-debug", null, Ingress.LOCAL, "127.0.0.1"),
          reportsItself);
      assertThat(linkOf("antigravity", id).path("status").asString()).as("a name a token caller gives itself")
          .isEqualTo("needs-restart");
      assertThat(linkOf("antigravity", id).path("lastCallAt").isNull()).isTrue();
      assertThat(card(api.ok("/api/clients"), "antigravity").path("status").asString()).isEqualTo("needs-restart");
      assertThat(overviewCard("antigravity")).as("Overview's clients node reads the same proof")
          .isEqualTo("needs-restart");

      // Antigravity signs in and calls: its grant is labelled with the name it registered with.
      logCall(new Caller(Caller.Kind.OAUTH, "gr_signin", "Antigravity", "antigravity", Ingress.LOCAL, "127.0.0.1"),
          null);
      assertThat(overviewCard("antigravity")).isEqualTo("connected");

      JsonNode after = linkOf("antigravity", id);
      assertThat(after.path("status").asString()).isEqualTo("connected");
      assertThat(after.path("lastCallAt").isNull()).isFalse();
      assertThat(after.path("revision").asString()).isEqualTo("2025-11-25");
      assertThat(card(api.ok("/api/clients"), "antigravity").path("status").asString()).isEqualTo("connected");
    } finally {
      disconnect(id);
    }
  }

  // ── Drift ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(50)
  void anEntryChangedOutsideTheAppNeedsRepairAndRepairKeepsItsGrant() throws Exception {
    Path config = userHome.resolve(".codex/config.toml");
    String ours = Files.readString(config);
    Files.writeString(config, ours.replace("127.0.0.1:" + port + "/", "127.0.0.1:1/"));
    JsonNode link = linkOf("codex", codexLink);
    assertThat(link.path("status").asString()).isEqualTo("needs-repair");
    assertThat(link.path("statusText").asString()).contains("port 1");
    JsonNode page = api.ok("/api/clients");
    assertThat(card(page, "codex").path("status").asString()).isEqualTo("error");

    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/codex/connect/preview", """
        {"endpoint":"dev","method":"helper"}""");
    assertThat(preview.path("repair").asBoolean()).isTrue();
    assertThat(preview.toString()).contains(codexGrant);
    api.expect(200, HttpMethod.POST, "/api/clients/codex/connect", "{\"previewId\":\""
        + preview.path("previewId").asString() + "\"}");
    assertThat(Files.readString(config)).isEqualTo(ours);
    assertThat(linkOf("codex", codexLink).path("status").asString()).isIn("connected", "needs-restart");
    assertThat(setupChanges("client", "Codex → dev")).contains("Needs repair → Connected");
  }

  // ── Disconnect ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(60)
  void disconnectTakesOutOurEntryAndKeepsWhatThePersonChangedSince() throws Exception {
    Path cursor = userHome.resolve(".cursor/mcp.json");
    String ours = Files.readString(cursor);
    // The person changes the file after Connect: a setting at the top, another command for github.
    String edited = person(ours);
    Files.writeString(cursor, edited);

    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/links/" + cursorLink + "/disconnect/preview",
        null);
    JsonNode diff = preview.path("files").get(0).path("diff");
    assertThat(diff.path("removed").asInt()).isEqualTo(7);
    assertThat(diff.path("added").asInt()).isEqualTo(1);
    assertThat(preview.toString()).doesNotContain(cursorToken);
    JsonNode done = api.expect(200, HttpMethod.POST, "/api/clients/links/" + cursorLink + "/disconnect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    assertThat(done.path("entryRemoved").asBoolean()).isTrue();

    assertThat(Files.readString(cursor)).as("nothing of the person's is lost").isEqualTo(person(cursorBefore));
    assertThat(initialize("dev", cursorToken).statusCode()).isEqualTo(401);
    assertThat(api.ok("/api/clients").path("clients").findValuesAsString("id")).doesNotContain(cursorLink);
    // No copy outlives the token's revocation.
    List<Path> copies = new ArrayList<>(list(core.home().resolve("backups/cursor")));
    try (Stream<Path> s = Files.list(userHome.resolve(".cursor"))) {
      s.filter(p -> p.getFileName().toString().startsWith("mcp.json.bak-")).forEach(copies::add);
    }
    assertThat(copies).isNotEmpty().allSatisfy(p -> assertThat(Files.readString(p)).doesNotContain(cursorToken));
    assertThat(setupChanges("client", "Cursor → dev")).contains("Connected → Disconnected");
  }

  private static String person(String text) {
    return text.replace("{\n  \"mcpServers\"", "{\n  \"theme\": \"dark\",\n  \"mcpServers\"")
        .replace("\"command\": \"npx\"", "\"command\": \"bunx\"");
  }

  @Test
  @Order(61)
  void disconnectingCodexGivesItsFileBackAndEndsTheGrant() throws Exception {
    disconnect(codexLink);
    assertThat(Files.readString(userHome.resolve(".codex/config.toml"))).isEqualTo(codexBefore);
    assertThat(bridgeToken("codex", "dev", codexGrant).path("error").asString()).isEqualTo("grant_revoked");
    assertThat(liveTokens(codexGrant)).isZero();
  }

  @Test
  @Order(62)
  void anEntryRemovedByHandIsShownAndDisconnectStillEndsTheAccess() throws Exception {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/codex/connect/preview", """
        {"endpoint":"dev","method":"helper","grantLabel":"codex-laptop"}""");
    String id = api.expect(200, HttpMethod.POST, "/api/clients/codex/connect", "{\"previewId\":\""
        + preview.path("previewId").asString() + "\"}").path("link").path("id").asString();
    Path config = userHome.resolve(".codex/config.toml");
    String grant = grantIn(Files.readString(config));
    assertThat(grant).isNotEqualTo(codexGrant);
    Files.writeString(config, codexBefore);
    assertThat(linkOf("codex", id).path("status").asString()).isEqualTo("removed-outside");
    JsonNode p = api.expect(200, HttpMethod.POST, "/api/clients/links/" + id + "/disconnect/preview", null);
    assertThat(p.path("files").size()).isZero();
    assertThat(p.path("notes").toString()).contains("already gone");
    assertThat(disconnect(id).path("entryRemoved").asBoolean()).isFalse();
    assertThat(bridgeToken("codex", "dev", grant).path("error").asString()).isEqualTo("grant_revoked");
  }

  // ── Restore, endpoint removal, the Overview node ────────────────────────────────────────────────────────────

  @Test
  @Order(70)
  void aBackupCanBeRestored() throws Exception {
    JsonNode backups = api.ok("/api/clients/codex/backups");
    assertThat(backups.size()).isGreaterThanOrEqualTo(2);
    String oldest = backups.get(backups.size() - 1).path("id").asString();
    Path copy = core.home().resolve("backups/codex").resolve(oldest);
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/codex/restore/preview",
        "{\"backupId\":\"" + oldest + "\"}");
    assertThat(preview.path("files").get(0).path("kind").asString()).isEqualTo("restore");
    api.expect(404, HttpMethod.POST, "/api/clients/codex/restore/preview", "{\"backupId\":\"../../gateway.db\"}");
    api.expect(200, HttpMethod.POST, "/api/clients/codex/restore", "{\"previewId\":\""
        + preview.path("previewId").asString() + "\"}");
    assertThat(Files.readString(userHome.resolve(".codex/config.toml"))).isEqualTo(Files.readString(copy));
  }

  @Test
  @Order(80)
  void deletingAnEndpointEndsItsLinksAndDeletesItsExtensions() throws Exception {
    core.bean(EndpointService.class).create("scratch", null, EndpointService.StartFrom.EMPTY, null);
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect/preview", """
        {"endpoint":"scratch","method":"mcpb"}""");
    JsonNode connected = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    Path mcpb = Path.of(connected.path("mcpbPath").asString());
    assertThat(mcpb).exists();
    EndpointService.DeleteSummary deleted = core.bean(EndpointService.class).delete("scratch", "scratch", null);
    assertThat(deleted.clients()).singleElement().satisfies(c -> assertThat(c).startsWith("Claude Desktop"));
    assertThat(mcpb).doesNotExist();
    assertThat(core.jdbc().queryForObject("select count(*) from client_link where id = ?", Integer.class,
        connected.path("link").path("id").asString())).isZero();
  }

  @Test
  @Order(81)
  void overviewCountsTheConnectedClients() {
    SetupProgress clients = core.context().getBeansOfType(SetupProgress.class).values().stream()
        .filter(p -> p.area().equals("clients")).findFirst().orElseThrow();
    SetupProgress.Progress p = clients.progress();
    assertThat(p.count()).isEqualTo(1);
    assertThat(p.done()).isTrue();
    assertThat(p.status()).isEqualTo("Claude Desktop");
  }

  @Test
  @Order(82)
  void manualSetupShowsAPlaceholderNotAToken() {
    JsonNode manual = api.ok("/api/clients/manual?endpoint=dev");
    assertThat(manual.path("url").asString()).isEqualTo("http://127.0.0.1:" + port + "/mcp/dev");
    assertThat(manual.path("snippets").findValuesAsString("id")).contains("inspector-config", "inspector-ui",
        "toml");
    assertThat(manual.toString()).contains("Bearer ‹token›");
    assertThat(manual.path("others").findValuesAsString("slug")).contains("incident-prod");
  }

  @Test
  @Order(84)
  void disconnectDeletesTheExtensionItBuiltAndNothingElse() throws Exception {
    // client_link rows carry no MAC: a path changed in the database must not make Disconnect delete another file.
    Path victim = Files.writeString(userHome.resolve("notes.txt"), "the person's notes");
    String changed = connectExtension("dev");
    Path mcpb = core.home().resolve("clients/claude-desktop/mcp-gateway-dev.mcpb");
    assertThat(mcpb).exists();
    core.jdbc().update("update client_link set config_path = ? where id = ?", victim.toString(), changed);
    assertThat(disconnect(changed).path("entryRemoved").asBoolean()).isFalse();
    assertThat(victim).hasContent("the person's notes");
    // The extension it built goes with its own link.
    String own = connectExtension("dev");
    assertThat(disconnect(own).path("entryRemoved").asBoolean()).isTrue();
    assertThat(mcpb).doesNotExist();
  }

  private static String connectExtension(String endpoint) {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect/preview",
        "{\"endpoint\":\"" + endpoint + "\",\"method\":\"mcpb\"}");
    return api.expect(200, HttpMethod.POST, "/api/clients/claude-desktop/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}").path("link").path("id").asString();
  }

  @Test
  @Order(99)
  void noAdminAnswerHoldsAToken() {
    for (AdminMvc.Answer a : api.answers()) {
      if (a.target().equals("/internal/bridge/tokens")) {
        continue;
      }
      assertThat(a.body()).as(a.method() + " " + a.target()).doesNotContainPattern(NAMED.pattern())
          .doesNotContainPattern(BRIDGE.pattern());
      if (a.contentType() != null && a.contentType().contains("json") && !a.body().isEmpty()) {
        assertThat(AdminMvc.secretLookingValues(a.json())).as(a.method() + " " + a.target()).isEmpty();
      }
    }
    assertThat(api.answers()).anySatisfy(a -> assertThat(a.target()).isEqualTo("/api/clients/cursor/connect"));
  }

  @Test
  @Order(99)
  void noLogLineHoldsATokenOrAWholeGrant() throws IOException {
    // §9 "token shown once": every token of this class went through the core and none may be logged, not even masked
    // later by the log pattern; a grant shows its prefix only (gr_Cx7q…). The bridge's own log is held to the same.
    List<String> lines = new ArrayList<>();
    synchronized (LOGGED) {
      for (ILoggingEvent e : LOGGED.list) {
        lines.add(e.getLoggerName() + ": " + e.getFormattedMessage()
            + (e.getThrowableProxy() == null ? "" : " " + ThrowableProxyUtil.asString(e.getThrowableProxy())));
      }
    }
    assertThat(lines).as("the core logged while connecting four clients").isNotEmpty();
    Path bridgeLog = core.home().resolve("logs/bridge.log");
    if (Files.exists(bridgeLog)) {
      lines.addAll(Files.readAllLines(bridgeLog, StandardCharsets.UTF_8));
    }
    assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContainPattern(NAMED.pattern())
        .doesNotContainPattern(BRIDGE.pattern()).doesNotContainPattern(GRANT.pattern()));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static ch.qos.logback.classic.Logger rootLogger() {
    return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  private static String golden(String name) throws IOException {
    try (InputStream in = ClientsIT.class.getResourceAsStream("/ai/msrv/gaterook/core/clients/golden/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static JsonNode card(JsonNode page, String client) {
    for (JsonNode c : page.path("clients")) {
      if (c.path("client").asString().equals(client)) {
        return c;
      }
    }
    throw new AssertionError("no card " + client);
  }

  private static JsonNode method(JsonNode card, String id) {
    for (JsonNode m : card.path("methods")) {
      if (m.path("id").asString().equals(id)) {
        return m;
      }
    }
    throw new AssertionError("no method " + id);
  }

  private static JsonNode step(JsonNode check, String id) {
    for (JsonNode s : check.path("steps")) {
      if (s.path("id").asString().equals(id)) {
        return s;
      }
    }
    throw new AssertionError("no step " + id);
  }

  private static JsonNode linkOf(String client, String id) {
    for (JsonNode l : card(api.ok("/api/clients"), client).path("links")) {
      if (l.path("id").asString().equals(id)) {
        return l;
      }
    }
    throw new AssertionError("no link " + id);
  }

  /** A card's status as Overview's clients node reads it (each link's last call, no revisions). */
  private static String overviewCard(String client) {
    return core.bean(ai.msrv.gaterook.core.admin.clients.ClientCalls.class)
        .withLastCalls(core.bean(ClientService.class).clients().clients()).stream()
        .filter(c -> c.client().equals(client)).findFirst().orElseThrow().status();
  }

  /**
   * One allowed call of {@code gateway_status} on dev as the call path logs it: the client is what the credential
   * implies, else the name the caller gives itself ({@code clientInfo}), as {@code mcp.internal.Calls.client} has it.
   */
  private static void logCall(Caller caller, JsonNode clientInfo) {
    String client = caller.clientHint() != null ? caller.clientHint()
        : clientInfo.path("name").asString("").toLowerCase(java.util.Locale.ROOT);
    AuditWriter.Call call = new AuditWriter.Call(ai.msrv.gaterook.core.platform.Ids.next("tc_"), "dev",
        "gateway_status", "gateway_status", null, Env.DEV, caller, client, clientInfo, "2025-11-25",
        "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", false, JsonNodeFactory.instance.objectNode(),
        "0".repeat(64), null);
    AuditWriter writer = core.bean(AuditWriter.class);
    writer.received(call, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED, Classification.Kind.READ, null));
    writer.completed(call, new AuditWriter.Completion(AuditWriter.Verdict.ALLOWED, null, null, null, null, 3L, 3L,
        0L, null, 2L, "0".repeat(64), "ok", 0, false, null, null, null, null, null));
  }

  private static JsonNode disconnect(String linkId) {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/links/" + linkId + "/disconnect/preview", null);
    return api.expect(200, HttpMethod.POST, "/api/clients/links/" + linkId + "/disconnect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
  }

  /** The next event of {@code type} on {@code events}, within a few seconds. */
  private static EventBus.GatewayEvent next(EventBus.Subscription events, String type) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      java.util.Optional<EventBus.GatewayEvent> e = events.next(Duration.ofMillis(500));
      if (e.isPresent() && e.get().type().equals(type)) {
        return e.get();
      }
    }
    throw new AssertionError("no " + type + " event");
  }

  private static String grantIn(String text) {
    Matcher m = GRANT.matcher(text);
    assertThat(m.find()).isTrue();
    return m.group();
  }

  private static JsonNode bridgeToken(String client, String endpoint, String grant) {
    return api.expect(200, HttpMethod.POST, "/internal/bridge/tokens", """
        {"v":1,"op":"token","client":"%s","endpoint":"%s","grant":"%s","pid":4242,"bridge":"0.4.0"}"""
        .formatted(client, endpoint, grant));
  }

  private static int liveTokens(String grant) {
    return core.jdbc().queryForObject("select count(*) from api_token where grant_id = ? and revoked_at is null and "
        + "expires_at > ?", Integer.class, grant, Instant.now().toEpochMilli());
  }

  private static String setupChanges(String what, String subject) {
    return String.join("\n", core.jdbc().queryForList("select coalesce(change_from, '') || ' → ' || "
        + "coalesce(change_to, '') from setup_change where what = ? and subject = ? order by id", String.class, what,
        subject));
  }

  /**
   * A broker that only answers pings, named in the gateway home's {@code run/broker.json}: Check's step 2 passes, so
   * step 3 decides whether to run the entry's command. Its socket lives in the temp folder: a path under
   * {@code target/} is longer than a Unix socket's name may be on macOS.
   */
  private static final class PingBroker implements AutoCloseable {

    private final Path info;
    private final Path socket;
    private final ServerSocketChannel server;

    PingBroker(Path info) throws IOException {
      this.info = info;
      this.socket = Files.createTempFile("k1-broker", ".sock");
      Files.delete(socket);
      this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      server.bind(UnixDomainSocketAddress.of(socket));
      Files.createDirectories(info.getParent());
      Files.writeString(info, "{\"v\":1,\"path\":\"" + socket + "\"}");
      Thread.ofVirtual().start(() -> {
        while (server.isOpen()) {
          try (SocketChannel ch = server.accept()) {
            ByteBuffer in = ByteBuffer.allocate(4096);
            while (ch.read(in) > 0 && in.get(in.position() - 1) != '\n') {
              // until the request line ends
            }
            ch.write(ByteBuffer.wrap("{\"v\":1,\"ok\":true,\"broker\":\"test\"}\n".getBytes(StandardCharsets.UTF_8)));
          } catch (IOException e) {
            // Closed, or a caller that went away.
          }
        }
      });
    }

    @Override
    public void close() throws IOException {
      server.close();
      Files.deleteIfExists(socket);
      Files.deleteIfExists(info);
    }
  }

  /** MCP initialize on {@code /mcp/<slug>} with {@code token}, over the real listener. */
  private static HttpResponse<String> initialize(String slug, String token) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/" + slug))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
            "capabilities":{},"clientInfo":{"name":"clients-it","version":"1"}}}"""))
        .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String mode(Path p) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
  }

  private static List<Path> list(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.sorted().toList();
    }
  }
}
