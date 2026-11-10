package ai.msrv.gaterook.e2e.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An external MCP server on the assembled core, started as Electron starts it (ARCHITECTURE.md §7.5, C10): the review
 * shows the command as it will run, Add starts it once with the typed secret before anything is saved, its tools join
 * an endpoint in Read only for declared reads and Approval otherwise; mutations cannot use Read only,
 * agents see the prefixed names without a {@code connection} argument, a read runs, an approved call returns with the
 * server's echo of its secret masked, Stop stops the process, and Delete leaves nothing. With
 * {@code -Dgateway.core.binary} the same runs against the native executable (the MCP SDK's records in the image).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServersCoreProcessIT {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final String TOKEN = "notes-tok-" + Long.toHexString(System.nanoTime()) + "-7c1e";
  private static CoreProcess core;
  private static String token;

  @BeforeAll
  static void start() throws Exception {
    core = CoreProcess.start(CoreProcess.newHome("mcp-servers-e2e"), CoreProcess.freePort(), List.of()).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  /** The server's JVM reads its long class path from an argument file: an argument is at most 1024 characters. */
  private static Path argFile() throws java.io.IOException {
    Path file = Path.of("target", "mcp-servers-e2e", "notes-mcp.args").toAbsolutePath();
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.writeString(file, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\")
        + "\"\n" + NotesMcpServer.class.getName() + "\n");
    return file;
  }

  private static ObjectNode config() throws java.io.IOException {
    ObjectNode c = CoreProcess.JSON.createObjectNode().put("name", "notes-mcp").put("transport", "stdio");
    ArrayNode command = c.putArray("command");
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("@" + argFile());
    c.putArray("env").addObject().put("name", "NOTES_TOKEN");
    return c;
  }

  @Test
  @Order(1)
  void theReviewShowsWhatWillRunAndAddStartsItOnce() throws Exception {
    ObjectNode review = CoreProcess.JSON.createObjectNode();
    review.set("config", config());
    JsonNode r = json(admin("POST", "/api/mcp-servers/review", review.toString()), 200);
    assertThat(r.path("argv").get(0).asString()).endsWith("/bin/java");
    assertThat(r.path("env")).extracting(JsonNode::asString).containsExactly("NOTES_TOKEN");
    assertThat(r.path("notPinned").asBoolean()).isFalse();
    assertThat(r.path("name").asString()).isEqualTo("notes-mcp");

    ObjectNode create = review.deepCopy();
    create.putObject("secrets").putObject("/env/0/secretRef").put("source", "local").put("value", TOKEN);
    json(admin("POST", "/api/mcp-servers", create.toString()), 422);

    create.put("acknowledge", true).put("reason", "notes for the agent");
    JsonNode added = json(admin("POST", "/api/mcp-servers", create.toString()), 201);
    assertThat(added.path("tools").asInt()).isEqualTo(3);

    JsonNode tools = json(admin("GET", "/api/mcp-servers/notes-mcp/tools", null), 200);
    assertThat(tools).extracting(t -> t.path("name").asString())
        .containsExactlyInAnyOrder("notes_search", "notes_create_note", "notes_whoami");
    assertThat(tools).allSatisfy(t -> {
      assertThat(t.path("declaredBy").asString()).isEqualTo("notes-mcp");
      assertThat(t.path("verified").asBoolean(true)).isFalse();
    });

    JsonNode connection = json(admin("GET", "/api/connections/notes-mcp", null), 200);
    assertThat(connection.toString()).doesNotContain(TOKEN);
    assertThat(connection.path("secrets").get(0).path("field").asString()).isEqualTo("/env/0/secretRef");
    assertThat(connection.path("secrets").get(0).path("name").asString()).isEqualTo("notes-mcp/notes-token");

    JsonNode types = json(admin("GET", "/api/connector-types", null), 200);
    List<JsonNode> servers = new ArrayList<>();
    types.path("installed").forEach(t -> {
      if (t.path("kind").asString().equals("mcp-server")) {
        servers.add(t);
      }
    });
    assertThat(servers).singleElement().satisfies(s -> {
      assertThat(s.path("id").asString()).isEqualTo("notes-mcp");
      assertThat(s.path("version").asString()).isEqualTo("1.0.0");
      assertThat(s.path("tools").asInt()).isEqualTo(3);
    });
  }

  @Test
  @Order(2)
  void declaredReadsJoinAnEndpointInReadOnlyWithoutPerToolConfirmation() throws Exception {
    json(admin("POST", "/api/endpoints", "{\"slug\":\"notes\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/notes/connections/notes-mcp", "{}"), 200);
    List<JsonNode> rows = rows();
    assertThat(rows).extracting(t -> t.path("name").asString())
        .containsExactlyInAnyOrder("notes_search", "notes_create_note", "notes_whoami");
    assertThat(row("notes_search").path("mode").asString()).isEqualTo("READ_ONLY");
    assertThat(row("notes_whoami").path("mode").asString()).isEqualTo("READ_ONLY");
    assertThat(row("notes_create_note").path("mode").asString()).isEqualTo("APPROVAL");
    assertThat(row("notes_search").path("readOnlyAllowed").asBoolean()).isTrue();
    assertThat(row("notes_search").path("readOnlyConfirmed").asBoolean()).isFalse();

    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_search"), "APPROVAL")), 200);
    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_search"), "READ_ONLY")), 200);
    assertThat(row("notes_search").path("mode").asString()).isEqualTo("READ_ONLY");
    // Keep explicit approval coverage for the secret-masking call below.
    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_whoami"), "APPROVAL")), 200);

    Answer mutation = admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_create_note"), "READ_ONLY"));
    assertThat(mutation.status()).isIn(409, 422);
    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_create_note"), "OFF")), 200);
    assertThat(row("notes_create_note").path("mode").asString()).isEqualTo("OFF");
  }

  @Test
  @Order(3)
  void agentsSeeThePrefixedToolsAndAReadRunsOnTheServer() throws Exception {
    JsonNode settings = json(admin("GET", "/api/endpoints/notes/settings", null), 200);
    json(admin("PUT", "/api/endpoints/notes/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/notes/tokens", "{\"name\":\"e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"mcp-servers-e2e","version":"1"}}}""");

    JsonNode list = mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
    List<String> names = new ArrayList<>();
    list.at("/result/tools").forEach(t -> names.add(t.path("name").asString()));
    assertThat(names).contains("notes_search", "notes_whoami").doesNotContain("notes_create_note");
    list.at("/result/tools").forEach(t -> {
      if (t.path("name").asString().startsWith("notes_")) {
        assertThat(t.at("/inputSchema/properties").has("connection")).as(t.toString()).isFalse();
        assertThat(t.path("description").asString()).contains("notes-mcp (1.0.0, dev)");
      }
    });

    JsonNode found = call(3, "notes_search", "{\"q\":\"groceries\"}");
    assertThat(found.at("/result/isError").asBoolean(true)).as(found.toString()).isFalse();
    assertThat(found.at("/result/content/0/text").asString()).isEqualTo("found: groceries");
  }

  @Test
  @Order(4)
  void anApprovedCallRunsAndTheServersEchoOfItsSecretIsMasked() throws Exception {
    JsonNode pending = call(4, "notes_whoami", "{}");
    String id = pending.at("/result/structuredContent/handle").asString();
    assertThat(pending.at("/result/structuredContent/status").asString()).as(pending.toString())
        .isEqualTo("pending");
    JsonNode detail = json(admin("GET", "/api/approvals/" + id, null), 200);
    assertThat(detail.at("/action/text").asString()).contains("\"method\" : \"tools/call\"")
        .contains("\"name\" : \"whoami\"");
    json(admin("POST", "/api/approvals/" + id + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);

    JsonNode status = call(5, "approval_status", "{\"handle\":\"" + id + "\",\"wait_seconds\":20}");
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString()).isEqualTo("completed");
    assertThat(status.toString()).doesNotContain(TOKEN).contains("‹masked›");
  }

  @Test
  @Order(5)
  void aRenameMovesTheServersFolderLogAndSecretNamesAndKeepsItsToolsAndModes() throws Exception {
    List<String> modes = rows("notes-mcp").stream().map(t -> t.path("name").asString() + "=" + t.path("mode")
        .asString()).sorted().toList();
    assertThat(core.home.resolve("mcp/notes-mcp")).isDirectory();

    rename("notes-mcp", "notes-v2");

    assertThat(core.home.resolve("mcp/notes-mcp")).doesNotExist();
    assertThat(core.home.resolve("mcp/notes-v2")).isDirectory();
    assertThat(core.home.resolve("logs/mcp/notes-mcp.log")).doesNotExist();
    assertThat(core.home.resolve("logs/mcp/notes-v2.log")).exists();
    assertThat(admin("GET", "/api/connections/notes-mcp", null).status()).isEqualTo(404);
    assertThat(json(admin("GET", "/api/connections/notes-v2", null), 200).at("/secrets/0/name").asString())
        .isEqualTo("notes-v2/notes-token");
    assertThat(rows("notes-v2").stream().map(t -> t.path("name").asString() + "=" + t.path("mode").asString())
        .sorted().toList()).isEqualTo(modes);
    JsonNode list = mcp("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/list\",\"params\":{}}");
    List<String> names = new ArrayList<>();
    list.at("/result/tools").forEach(t -> {
      if (t.path("name").asString().startsWith("notes_")) {
        names.add(t.path("name").asString());
        assertThat(t.path("description").asString()).contains("notes-v2 (1.0.0, dev)");
      }
    });
    assertThat(names).containsExactlyInAnyOrder("notes_search", "notes_whoami");
    JsonNode found = call(21, "notes_search", "{\"q\":\"milk\"}");
    assertThat(found.at("/result/isError").asBoolean(true)).as(found.toString()).isFalse();
    assertThat(found.at("/result/content/0/text").asString()).isEqualTo("found: milk");
    assertThat(java.nio.file.Files.readString(core.home.resolve("logs/mcp/notes-v2.log"))).doesNotContain(TOKEN);

    rename("notes-v2", "notes-mcp");

    assertThat(core.home.resolve("mcp/notes-v2")).doesNotExist();
    assertThat(core.home.resolve("mcp/notes-mcp")).isDirectory();
    assertThat(rows("notes-mcp")).hasSize(3);
    JsonNode again = call(22, "notes_search", "{\"q\":\"bread\"}");
    assertThat(again.at("/result/content/0/text").asString()).as(again.toString()).isEqualTo("found: bread");
  }

  /**
   * What the server lists at its next start decides nothing the person decided (ARCHITECTURE.md §7.5): another
   * description takes Read only back to Approval until the person sets it again, and a tool the server stops listing
   * and lists again keeps the Off it was given instead of becoming a declared read again.
   */
  @Test
  @Order(6)
  void aToolTheServerChangesOrUnlistsCanNeitherKeepReadOnlyNorUndoAnOff() throws Exception {
    Path variant = core.home.resolve("mcp/notes-mcp/variant");
    assertThat(row("notes_search").path("storedMode").asString()).isEqualTo("READ_ONLY");

    java.nio.file.Files.writeString(variant, "changed");
    json(admin("POST", "/api/mcp-servers/notes-mcp/restart", null), 200);
    awaitStored("notes_search", "APPROVAL");
    assertThat(row("notes_whoami").path("storedMode").asString()).isEqualTo("APPROVAL");
    JsonNode held = call(30, "notes_search", "{\"q\":\"groceries\"}");
    assertThat(held.at("/result/structuredContent/status").asString()).as(held.toString()).isEqualTo("pending");

    // The person sets it for what it is now, and it runs; then turns it Off.
    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_search"), "READ_ONLY")), 200);
    JsonNode found = call(31, "notes_search", "{\"q\":\"bread\"}");
    assertThat(found.at("/result/content/0/text").asString()).as(found.toString()).isEqualTo("found: bread");
    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_search"), "OFF")), 200);

    // Unlisted: not published. Listed again with its first description, as a declared read: still Off.
    java.nio.file.Files.writeString(variant, "gone");
    json(admin("POST", "/api/mcp-servers/notes-mcp/restart", null), 200);
    awaitRows(names -> !names.contains("notes_search"));
    java.nio.file.Files.delete(variant);
    json(admin("POST", "/api/mcp-servers/notes-mcp/restart", null), 200);
    awaitRows(names -> names.contains("notes_search"));
    assertThat(row("notes_search").path("storedMode").asString()).isEqualTo("OFF");
    assertThat(row("notes_whoami").path("storedMode").asString()).isEqualTo("APPROVAL");

    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_search"), "READ_ONLY")), 200);
    assertThat(row("notes_search").path("mode").asString()).isEqualTo("READ_ONLY");
  }

  @Test
  @Order(7)
  void bulkOffIncludesUnannotatedMutations() throws Exception {
    JsonNode tools = json(admin("GET", "/api/mcp-servers/notes-mcp/tools", null), 200);
    assertThat(tools).filteredOn(t -> t.path("name").asString().equals("notes_create_note"))
        .singleElement().satisfies(t -> {
          assertThat(t.path("annotations").isNull() || t.path("annotations").isMissingNode()).isTrue();
          assertThat(t.path("nature").asString()).isEqualTo("mutation");
        });
    json(admin("PUT", "/api/endpoints/notes/tools", mode(row("notes_create_note"), "APPROVAL")), 200);
    JsonNode bulk = json(admin("POST", "/api/endpoints/notes/tools/bulk",
        "{\"group\":\"notes-mcp\",\"mode\":\"OFF\"}"), 200);
    assertThat(bulk.path("changed")).hasSize(3);
    assertThat(rows()).hasSize(3).allSatisfy(t -> assertThat(t.path("mode").asString()).isEqualTo("OFF"));
  }

  @Test
  @Order(8)
  void stopStopsTheProcessAndDeleteLeavesNothing() throws Exception {
    JsonNode stopped = json(admin("POST", "/api/mcp-servers/notes-mcp/stop", null), 200);
    assertThat(stopped.path("state").asString()).isEqualTo("stopped");
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (core.process.descendants().anyMatch(p -> p.isAlive() && p.info().commandLine().orElse("")
        .contains(NotesMcpServer.class.getName())) && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(core.process.descendants().filter(ProcessHandle::isAlive)
        .filter(p -> p.info().commandLine().orElse("").contains(NotesMcpServer.class.getName()))).isEmpty();

    Answer deleted = admin("DELETE", "/api/mcp-servers/notes-mcp", "{\"reason\":\"done\"}");
    assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
    assertThat(admin("GET", "/api/mcp-servers/notes-mcp/tools", null).status()).isEqualTo(404);
    assertThat(admin("GET", "/api/connections/notes-mcp", null).status()).isEqualTo(404);
    JsonNode secrets = json(admin("GET", "/api/secrets", null), 200);
    assertThat(secrets.toString()).doesNotContain("notes-mcp/notes-token");
  }

  @Test
  @Order(9)
  void theLogsHaveNoSecret() throws Exception {
    assertThat(core.coreLog()).doesNotContain(TOKEN);
    Path serverLog = core.home.resolve("logs/mcp/notes-mcp.log");
    if (java.nio.file.Files.exists(serverLog)) {
      assertThat(java.nio.file.Files.readString(serverLog)).doesNotContain(TOKEN);
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private record Answer(int status, String body) {}

  private static List<JsonNode> rows() throws Exception {
    return rows("notes-mcp");
  }

  private static List<JsonNode> rows(String connection) throws Exception {
    List<JsonNode> out = new ArrayList<>();
    json(admin("GET", "/api/endpoints/notes/tools", null), 200).forEach(g -> {
      if (g.path("connection").asString().equals(connection)) {
        g.path("tools").forEach(out::add);
      }
    });
    return out;
  }

  /** Edit's Save with only a new name: the settings as the connection form sends them, the version just read. */
  private static void rename(String from, String to) throws Exception {
    JsonNode current = json(admin("GET", "/api/connections/" + from, null), 200);
    ObjectNode update = CoreProcess.JSON.createObjectNode().put("name", to).put("env", current.path("env")
        .asString()).put("version", current.path("version").asLong());
    // The form sends every member of the stdio branch with its default, the AppContainer switch among them.
    update.set("config", ((ObjectNode) current.path("config").deepCopy()).put("offThisComputer", false));
    assertThat(json(admin("PUT", "/api/connections/" + from, update.toString()), 200).path("name").asString())
        .isEqualTo(to);
  }

  private static JsonNode row(String name) throws Exception {
    return rows().stream().filter(t -> t.path("name").asString().equals(name)).findFirst().orElseThrow();
  }

  /** Waits for the core to follow a list the server gave (it reconciles the endpoints after it took it in). */
  private static void awaitRows(java.util.function.Predicate<List<String>> published) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (published.test(rows().stream().map(t -> t.path("name").asString()).toList())) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("The endpoint didn't follow the server's list: " + rows());
  }

  private static void awaitStored(String name, String mode) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (mode.equals(row(name).path("storedMode").asString())) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(name + " didn't go to " + mode + ": " + row(name));
  }

  /** A mode the person picks for the tool as the row shows it, with the definition it shows. */
  private static String mode(JsonNode row, String mode) {
    return "{\"toolKey\":\"" + row.path("toolKey").asString() + "\",\"connection\":\"notes-mcp\",\"mode\":\"" + mode
        + "\",\"definitionHash\":\"" + row.path("definitionHash").asString() + "\",\"version\":"
        + row.path("version").asLong() + "}";
  }

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  /** A request of the window: signed as Electron's main process signs it. */
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

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/notes"))
        .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static JsonNode call(int id, String tool, String arguments) throws Exception {
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":" + arguments + "}}");
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
