package ai.msrv.gaterook.connectors.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The S3 connector against moto 5.2.4's S3 and STS (test-services, profile {@code s3}; run with {@code -Pservices}):
 * as another provider's endpoint (path style, an access key) and as Amazon S3 through a development build's test
 * endpoint (with and without a role). Every tool runs as an agent calls it, the changes after the approval of their
 * exact payload. moto checks no signature and enforces no session policy: signatures are checked against AWS's
 * published examples and the policies' contents against the fake in {@link S3ConnectorTest}. The buckets carry a
 * random suffix and are removed afterwards; moto's state is never reset, so that other test runs keep theirs.
 */
@Tag("cloud-it")
class MotoS3IT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TestSecrets SECRETS = new TestSecrets().with("/credentials/secretAccessKey", "moto-secret");
  private static final String BUCKET = "gw-s3-it-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
  private static final String ARCHIVE = BUCKET + "-archive";
  private static URI base;
  private static CloudCredentials cloud;
  private static S3Connector s3;
  private static ConnectionConfig custom;

  @BeforeAll
  static void buckets() throws Exception {
    assumeTrue(TestServices.enabled(), "moto runs in the Compose services: scripts/test-services.sh up s3, then "
        + "-Pservices");
    base = URI.create("http://127.0.0.1:" + TestServices.port("GW_MOTO_PORT"));
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null, Map.of("aws.s3", base)),
        Clock.systemUTC());
    s3 = new S3Connector(cloud);
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("endpoint", base.toString()).put("region", "us-east-1");
    c.putObject("credentials").put("accessKeyId", "moto-access-key");
    c.putArray("buckets").add(BUCKET).add(ARCHIVE);
    custom = new ConnectionConfig("conn-moto", "moto-s3", "s3", Env.DEV, 1, c, Set.of("/credentials/secretAccessKey"),
        null);
    for (String b : new String[] {BUCKET, ARCHIVE}) {
      expect(raw(S3Request.of("CreateBucket", "PUT", b, null)), 200);
    }
    expect(raw(S3Request.of("PutObject", "PUT", BUCKET, "img/logo.png").header("content-type", "image/png")
        .withBody(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13})), 200);
  }

  @AfterAll
  static void cleanUp() throws Exception {
    if (s3 == null) {
      return;
    }
    for (String b : new String[] {BUCKET, ARCHIVE}) {
      S3Api.Answer list = raw(S3Request.of("ListObjectsV2", "GET", b, null).param("list-type", "2"));
      for (org.w3c.dom.Element o : S3Xml.children(S3Xml.parse(list.body()), "Contents")) {
        raw(S3Request.of("DeleteObject", "DELETE", b, S3Xml.text(o, "Key")));
      }
      raw(S3Request.of("DeleteBucket", "DELETE", b, null));
    }
    s3.close();
    cloud.close();
  }

  /** A request the tools don't offer (buckets, a binary object), sent the connector's way. */
  private static S3Api.Answer raw(S3Request r) throws ConnectorException {
    return new S3Api(cloud).send(new S3Api.Call(custom, S3Settings.of(custom), SECRETS, TestCtx.of()), r, null,
        Reads.MAX_LIST);
  }

  private static void expect(S3Api.Answer a, int status) {
    assertThat(a.status()).as(new String(a.body(), StandardCharsets.UTF_8)).isEqualTo(status);
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_moto", tool, "moto-s3", (ObjectNode) JSON.readTree(args), null);
  }

  private static ToolResult run(ConnectionConfig cfg, String tool, String args) throws ConnectorException {
    return s3.execute(call(tool, args), cfg, SECRETS, TestCtx.of());
  }

  /** A change as the core runs it: its exact payload approved, then executed. */
  private static ToolResult approved(ConnectionConfig cfg, String tool, String args) throws ConnectorException {
    JsonNode payload = s3.prepare(call(tool, args), cfg, SECRETS, TestCtx.of()).preview().payload();
    return s3.execute(call(tool, args), cfg, SECRETS, TestCtx.of().approved(payload));
  }

  @Test
  void everyToolOnAnS3CompatibleEndpoint() throws Exception {
    assertThat(run(custom, "s3_list_buckets", "{}").structuredContent().path("buckets").toString())
        .contains(BUCKET, ARCHIVE);
    String csv = "name,total\nPrüfung,12.5\nok,3\n";
    ToolResult put = approved(custom, "s3_put_object", JSON.writeValueAsString(Map.of("bucket", BUCKET, "key",
        "reports/2026/q3 final.csv", "content", csv, "contentType", "text/csv; charset=utf-8", "metadata",
        Map.of("source", "agent", "api-token", "tok-moto"))));
    assertThat(put.structuredContent().path("changed").asBoolean()).isTrue();
    approved(custom, "s3_put_object", JSON.writeValueAsString(Map.of("bucket", BUCKET, "key", "reports/readme.md",
        "content", "# Reports")));
    assertThatThrownBy(() -> approved(custom, "s3_put_object", JSON.writeValueAsString(Map.of("bucket", BUCKET,
        "key", "reports/readme.md", "content", "again", "overwrite", false))))
        .as("overwrite false keeps the object").isInstanceOf(ConnectorException.class);

    JsonNode top = run(custom, "s3_list_objects", "{\"bucket\": \"" + BUCKET + "\", \"prefix\": \"reports/\"}")
        .structuredContent();
    assertThat(top.path("prefixes").toString()).isEqualTo("[\"reports/2026/\"]");
    assertThat(top.path("objects").get(0).path("key").asString()).isEqualTo("reports/readme.md");
    JsonNode page = run(custom, "s3_list_objects", "{\"bucket\": \"" + BUCKET + "\", \"delimiter\": \"\", "
        + "\"limit\": 1}").structuredContent();
    assertThat(page.path("count").asInt()).isEqualTo(1);
    JsonNode next = run(custom, "s3_list_objects", "{\"bucket\": \"" + BUCKET + "\", \"delimiter\": \"\", "
        + "\"limit\": 1, \"cursor\": \"" + page.path("nextCursor").asString() + "\"}").structuredContent();
    assertThat(next.path("objects").get(0).path("key").asString())
        .isNotEqualTo(page.path("objects").get(0).path("key").asString());

    JsonNode head = run(custom, "s3_head_object", "{\"bucket\": \"" + BUCKET + "\", \"key\": \"reports/2026/q3 "
        + "final.csv\"}").structuredContent();
    assertThat(head.path("size").asLong()).isEqualTo(csv.getBytes(StandardCharsets.UTF_8).length);
    assertThat(head.path("metadata").path("source").asString()).isEqualTo("agent");
    assertThat(head.path("metadata").path("api-token").asString()).isEqualTo("‹masked›");

    ToolResult part = run(custom, "s3_read_object", "{\"bucket\": \"" + BUCKET + "\", \"key\": \"reports/2026/q3 "
        + "final.csv\", \"maxBytes\": 13}");
    assertThat(part.structuredContent().path("text").asString()).isEqualTo("name,total\nPr");
    long offset = part.structuredContent().path("nextOffset").asLong();
    assertThat(run(custom, "s3_read_object", "{\"bucket\": \"" + BUCKET + "\", \"key\": \"reports/2026/q3 final.csv\","
        + " \"offset\": " + offset + "}").structuredContent().path("text").asString()).isEqualTo("üfung,12.5\nok,3\n");
    assertThatThrownBy(() -> run(custom, "s3_read_object", "{\"bucket\": \"" + BUCKET + "\", \"key\": "
        + "\"img/logo.png\"}")).hasMessageContaining("image/png").hasMessageContaining("8 bytes");

    approved(custom, "s3_copy_object", JSON.writeValueAsString(Map.of("sourceBucket", BUCKET, "sourceKey",
        "reports/2026/q3 final.csv", "bucket", ARCHIVE, "key", "2026/q3.csv")));
    assertThat(run(custom, "s3_head_object", "{\"bucket\": \"" + ARCHIVE + "\", \"key\": \"2026/q3.csv\"}")
        .structuredContent().path("size").asLong()).isEqualTo(head.path("size").asLong());
    approved(custom, "s3_delete_object", "{\"bucket\": \"" + ARCHIVE + "\", \"key\": \"2026/q3.csv\"}");
    assertThatThrownBy(() -> run(custom, "s3_head_object", "{\"bucket\": \"" + ARCHIVE + "\", \"key\": "
        + "\"2026/q3.csv\"}")).hasMessageContaining("There is no object 2026/q3.csv");

    TestResult t = s3.test(custom, SECRETS, TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.checks().getLast().detail()).contains(BUCKET);
  }

  @Test
  void amazonS3WithAndWithoutARole() throws Exception {
    for (boolean role : new boolean[] {false, true}) {
      ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "us-east-1").put("pathStyle", true);
      ObjectNode credentials = c.putObject("credentials").put("source", "accessKey")
          .put("accessKeyId", "AKIAIOSFODNN7EXAMPLE");
      if (role) {
        credentials.put("roleArn", "arn:aws:iam::123456789012:role/gw-s3").put("externalId", "gw-ext-0123456789ab");
      }
      ConnectionConfig cfg = new ConnectionConfig("conn-moto-aws-" + role, "moto-aws", "s3", Env.DEV, 1, c,
          Set.of("/credentials/secretAccessKey"), null);
      TestResult t = s3.test(cfg, SECRETS, TestCtx.of());
      assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
      assertThat(t.identity()).contains(role ? "assumed-role/gw-s3" : "arn:aws:");
      // Without a bucket list, requests carry the account STS names as the expected owner.
      assertThat(run(cfg, "s3_list_objects", "{\"bucket\": \"" + BUCKET + "\"}").structuredContent().path("count")
          .asInt()).isGreaterThanOrEqualTo(0);
    }
  }
}
