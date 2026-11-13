package com.gaterook.connectors.firebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Realtime Database connector against a fake of the database's REST API and of Google's token endpoints. */
class RtdbConnectorTest {

  private static final String URL = "https://acme-default-rtdb.firebaseio.com";
  private static final AtomicInteger CALLS = new AtomicInteger();

  private FakeGoogle google;
  private RtdbConnector rtdb;
  private final TestSecrets secrets = new TestSecrets();

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    rtdb = new RtdbConnector(google.cloud);
  }

  @AfterEach
  void stop() {
    rtdb.close();
    google.close();
  }

  private static ConnectionConfig configWith(Env env, String credentials, String... paths) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("databaseUrl", URL);
    c.set("credentials", TestCtx.JSON.readTree(credentials));
    if (paths.length > 0) {
      var list = c.putArray("paths");
      for (String p : paths) {
        list.add(p);
      }
    }
    return new ConnectionConfig("conn-rtdb-" + env.id(), env.id() + "-rtdb", "firebase-rtdb", env, 1, c,
        Set.of("/credentials/serviceAccountKey"), null);
  }

  private static ConnectionConfig config(Env env, String... paths) {
    return configWith(env, "{\"source\": \"adc\"}", paths);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".firebase-rtdb", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_rtdb_" + CALLS.incrementAndGet(), tool, "dev-rtdb",
        (ObjectNode) TestCtx.JSON.readTree(args), null);
  }

  private Classification classify(String tool, String args, String... paths) {
    return rtdb.classify(call(tool, args), config(Env.DEV, paths), policy(Env.DEV));
  }

  // ── Tools, settings and routes ──────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesAReadAndAWriteThatWaitsForApproval() {
    var m = rtdb.manifest();
    assertThat(m.id()).isEqualTo("firebase-rtdb");
    assertThat(m.name()).isEqualTo("Firebase Realtime Database");
    assertThat(m.icon()).isEqualTo("database");
    assertThat(m.group()).isEqualTo("Databases");
    assertThat(m.description()).endsWith(".");
    assertThat(m.configSchema().at("/x-card/description").asString())
        .isEqualTo("Paths, shallow reads and queries. Changes wait for approval.");
    assertThat(m.tools()).extracting(ToolDefinition::name).containsExactly("rtdb_get", "rtdb_write");
    for (ToolDefinition t : m.tools()) {
      assertThat(t.inputSchema().path("additionalProperties").asBoolean(true)).as(t.name()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection")).as(t.name())
          .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
    }
    ToolDefinition get = m.tools().getFirst();
    assertThat(get.nature()).isEqualTo(ToolDefinition.Nature.READ);
    assertThat(List.of(get.defaultMode(), get.prodDefaultMode())).containsOnly(ToolDefinition.Mode.READ_ONLY);
    ToolDefinition write = m.tools().getLast();
    assertThat(write.nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
    assertThat(write.annotations()).isEqualTo(ToolDefinition.Annotations.write(true));
    assertThat(write.outputSchema()).isNull();
    assertThat(List.of(write.defaultMode(), write.prodDefaultMode())).containsOnly(ToolDefinition.Mode.APPROVAL);
  }

  @Test
  void theDatabaseUrlIsOneOfTheHostsGoogleRunsForDatabases() {
    for (String url : List.of(URL, URL + "/", "https://acme-eu.europe-west1.firebasedatabase.app",
        "https://ACME.firebaseio.com")) {
      assertThat(RtdbSettings.database(url)).as(url).isNotNull();
    }
    assertThat(RtdbSettings.database("https://ACME.firebaseio.com:443/").toString())
        .isEqualTo("https://acme.firebaseio.com");
    for (String url : List.of("http://acme.firebaseio.com", URL + "/users", URL + "/.json", URL + "?ns=x",
        URL + "#x", "https://acme.web.app", "https://acme.firebaseapp.com", "https://acme.firebaseio.com:8443",
        "https://user@acme.firebaseio.com", "https://a.b.firebaseio.com", "https://firestore.googleapis.com",
        " " + URL, "not a url")) {
      assertThat(RtdbSettings.database(url)).as(url).isNull();
    }
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("databaseUrl", "https://acme.web.app");
    c.putObject("credentials").put("source", "adc");
    ConnectionConfig hosting = new ConnectionConfig("conn-x", "x", "firebase-rtdb", Env.DEV, 1, c, Set.of(), null);
    assertThat(rtdb.classify(call("rtdb_get", "{}"), hosting, policy(Env.DEV)).ruleId())
        .isEqualTo("dev.firebase-rtdb.bad-config");
    assertThat(rtdb.test(hosting, secrets, TestCtx.of()).error().code()).isEqualTo("rtdb.settings");
    assertThat(rtdb.health(hosting, secrets, TestCtx.of()).state()).isEqualTo(HealthResult.State.UNREACHABLE);
    ObjectNode badList = (ObjectNode) config(Env.DEV).config().deepCopy();
    badList.putArray("paths").add("users/alice");
    assertThat(rtdb.test(new ConnectionConfig("conn-y", "y", "firebase-rtdb", Env.DEV, 1, badList, Set.of(), null),
        secrets, TestCtx.of()).error().code()).isEqualTo("rtdb.settings");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void pathsKeysAndQueriesAreReadStrictly() {
    assertThat(classify("rtdb_get", "{\"path\": \"/users/alice smith\"}").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("rtdb_get", "{\"path\": \"users\"}").kind()).isEqualTo(Classification.Kind.READ);
    for (String path : List.of("/users/../x", "/users/..", "/users/.", "/a.b", "/a$b", "/a#b", "/a?b", "/a[0]",
        "/a]", "/a%2e%2e", "/a\\b", "/a\u0000", "/a\u001f", "/a‮b", "/a﻿b", "//a", "/a/", "/a//b")) {
      Classification c = classify("rtdb_get", TestCtx.JSON.writeValueAsString(Map.of("path", path)));
      assertThat(c.kind()).as(path).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(path).isEqualTo("dev.firebase-rtdb.bad-argument");
    }
    for (String args : List.of("{\"path\": \"/users\", \"limitToFirst\": 3}",
        "{\"path\": \"/users\", \"shallow\": true, \"orderBy\": \"$key\"}",
        "{\"path\": \"/users\", \"orderBy\": \"$key\", \"limitToFirst\": 3, \"limitToLast\": 3}",
        "{\"path\": \"/users\", \"orderBy\": \"$key\", \"equalTo\": \"a\", \"startAt\": \"a\"}",
        "{\"path\": \"/users\", \"orderBy\": \"a.b\"}", "{\"path\": \"/users\", \"orderBy\": \"/a\"}",
        "{\"path\": \"/users\", \"orderBy\": \"$key\", \"startAt\": {\"a\": 1}}",
        "{\"path\": \"/users\", \"orderBy\": \"$key\", \"limitToFirst\": 0}")) {
      assertThat(classify("rtdb_get", args).kind()).as(args).isEqualTo(Classification.Kind.DENY);
    }
    assertThat(classify("rtdb_get", "{\"path\": \"/users\", \"orderBy\": \"profile/age\", \"startAt\": 18, "
        + "\"limitToFirst\": 5}").kind()).isEqualTo(Classification.Kind.READ);
    for (String args : List.of("{\"operation\": \"set\", \"path\": \"/users/a\", \"value\": {\"a.b\": 1}}",
        "{\"operation\": \"set\", \"path\": \"/users/a\", \"value\": {\"a/b\": 1}}",
        "{\"operation\": \"set\", \"path\": \"/users/a\", \"value\": {\"$x\": 1}}",
        "{\"operation\": \"set\", \"path\": \"/users/a\", \"value\": null}",
        "{\"operation\": \"set\", \"path\": \"/users/a\"}",
        "{\"operation\": \"update\", \"path\": \"/users\", \"value\": {\"a/../b\": 1}}",
        "{\"operation\": \"update\", \"path\": \"/users\", \"value\": {\"/a\": 1}}",
        "{\"operation\": \"update\", \"path\": \"/users\", \"value\": {}}",
        "{\"operation\": \"update\", \"path\": \"/users\", \"value\": [1]}",
        "{\"operation\": \"delete\", \"path\": \"/users/a\", \"value\": 1}",
        "{\"operation\": \"drop\", \"path\": \"/users/a\"}")) {
      assertThat(classify("rtdb_write", args).kind()).as(args).isEqualTo(Classification.Kind.DENY);
    }
    assertThat(classify("rtdb_write", "{\"operation\": \"set\", \"path\": \"/users/a\", \"value\": {\"seen\": "
        + "{\".sv\": \"timestamp\"}}}").kind()).isEqualTo(Classification.Kind.MUTATION);
  }

  @Test
  void theListOfPathsKeepsReadsAndWritesToItsTopLevelKeys() {
    String[] list = {"users", "public-*"};
    assertThat(classify("rtdb_get", "{\"path\": \"/users/alice\"}", list).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("rtdb_get", "{\"path\": \"/public-feed/1\"}", list).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification secret = classify("rtdb_get", "{\"path\": \"/secrets\"}", list);
    assertThat(secret.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(secret.ruleId()).isEqualTo("dev.firebase-rtdb.path-not-allowed");
    assertThat(classify("rtdb_get", "{}", list).kind()).as("the root, shallow by default")
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("rtdb_get", "{\"path\": \"/\", \"shallow\": false}", list).ruleId())
        .isEqualTo("dev.firebase-rtdb.path-not-allowed");
    assertThat(classify("rtdb_get", "{\"path\": \"/\", \"orderBy\": \"$key\"}", list).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("rtdb_write", "{\"operation\": \"set\", \"path\": \"/secrets/x\", \"value\": 1}", list)
        .ruleId()).isEqualTo("dev.firebase-rtdb.path-not-allowed");
    assertThat(classify("rtdb_write", "{\"operation\": \"update\", \"path\": \"/\", \"value\": {\"users/a\": 1, "
        + "\"secrets/b\": 2}}", list).ruleId()).isEqualTo("dev.firebase-rtdb.path-not-allowed");
    Classification update = classify("rtdb_write", "{\"operation\": \"update\", \"path\": \"/\", \"value\": "
        + "{\"users/a\": 1, \"public-x/b\": null}}", list);
    assertThat(update.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(update.facts().path("operation").asString()).isEqualTo("update");
    for (String op : List.of("set", "delete")) {
      Classification root = classify("rtdb_write", "{\"operation\": \"" + op + "\", \"path\": \"/\""
          + (op.equals("set") ? ", \"value\": {\"users\": {}}" : "") + "}");
      assertThat(root.ruleId()).as(op).isEqualTo("dev.firebase-rtdb.root-write");
    }
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aReadIsOneGetWithItsQueryEncodedAndTheTokenAlone() throws Exception {
    google.on("/users.json", r -> FakeGoogle.json(200, "{\"u1\": {\"score\": 12, \"name\": \"Ann\"}, \"u2\": "
        + "{\"score\": 9.5, \"big\": 123456789012345678901234}}"));
    TestCtx ctx = TestCtx.of().env(Env.PROD);
    ToolResult r = rtdb.execute(call("rtdb_get", "{\"path\": \"/users\", \"orderBy\": \"score\", \"limitToFirst\": "
        + "500, \"startAt\": \"a b\"}"), config(Env.PROD), secrets, ctx);
    RecordedRequest sent = google.to("/users.json").getFirst();
    assertThat(sent.getMethod()).isEqualTo("GET");
    assertThat(sent.getTarget()).as("limitToFirst capped at the endpoint's rows")
        .isEqualTo("/users.json?orderBy=%22score%22&limitToFirst=100&startAt=%22a%20b%22");
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.userToken);
    assertThat(sent.getHeaders().get("x-goog-user-project")).as("only the token goes to a database").isNull();
    assertThat(ctx.taints).contains(google.userToken);
    JsonNode out = r.structuredContent();
    assertThat(out.path("path").asString()).isEqualTo("/users");
    assertThat(out.path("shallow").asBoolean(true)).isFalse();
    assertThat(out.path("children").asInt()).isEqualTo(2);
    assertThat(out.at("/value/u1/score").isIntegralNumber()).isTrue();
    assertThat(out.at("/value/u2/score").asDouble()).isEqualTo(9.5);
    assertThat(out.at("/value/u2/big").isBigInteger()).isTrue();
    assertThat(r.content().getFirst().text()).startsWith("/users: {\"u1\"")
        .contains("doesn't sort a REST answer");

    google.on("/users/a%20b/%C3%A9.json", req -> FakeGoogle.json(200, "\"ok\""));
    ToolResult primitive = rtdb.execute(call("rtdb_get", "{\"path\": \"/users/a b/é\"}"), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(google.to("/users/a%20b/%C3%A9.json").getFirst().getTarget()).isEqualTo("/users/a%20b/%C3%A9.json");
    assertThat(primitive.structuredContent().path("value").asString()).isEqualTo("ok");
    google.on("/users/nobody.json", req -> FakeGoogle.json(200, "null"));
    assertThat(rtdb.execute(call("rtdb_get", "{\"path\": \"/users/nobody\"}"), config(Env.DEV), secrets,
        TestCtx.of()).content().getFirst().text()).isEqualTo("No data at /users/nobody.");
  }

  @Test
  void theRootIsReadShallowAndOnlyItsListedKeysShow() throws Exception {
    google.on("/.json", r -> FakeGoogle.json(200, "{\"users\": true, \"public-feed\": true, \"secrets\": true}"));
    ToolResult all = rtdb.execute(call("rtdb_get", "{}"), config(Env.DEV), secrets, TestCtx.of());
    assertThat(google.to("/.json").getFirst().getTarget()).isEqualTo("/.json?shallow=true");
    assertThat(all.structuredContent().path("children").asInt()).isEqualTo(3);
    assertThat(all.content().getFirst().text()).startsWith("/ (keys only, 3 children): ");
    ToolResult listed = rtdb.execute(call("rtdb_get", "{\"path\": \"/\"}"), config(Env.DEV, "users", "public-*"),
        secrets, TestCtx.of());
    assertThat(listed.structuredContent().path("value").toString())
        .isEqualTo("{\"users\":true,\"public-feed\":true}");
    assertThat(listed.content().getFirst().text()).doesNotContain("secrets");
  }

  @Test
  void anAnswerLargerThanTheResponseSizeIsRefusedAndLongStringsAreCut() throws Exception {
    StringBuilder big = new StringBuilder("{");
    for (int i = 0; i < 2_000; i++) {
      big.append(i == 0 ? "" : ",").append("\"user-").append(i).append("\": {\"name\": \"someone\"}");
    }
    google.on("/users.json", r -> FakeGoogle.json(200, big.append("}").toString()));
    Limits small = new Limits(100, 100, 32 * 1024, 64 * 1024, 64 * 1024, 64 * 1024, 1_000, Duration.ofSeconds(3),
        Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_get", "{\"path\": \"/users\"}"), config(Env.DEV), secrets,
        TestCtx.of().with(small))).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("The data at /users is larger than 7 KB", "shallow=true",
              "limitToFirst");
        });
    google.on("/bio.json", r -> FakeGoogle.json(200, "{\"text\": \"" + "x".repeat(20_000) + "\"}"));
    Limits cells = new Limits(100, 100, 64 * 1024, 64 * 1024, 64 * 1024, 64 * 1024, 1_000, Duration.ofSeconds(3),
        Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    ToolResult bio = rtdb.execute(call("rtdb_get", "{\"path\": \"/bio\"}"), config(Env.DEV), secrets,
        TestCtx.of().with(cells));
    assertThat(bio.structuredContent().at("/value/text").asString()).hasSize(1_000 + "…‹20000 characters›".length())
        .endsWith("…‹20000 characters›");
  }

  // ── Writes ──────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aWriteRunsOnlyAfterApprovalAndExactlyAsShown() throws Exception {
    google.on("/users", r -> new MockResponse.Builder().code(204).build());
    ConnectionConfig cfg = config(Env.DEV, "users");
    ToolCall set = call("rtdb_write", "{\"operation\": \"set\", \"path\": \"/users/alice\", \"value\": {\"name\": "
        + "\"Alice\", \"seen\": {\".sv\": \"timestamp\"}}, \"note\": \"New user\"}");
    Classification c = rtdb.classify(set, cfg, policy(Env.DEV));
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.facts().path("path").asString()).isEqualTo("/users/alice");
    ActionPreview preview = rtdb.describeAction(set, cfg);
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(preview.text()).isEqualTo("PUT " + URL + "/users/alice.json?print=silent\n"
        + "Authorization: Bearer ‹masked›\nContent-Type: application/json; charset=utf-8\n\n{\n  \"name\" : "
        + "\"Alice\",\n  \"seen\" : {\n    \".sv\" : \"timestamp\"\n  }\n}");
    assertThat(preview.payload().toString()).isEqualTo("{\"operation\":\"set\",\"path\":\"/users/alice\","
        + "\"value\":{\"name\":\"Alice\",\"seen\":{\".sv\":\"timestamp\"}}}");
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("replaced"));

    assertThatThrownBy(() -> rtdb.execute(set, cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(google.api()).isEmpty();

    // The call's own arguments say something else: what runs is what the person approved.
    ToolCall other = call("rtdb_write", "{\"operation\": \"delete\", \"path\": \"/users/bob\"}");
    ToolResult done = rtdb.execute(other, cfg, secrets, TestCtx.of().approved(preview.payload()));
    RecordedRequest sent = google.api().getLast();
    assertThat(sent.getMethod()).isEqualTo("PUT");
    assertThat(sent.getTarget()).isEqualTo("/users/alice.json?print=silent");
    assertThat(sent.getBody().utf8()).isEqualTo(preview.text().substring(preview.text().indexOf("\n\n") + 2));
    assertThat(done.content().getFirst().text()).isEqualTo("Set /users/alice.");

    ActionPreview update = rtdb.describeAction(call("rtdb_write", "{\"operation\": \"update\", \"path\": "
        + "\"/users/alice\", \"value\": {\"name\": \"Ann\", \"profile/city\": \"Berlin\", \"old\": null}}"), cfg);
    rtdb.execute(other, cfg, secrets, TestCtx.of().approved(update.payload()));
    sent = google.api().getLast();
    assertThat(sent.getMethod()).isEqualTo("PATCH");
    assertThat(FakeGoogle.body(sent).toString()).isEqualTo("{\"name\":\"Ann\",\"profile/city\":\"Berlin\","
        + "\"old\":null}");

    ActionPreview delete = rtdb.describeAction(other, cfg);
    assertThat(delete.text()).isEqualTo("DELETE " + URL + "/users/bob.json?print=silent\n"
        + "Authorization: Bearer ‹masked›\n");
    ToolResult deleted = rtdb.execute(other, cfg, secrets, TestCtx.of().approved(delete.payload()));
    sent = google.api().getLast();
    assertThat(sent.getMethod()).isEqualTo("DELETE");
    assertThat(sent.getBodySize()).isZero();
    assertThat(deleted.content().getFirst().text()).isEqualTo("Deleted /users/bob.");
  }

  @Test
  void anApprovedWriteIsReadBackAndALostAnswerIsUnknown() {
    ConnectionConfig cfg = config(Env.DEV, "users");
    for (String tampered : List.of("{\"operation\": \"set\", \"path\": \"/secrets/x\", \"value\": 1}",
        "{\"operation\": \"delete\", \"path\": \"/\"}",
        "{\"operation\": \"set\", \"path\": \"/users/x\", \"value\": 1, \"method\": \"POST\"}",
        "{\"operation\": \"set\", \"path\": \"/users/x\", \"value\": {\"a#\": 1}}",
        "{\"operation\": \"update\", \"path\": \"/users\", \"value\": {\"x\": 1, \"../secrets\": 2}}",
        "{\"operation\": \"set\", \"path\": \"users/x\", \"value\": 1}")) {
      assertThatThrownBy(() -> rtdb.execute(call("rtdb_write", "{}"), cfg, secrets,
          TestCtx.of().approved(TestCtx.JSON.readTree(tampered)))).as(tampered)
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThat(google.api()).isEmpty();

    JsonNode payload = rtdb.describeAction(call("rtdb_write", "{\"operation\": \"set\", \"path\": \"/users/x\", "
        + "\"value\": 1}"), cfg).payload();
    google.on("/users/x.json", r -> FakeGoogle.json(500, "{\"error\": \"Internal server error.\"}"));
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_write", "{}"), cfg, secrets, TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    google.on("/users/x.json", r -> new MockResponse.Builder().onResponseStart(
        SocketEffect.ShutdownConnection.INSTANCE).build());
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_write", "{}"), cfg, secrets, TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    assertThat(google.to("/users/x.json")).as("never retried").hasSize(2);
  }

  @Test
  void aStalledAnswerIsATimeoutForAReadAndUnknownForAChange() {
    ConnectionConfig cfg = config(Env.DEV, "users");
    google.on("/users", r -> new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_get", "{\"path\": \"/users/a\"}"), cfg, secrets,
        TestCtx.of().timeout(Duration.ofSeconds(2)))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    JsonNode payload = rtdb.describeAction(call("rtdb_write", "{\"operation\": \"update\", \"path\": \"/users/a\", "
        + "\"value\": {\"n\": 1}}"), cfg).payload();
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_write", "{}"), cfg, secrets, TestCtx.of()
        .timeout(Duration.ofSeconds(2)).approved(payload))).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).contains("whether it ran is not known");
        });
  }

  @Test
  void aRedirectIsNeverFollowedAndAReadInApprovalRunsTheApprovedArguments() throws Exception {
    google.on("/users.json", r -> new MockResponse.Builder().code(307).addHeader("Location", "/stolen.json").build());
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_get", "{\"path\": \"/users\"}"), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("answered 307"));
    assertThat(google.to("/stolen.json")).isEmpty();

    google.on("/public.json", r -> FakeGoogle.json(200, "{\"a\": 1}"));
    ToolCall approved = call("rtdb_get", "{\"path\": \"/public\"}");
    ActionPreview preview = rtdb.describeAction(approved, config(Env.PROD));
    assertThat(preview.payload().toString()).isEqualTo("{\"tool\":\"rtdb_get\",\"arguments\":{\"path\":"
        + "\"/public\"}}");
    ToolResult r = rtdb.execute(call("rtdb_get", "{\"path\": \"/users\"}"), config(Env.PROD), secrets,
        TestCtx.of().env(Env.PROD).approved(preview.payload()));
    assertThat(r.structuredContent().path("path").asString()).isEqualTo("/public");
    assertThat(google.to("/users.json")).hasSize(1);
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_write", "{}"), config(Env.PROD), secrets,
        TestCtx.of().env(Env.PROD).approved(preview.payload()))).isInstanceOf(ConnectorException.class);
  }

  @Test
  void theDatabasesRefusalsSayWhatToDo() {
    ConnectionConfig cfg = config(Env.DEV);
    google.on("/users.json", r -> FakeGoogle.json(400, "{\"error\": \"Index not defined, add \\\".indexOn\\\": "
        + "\\\"score\\\", for path \\\"/users\\\", to the rules\"}"));
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_get", "{\"path\": \"/users\", \"orderBy\": \"score\"}"), cfg,
        secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains(".indexOn", "$key");
        });
    google.on("/users.json", r -> FakeGoogle.json(401, "{\"error\": \"Unauthorized request.\"}"));
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_get", "{\"path\": \"/users\"}"), cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("keeps the scopes it was given", RtdbApi.FIREBASE_DATABASE)
              .doesNotContain("..");
        });
    google.on("/users.json", r -> FakeGoogle.json(404, "{\"error\": \"Firebase error. Please ensure that you "
        + "spelled the name of your Firebase correctly\"}"));
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_get", "{\"path\": \"/users\"}"), cfg, secrets, TestCtx.of()))
        .hasMessageContaining("There is no database at " + URL);
    google.on("/users.json", r -> FakeGoogle.json(200, "{\"a\": "));
    assertThatThrownBy(() -> rtdb.execute(call("rtdb_get", "{\"path\": \"/users\"}"), cfg, secrets, TestCtx.of()))
        .hasMessageContaining("isn't JSON");
  }

  // ── Credentials and Test connection ─────────────────────────────────────────────────────────────────────────

  @Test
  void aKeyAndAnImpersonatedServiceAccountGetTheDatabasesScopes() throws Exception {
    google.on("/.json", r -> FakeGoogle.json(200, "{\"users\": true}"));
    TestResult impersonated = rtdb.test(configWith(Env.PROD, "{\"source\": \"adc\", \"impersonate\": \""
        + FakeGoogle.SA + "\"}"), secrets, TestCtx.of().env(Env.PROD));
    assertThat(impersonated.ok()).as(String.valueOf(impersonated.error())).isTrue();
    assertThat(impersonated.identity()).isEqualTo(FakeGoogle.SA);
    JsonNode iam = FakeGoogle.body(google.requests.stream().filter(q -> q.getUrl().encodedPath()
        .endsWith(":generateAccessToken")).findFirst().orElseThrow());
    assertThat(iam.path("scope").toString()).isEqualTo("[\"" + RtdbApi.FIREBASE_DATABASE + "\",\""
        + RtdbApi.USERINFO_EMAIL + "\"]");
    assertThat(google.to("/.json").getLast().getHeaders().get("Authorization"))
        .isEqualTo("Bearer " + google.impersonatedToken);

    TestSecrets key = new TestSecrets().with("/credentials/serviceAccountKey", FakeGoogle.serviceAccountKey());
    TestResult withKey = rtdb.test(configWith(Env.DEV, "{\"source\": \"serviceAccountKey\"}"), key, TestCtx.of());
    assertThat(withKey.identity()).isEqualTo(FakeGoogle.KEY_SA);
    RecordedRequest token = google.requests.stream().filter(q -> q.getUrl().encodedPath().equals("/token")
        && q.getBody().utf8().contains("jwt-bearer")).findFirst().orElseThrow();
    assertThat(FakeGoogle.assertion(token).path("scope").asString())
        .isEqualTo(RtdbApi.FIREBASE_DATABASE + " " + RtdbApi.USERINFO_EMAIL);
  }

  @Test
  void aDatabaseTokenGoesNowhereButADatabaseHost() {
    try (CloudCredentials real = new CloudCredentials(google.endpoints(false), Clock.systemUTC());
        RtdbConnector c = new RtdbConnector(real)) {
      assertThatThrownBy(() -> c.execute(call("rtdb_get", "{\"path\": \"/users\"}"), config(Env.DEV), secrets,
          TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).contains("only to Google's own hosts");
          });
      assertThat(c.test(config(Env.DEV), secrets, TestCtx.of()).error().code()).isEqualTo("rtdb.credentials");
    }
    assertThat(google.requests).isEmpty();
  }

  @Test
  void testConnectionNamesTheIdentityAndTheTopLevelPaths() {
    google.on("/.json", r -> FakeGoogle.json(200, "{\"users\": true, \"secrets\": true, \"public-feed\": true}"));
    TestResult r = rtdb.test(config(Env.PROD, "users", "public-*"), secrets, TestCtx.of().env(Env.PROD));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("alice@example.com");
    assertThat(r.serverVersion()).isEqualTo("Firebase Realtime Database · acme-default-rtdb.firebaseio.com");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("identity", "data");
    assertThat(r.checks().get(1).detail()).isEqualTo("2 top-level paths agents may use: users, public-feed");
    assertThat(google.to("/.json").getFirst().getTarget()).isEqualTo("/.json?shallow=true");
    assertThat(r.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("rtdb.long-lived-credentials");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });

    TestResult none = rtdb.test(config(Env.DEV, "orders"), secrets, TestCtx.of());
    assertThat(none.hints()).extracting(TestResult.Hint::code).containsExactly("rtdb.no-paths");

    google.on("/.json", req -> FakeGoogle.json(401, "{\"error\": \"Unauthorized request.\"}"));
    TestResult refused = rtdb.test(config(Env.DEV), secrets, TestCtx.of());
    assertThat(refused.ok()).isFalse();
    assertThat(refused.error().message()).contains("keeps the scopes it was given");
    assertThat(refused.checks()).extracting(TestResult.Check::ok).containsExactly(true, false);

    StringBuilder many = new StringBuilder("{");
    for (int i = 0; i < 10_000; i++) {
      many.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": true");
    }
    google.on("/.json", req -> FakeGoogle.json(200, many.append("}").toString()));
    TestResult large = rtdb.test(config(Env.DEV), secrets, TestCtx.of());
    assertThat(large.ok()).isTrue();
    assertThat(large.checks().getLast().detail()).isEqualTo("More top-level paths than Test connection reads");
    assertThat(rtdb.health(config(Env.DEV), secrets, TestCtx.of()).state()).isNotEqualTo(
        HealthResult.State.UNREACHABLE);
  }
}
