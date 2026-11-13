package com.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * {@code gcp_storage_read} against fakes of Cloud Storage and Resource Manager: the bucket is proven to belong to the
 * connection and to lie in an allowed location before any byte is read, only text is read, never more than asked or
 * than the call may return, cut at a whole UTF-8 character, and files that hold keys are refused by name.
 */
class GcpStorageReadTest {

  private static final String OBJECT_PATH = "/storage/v1/b/acme-stage-uploads/o/reports%2Fq3.csv";
  private FakeGoogle google;
  private GcpConnector connector;
  private final ConnectionConfig cfg = Configs.stage();
  private final AtomicReference<String> contentType = new AtomicReference<>("text/csv");
  private final AtomicReference<byte[]> content = new AtomicReference<>(
      "day,orders\n2026-10-01,42\n".getBytes(StandardCharsets.UTF_8));

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    google.on("/storage/v1/b/acme-stage-uploads", r -> FakeGoogle.json(200, """
        {"projectNumber":"123456789012","location":"EUROPE-WEST1"}"""));
    google.on("/v1/projects/acme-stage", r -> FakeGoogle.json(200, "{\"projectNumber\":\"123456789012\"}"));
    google.on(OBJECT_PATH, this::object);
    connector = new GcpConnector(google.cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    connector.close();
    google.close();
  }

  private MockResponse object(RecordedRequest r) {
    byte[] all = content.get();
    if (!"media".equals(FakeGoogle.param(r, "alt"))) {
      return FakeGoogle.json(200, """
          {"name":"reports/q3.csv","contentType":"%s","size":"%d","generation":"1730000000000001"}"""
          .formatted(contentType.get(), all.length));
    }
    String range = r.getHeaders().get("Range");
    String[] bounds = range.substring("bytes=".length()).split("-");
    int from = Integer.parseInt(bounds[0]);
    int to = Math.min(Integer.parseInt(bounds[1]), all.length - 1);
    byte[] part = java.util.Arrays.copyOfRange(all, from, to + 1);
    return new MockResponse.Builder().code(206).addHeader("Content-Type", contentType.get())
        .addHeader("Content-Range", "bytes " + from + "-" + to + "/" + all.length).body(new okio.Buffer().write(part))
        .build();
  }

  private ToolResult read(String arguments, PolicyView policy) throws ConnectorException {
    ToolCall call = Configs.call("gcp_storage_read", cfg.name(), arguments);
    Classification c = connector.classify(call, cfg, policy);
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, new TestSecrets(), TestCtx.of());
  }

  private ToolResult read(String arguments) throws ConnectorException {
    return read(arguments, Configs.policy(Env.STAGE));
  }

  @Test
  void readsTextOfABucketOfTheConnection() throws Exception {
    ToolResult result = OutputSchema.storageRead(read("""
        {"bucket":"acme-stage-uploads","object":"reports/q3.csv"}"""));

    JsonNode s = result.structuredContent();
    assertThat(s.path("text").asString()).isEqualTo("day,orders\n2026-10-01,42\n");
    assertThat(s.path("truncated").asBoolean()).isFalse();
    assertThat(s.has("nextOffset")).isFalse();
    assertThat(s.path("contentType").asString()).isEqualTo("text/csv");
    RecordedRequest media = google.to(OBJECT_PATH).stream().filter(r -> "media".equals(FakeGoogle.param(r, "alt")))
        .findFirst().orElseThrow();
    assertThat(media.getHeaders().get("Range")).isEqualTo("bytes=0-24");
    assertThat(media.getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
  }

  @Test
  void readsAPartAndCutsAtAWholeCharacter() throws Exception {
    content.set("héllo wörld".getBytes(StandardCharsets.UTF_8));

    JsonNode s = read("""
        {"bucket":"acme-stage-uploads","object":"reports/q3.csv","maxBytes":2}""").structuredContent();

    assertThat(s.path("text").asString()).isEqualTo("h");
    assertThat(s.path("bytes").asInt()).isEqualTo(1);
    assertThat(s.path("truncated").asBoolean()).isTrue();
    assertThat(s.path("nextOffset").asLong()).isEqualTo(1);
    JsonNode next = read("""
        {"bucket":"acme-stage-uploads","object":"reports/q3.csv","offset":1,"maxBytes":20}""").structuredContent();
    assertThat(next.path("text").asString()).isEqualTo("éllo wörld");
    assertThat(next.path("truncated").asBoolean()).isFalse();
  }

  @Test
  void binaryContentIsRefusedBeforeItIsRead() {
    contentType.set("image/png");

    assertThatThrownBy(() -> read("""
        {"bucket":"acme-stage-uploads","object":"reports/q3.csv"}"""))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("not text");
    assertThat(google.to(OBJECT_PATH)).noneMatch(r -> "media".equals(FakeGoogle.param(r, "alt")));
  }

  @Test
  void aBucketOutsideTheAllowedLocationsIsRefusedBeforeAnyObjectRequest() {
    assertThatThrownBy(() -> read("""
        {"bucket":"acme-stage-uploads","object":"reports/q3.csv"}""",
        Configs.policy(Env.STAGE, "{\"regions\":[\"us-central1\"]}")))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("europe-west1");
        });
    assertThat(google.to(OBJECT_PATH)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"config/.env", ".env.production", "infra/prod.tfstate", "certs/server.pem", "keys/api.key",
      "home/.netrc", "ssh/id_ed25519", "deploy/credentials.json", "app.p12"})
  void filesThatHoldKeysAreRefusedByName(String object) {
    Classification c = connector.classify(Configs.call("gcp_storage_read", cfg.name(), """
        {"bucket":"acme-stage-uploads","object":"%s"}""".formatted(object)), cfg, Configs.policy(Env.STAGE));

    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("stage.cloud.object_not_allowed");
  }

  @Test
  void resourceMasksApplyToTheObjectsFullName() {
    PolicyView policy = Configs.policy(Env.STAGE, """
        {"resources":["//storage.googleapis.com/acme-stage-uploads/objects/public/*"]}""");

    Classification denied = connector.classify(Configs.call("gcp_storage_read", cfg.name(), """
        {"bucket":"acme-stage-uploads","object":"reports/q3.csv"}"""), cfg, policy);
    Classification allowed = connector.classify(Configs.call("gcp_storage_read", cfg.name(), """
        {"bucket":"acme-stage-uploads","object":"public/readme.txt"}"""), cfg, policy);

    assertThat(denied.ruleId()).isEqualTo("stage.cloud.resource_not_allowed");
    assertThat(allowed.kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void utf8CutsAndBinaryDetection() {
    assertThat(StorageRead.completeUtf8(new byte[] {'a', (byte) 0xC3})).isEqualTo(1);
    assertThat(StorageRead.completeUtf8("é".getBytes(StandardCharsets.UTF_8))).isEqualTo(2);
    assertThat(StorageRead.completeUtf8(new byte[] {(byte) 0xE2, (byte) 0x82})).isZero();
    assertThat(StorageRead.binary("a\u0000b")).isTrue();
    assertThat(StorageRead.binary("line\tvalue\r\n")).isFalse();
  }
}
