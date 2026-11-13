package com.gaterook.connectors.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The S3 connector against a fake of S3's REST API and STS, through the core's guarded egress: what is sent, how it is
 * signed (every request the fake receives passes {@link SigV4Check}), what comes back, and what is refused before
 * anything is sent.
 */
class S3ConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-s3";
  private static final String KEY_SECRET = "test-only-secret-key";
  private static final String SESSION_SECRET = "session-secret";
  private static final String NS = "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"";

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> sts = new CopyOnWriteArrayList<>();
  private final List<RecordedRequest> s3 = new CopyOnWriteArrayList<>();
  private final List<Route> routes = new CopyOnWriteArrayList<>();
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", KEY_SECRET);
  private CloudCredentials cloud;
  private S3Connector connector;
  private int port;

  private record Route(String method, String bucket, String key, Function<RecordedRequest, MockResponse> answer) {}

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest r) {
        String body = r.getBody() == null ? "" : r.getBody().utf8();
        if (body.contains("Action=AssumeRole")) {
          sts.add(r);
          return xml(200, "<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
              + "<Credentials><AccessKeyId>ASIATESTSESSION00001</AccessKeyId><SecretAccessKey>" + SESSION_SECRET
              + "</SecretAccessKey><SessionToken>session-token</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
              + "</Credentials><AssumedRoleUser><AssumedRoleId>AROATEST:gaterook</AssumedRoleId><Arn>"
              + "arn:aws:sts::412398765012:assumed-role/gw-s3/gaterook</Arn></AssumedRoleUser></AssumeRoleResult>"
              + "<ResponseMetadata><RequestId>1</RequestId></ResponseMetadata></AssumeRoleResponse>");
        }
        if (body.contains("Action=GetCallerIdentity")) {
          sts.add(r);
          boolean session = String.valueOf(r.getHeaders().get("Authorization")).contains("Credential=ASIA");
          return xml(200, "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<GetCallerIdentityResult><Arn>" + (session ? "arn:aws:sts::412398765012:assumed-role/gw-s3/gaterook"
              : "arn:aws:iam::412398765012:user/reader") + "</Arn><UserId>AIDATEST</UserId><Account>412398765012"
              + "</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>2</RequestId></ResponseMetadata>"
              + "</GetCallerIdentityResponse>");
        }
        s3.add(r);
        String[] at = place(r);
        for (Route route : routes) {
          if (route.method().equals(r.getMethod()) && route.bucket().equals(at[0])
              && (route.key() == null ? at[1] == null : route.key().equals(at[1]))) {
            return route.answer().apply(r);
          }
        }
        return r.getMethod().equals("HEAD") ? new MockResponse.Builder().code(404).build()
            : xml(404, "<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    port = server.getPort();
    URI base = URI.create("http://127.0.0.1:" + port);
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null,
        Map.of("aws.s3", URI.create("http://s3.mock.test:" + port))), Clock.systemUTC());
    connector = new S3Connector(cloud);
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  /** Bucket and key of a request: from the host (virtual-hosted style) or the path (path style); "" for none. */
  private static String[] place(RecordedRequest r) {
    String host = r.getHeaders().get("Host");
    String target = r.getTarget();
    String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
    String decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
    if (host != null && host.contains(".s3.mock.test")) {
      String bucket = host.substring(0, host.indexOf(".s3.mock.test"));
      return new String[] {bucket, decoded.length() <= 1 ? null : decoded.substring(1)};
    }
    String rest = decoded.startsWith("/") ? decoded.substring(1) : decoded;
    if (rest.isEmpty()) {
      return new String[] {"", null};
    }
    int slash = rest.indexOf('/');
    return slash < 0 ? new String[] {rest, null} : new String[] {rest.substring(0, slash), rest.substring(slash + 1)};
  }

  private void on(String method, String bucket, String key, Function<RecordedRequest, MockResponse> answer) {
    routes.add(new Route(method, bucket, key, answer));
  }

  private static MockResponse xml(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/xml").body(body).build();
  }

  /** The S3 requests the fake received, each checked as S3 checks a signature. */
  private List<RecordedRequest> sent(String secret) {
    for (RecordedRequest r : s3) {
      SigV4Check.Signed signed = SigV4Check.verify(r, secret);
      assertThat(signed.service()).isEqualTo("s3");
      assertThat(r.getTarget()).as("never a presigned URL").doesNotContain("X-Amz-Signature", "X-Amz-Credential");
    }
    return s3;
  }

  private static String query(RecordedRequest r, String name) {
    return r.getUrl().queryParameter(name);
  }

  // ── Configurations ──────────────────────────────────────────────────────────────────────────────────────────

  private static ConnectionConfig aws(Env env, boolean role, String... buckets) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "eu-central-1");
    ObjectNode credentials = c.putObject("credentials").put("source", "accessKey")
        .put("accessKeyId", "AKIAIOSFODNN7EXAMPLE");
    if (role) {
      credentials.put("roleArn", ROLE).put("externalId", "gw-ext-0123456789ab");
    }
    list(c, buckets);
    return new ConnectionConfig("conn-s3-" + env.id(), env.id() + "-s3", "s3", env, 1, c,
        Set.of("/credentials/secretAccessKey"), null);
  }

  private ConnectionConfig custom(String endpoint, String region, Boolean pathStyle, String... buckets) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("endpoint", endpoint).put("region", region);
    if (pathStyle != null) {
      c.put("pathStyle", pathStyle);
    }
    c.putObject("credentials").put("accessKeyId", "r2-key-0123456789abcdef0123456789abcd");
    list(c, buckets);
    return new ConnectionConfig("conn-r2", "dev-r2", "s3", Env.DEV, 1, c, Set.of("/credentials/secretAccessKey"),
        null);
  }

  private static void list(ObjectNode c, String... buckets) {
    if (buckets.length > 0) {
      ArrayNode list = c.putArray("buckets");
      for (String b : buckets) {
        list.add(b);
      }
    }
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".s3", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-s3", (ObjectNode) JSON.readTree(args),
        null);
  }

  private Classification classify(ConnectionConfig cfg, String tool, String args) {
    return connector.classify(call(tool, args), cfg, policy(cfg.env()));
  }

  private static String policyOf(RecordedRequest assumeRole) {
    for (String pair : assumeRole.getBody().utf8().split("&")) {
      if (pair.startsWith("Policy=")) {
        return URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private List<RecordedRequest> assumeRoles() {
    return sts.stream().filter(r -> r.getBody().utf8().contains("Action=AssumeRole")).toList();
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesReadsAsReadOnlyAndChangesForApproval() {
    assertThat(connector.manifest().id()).isEqualTo("s3");
    assertThat(connector.manifest().group()).isEqualTo("Cloud");
    assertThat(connector.manifest().tools()).extracting(ToolDefinition::name).containsExactly("s3_list_buckets",
        "s3_list_objects", "s3_head_object", "s3_read_object", "s3_put_object", "s3_copy_object", "s3_delete_object");
    for (ToolDefinition t : connector.manifest().tools()) {
      boolean change = S3Tools.CHANGES.contains(t.name());
      assertThat(t.mutating()).as(t.name()).isEqualTo(change);
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(change ? ToolDefinition.Mode.APPROVAL
          : ToolDefinition.Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(t.defaultMode());
      assertThat(t.readOnlyModeAllowed()).as(t.name()).isEqualTo(!change);
      assertThat(t.outputSchema()).as(t.name()).isNull();
    }
  }

  @Test
  void bucketsKeysAndNamesAreCheckedBeforeAnyRequest() {
    ConnectionConfig cfg = aws(Env.DEV, false, "acme-*");
    assertThat(classify(cfg, "s3_list_objects", "{\"bucket\": \"acme-reports\"}").kind())
        .isEqualTo(Classification.Kind.READ);
    Classification other = classify(cfg, "s3_head_object", "{\"bucket\": \"payroll\", \"key\": \"a.csv\"}");
    assertThat(other.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(other.ruleId()).isEqualTo("dev.s3.bucket-not-allowed");
    for (String key : List.of("a/../b.csv", "./x", "x/.", "a/..;jsessionid=1/b", "back\\slash", "tab\there",
        "c1\u0085x", "lone\ud800surrogate", "")) {
      Classification c = classify(cfg, "s3_head_object", JSON.writeValueAsString(Map.of("bucket", "acme-reports",
          "key", key)));
      assertThat(c.kind()).as(key).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(key).isEqualTo("dev.s3.bad-argument");
    }
    assertThat(classify(cfg, "s3_head_object", "{\"bucket\": \"acme-reports\", \"key\": \"a b/ü+&=.csv\"}").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify(cfg, "s3_list_buckets", "{\"prefix\": \"acme/../x\"}").ruleId()).isEqualTo("dev.s3.bad-argument");
    assertThat(classify(cfg, "s3_list_objects", "{\"bucket\": \"acme-reports\", \"prefix\": \"a\\u0000b\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    // Amazon S3's own rules for bucket names, and its directory buckets.
    for (String bucket : List.of("Acme-Reports", "acme_reports", "acme..reports", "acme-a--use1-az4--x-s3")) {
      assertThat(classify(aws(Env.DEV, false), "s3_list_objects", "{\"bucket\": \"" + bucket + "\"}").kind())
          .as(bucket).isEqualTo(Classification.Kind.DENY);
    }
    assertThat(classify(custom("https://objectstorage.mock.test", "us-ashburn-1", null), "s3_list_objects",
        "{\"bucket\": \"Acme_Reports\"}").kind()).as("OCI allows upper case").isEqualTo(Classification.Kind.READ);
    Classification secret = classify(cfg, "s3_read_object", "{\"bucket\": \"acme-reports\", \"key\": \"app/.env\"}");
    assertThat(secret.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(secret.ruleId()).isEqualTo("dev.s3.object-not-allowed");
    for (String name : List.of("deploy/terraform.tfstate", "keys/server.pem", "home/.ssh/id_ed25519", ".netrc")) {
      assertThat(classify(cfg, "s3_read_object", JSON.writeValueAsString(Map.of("bucket", "acme-x", "key", name)))
          .kind()).as(name).isEqualTo(Classification.Kind.DENY);
    }
    assertThat(classify(cfg, "s3_head_object", "{\"bucket\": \"acme-reports\", \"key\": \"app/.env\"}").kind())
        .as("its metadata is fine").isEqualTo(Classification.Kind.READ);
    assertThat(s3).isEmpty();
    assertThat(sts).isEmpty();
  }

  @Test
  void anIncompleteOrMisplacedConnectionIsRefused() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "nowhere");
    ConnectionConfig broken = new ConnectionConfig("conn-x", "x", "s3", Env.DEV, 1, c, Set.of(), null);
    assertThat(classify(broken, "s3_list_buckets", "{}").ruleId()).isEqualTo("dev.s3.bad-config");
    assertThat(connector.test(broken, secrets, TestCtx.of()).error().code()).isEqualTo("s3.settings");
    ConnectionConfig onAws = custom("https://s3.eu-central-1.amazonaws.com", "eu-central-1", null);
    assertThat(classify(onAws, "s3_list_buckets", "{}").reason()).contains("leave the endpoint empty");
    ObjectNode withRole = (ObjectNode) custom("https://r2.mock.test", "auto", null).config().deepCopy();
    ((ObjectNode) withRole.get("credentials")).put("roleArn", ROLE);
    assertThat(classify(new ConnectionConfig("conn-r", "r", "s3", Env.DEV, 1, withRole, Set.of(), null),
        "s3_list_buckets", "{}").reason()).contains("Only Amazon S3 assumes roles");
    for (String endpoint : List.of("ftp://files.mock.test", "https://user:pw@r2.mock.test", "https://r2.mock.test/?a=b",
        "https://r2.mock.test/a/../b")) {
      assertThat(classify(custom(endpoint, "auto", null), "s3_list_buckets", "{}").kind()).as(endpoint)
          .isEqualTo(Classification.Kind.DENY);
    }
    assertThat(s3).isEmpty();
  }

  // ── What is sent and what comes back ───────────────────────────────────────────────────────────────────────

  @Test
  void aListingIsVirtualHostedSignedAndPagesWithASealedCursor() throws Exception {
    on("GET", "acme-reports", null, r -> query(r, "continuation-token") == null
        ? xml(200, "<ListBucketResult " + NS + "><Name>acme-reports</Name><Prefix>reports%2F</Prefix>"
            + "<EncodingType>url</EncodingType><IsTruncated>true</IsTruncated><NextContinuationToken>tok/2+=</"
            + "NextContinuationToken><Contents><Key>reports/q3+final%2B.csv</Key><LastModified>2026-11-10T14:02:11.000Z"
            + "</LastModified><ETag>&quot;9b2cf535f27731c974343645a3985328&quot;</ETag><Size>1204</Size>"
            + "<StorageClass>STANDARD</StorageClass></Contents><CommonPrefixes><Prefix>reports/2026/</Prefix>"
            + "</CommonPrefixes></ListBucketResult>")
        : xml(200, "<ListBucketResult " + NS + "><Name>acme-reports</Name><IsTruncated>false</IsTruncated><Contents>"
            + "<Key>reports/z.json</Key><Size>3</Size></Contents></ListBucketResult>"));
    ConnectionConfig cfg = aws(Env.DEV, false, "acme-*");
    String args = "{\"bucket\": \"acme-reports\", \"prefix\": \"reports/\", \"limit\": 2}";
    TestCtx ctx = TestCtx.of();
    ToolResult first = connector.execute(call("s3_list_objects", args), cfg, secrets, ctx);

    RecordedRequest list = sent(KEY_SECRET).getFirst();
    assertThat(list.getHeaders().get("Host")).isEqualTo("acme-reports.s3.mock.test:" + port);
    assertThat(list.getTarget()).startsWith("/?");
    assertThat(query(list, "list-type")).isEqualTo("2");
    assertThat(query(list, "max-keys")).isEqualTo("2");
    assertThat(query(list, "delimiter")).isEqualTo("/");
    assertThat(query(list, "encoding-type")).isEqualTo("url");
    assertThat(query(list, "prefix")).isEqualTo("reports/");
    assertThat(list.getHeaders().get("Authorization")).contains("Credential=AKIAIOSFODNN7EXAMPLE/",
        "/eu-central-1/s3/aws4_request", "x-amz-content-sha256");
    assertThat(list.getHeaders().get("x-amz-content-sha256")).isEqualTo(S3Request.EMPTY_SHA256);
    assertThat(list.getHeaders().get("x-amz-expected-bucket-owner")).as("a listed bucket").isNull();

    JsonNode out = first.structuredContent();
    assertThat(out.path("prefixes").toString()).isEqualTo("[\"reports/2026/\"]");
    assertThat(out.path("objects").get(0).path("key").asString()).as("decoded as S3 encoded it")
        .isEqualTo("reports/q3 final+.csv");
    assertThat(out.path("objects").get(0).path("size").asLong()).isEqualTo(1204);
    String cursor = out.path("nextCursor").asString();
    assertThat(cursor).startsWith("s31.").doesNotContain("tok/2");
    assertThat(first.content().getFirst().text()).contains("1 prefix, 1 object", "reports/2026/", "More: pass");

    ObjectNode more = (ObjectNode) JSON.readTree(args);
    more.put("cursor", cursor);
    ToolResult second = connector.execute(call("s3_list_objects", more.toString()), cfg, secrets, ctx.next());
    assertThat(query(sent(KEY_SECRET).getLast(), "continuation-token")).isEqualTo("tok/2+=");
    assertThat(second.structuredContent().has("nextCursor")).isFalse();

    // The cursor belongs to this request of this connection.
    ObjectNode other = (ObjectNode) JSON.readTree(args);
    other.put("prefix", "secrets/").put("cursor", cursor);
    assertThatThrownBy(() -> connector.execute(call("s3_list_objects", other.toString()), cfg, secrets, ctx.next()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
  }

  @Test
  void bucketListingsKeepToTheConnectionsBuckets() throws Exception {
    on("GET", "", null, r -> xml(200, "<ListAllMyBucketsResult " + NS + "><Buckets>"
        + "<Bucket><Name>payroll</Name><CreationDate>2025-01-01T00:00:00.000Z</CreationDate></Bucket>"
        + "<Bucket><Name>acme-reports</Name><CreationDate>2026-01-02T03:04:05.000Z</CreationDate><BucketRegion>"
        + "eu-central-1</BucketRegion></Bucket><Bucket><Name>acme-logs</Name></Bucket></Buckets>"
        + "</ListAllMyBucketsResult>"));
    ToolResult r = connector.execute(call("s3_list_buckets", "{\"limit\": 1}"), aws(Env.DEV, false, "acme-*"),
        secrets, TestCtx.of());
    RecordedRequest req = sent(KEY_SECRET).getFirst();
    assertThat(req.getHeaders().get("Host")).isEqualTo("s3.mock.test:" + port);
    assertThat(query(req, "bucket-region")).isEqualTo("eu-central-1");
    assertThat(query(req, "max-buckets")).isEqualTo("1000");
    assertThat(r.structuredContent().path("buckets").toString()).isEqualTo("[{\"name\":\"acme-logs\"}]");
    String cursor = r.structuredContent().path("nextCursor").asString();
    ToolResult rest = connector.execute(call("s3_list_buckets", "{\"limit\": 5, \"cursor\": \"" + cursor + "\"}"),
        aws(Env.DEV, false, "acme-*"), secrets, TestCtx.of());
    assertThat(rest.structuredContent().path("buckets").toString()).contains("acme-reports", "eu-central-1")
        .doesNotContain("payroll", "acme-logs");

    // Another provider gets ListBuckets without AWS's parameters.
    s3.clear();
    connector.execute(call("s3_list_buckets", "{}"), custom("http://minio.mock.test:" + port, "us-east-1", null),
        secrets, TestCtx.of());
    assertThat(sent(KEY_SECRET).getFirst().getTarget()).isEqualTo("/");
  }

  @Test
  void aTextReadTakesOneRangeAndStopsAtAWholeCharacter() throws Exception {
    byte[] object = "Prüfung,1\nok,2\n".getBytes(StandardCharsets.UTF_8);
    on("HEAD", "acme-reports", "reports/q3.csv", r -> new MockResponse.Builder().code(200)
        .setHeader("Content-Length", String.valueOf(object.length)).addHeader("Content-Type", "text/csv; charset=utf-8")
        .addHeader("ETag", "\"abc123\"").addHeader("Last-Modified", "Tue, 10 Nov 2026 14:02:11 GMT").build());
    on("GET", "acme-reports", "reports/q3.csv", r -> {
      String range = r.getHeaders().get("Range");
      int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
      int to = Integer.parseInt(range.substring(range.indexOf('-') + 1));
      return new MockResponse.Builder().code(206).addHeader("Content-Type", "text/csv; charset=utf-8")
          .addHeader("Content-Range", "bytes " + from + "-" + to + "/" + object.length)
          .body(new Buffer().write(java.util.Arrays.copyOfRange(object, from, to + 1))).build();
    });
    ConnectionConfig cfg = aws(Env.DEV, false, "acme-reports");
    // "Prü": the third byte cuts ü in half, so only "Pr" comes back and the next read starts at ü.
    ToolResult part = connector.execute(call("s3_read_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"reports/q3.csv\", \"maxBytes\": 3}"), cfg, secrets, TestCtx.of());
    RecordedRequest get = sent(KEY_SECRET).getLast();
    assertThat(get.getHeaders().get("Range")).isEqualTo("bytes=0-2");
    assertThat(get.getHeaders().get("If-Match")).isEqualTo("\"abc123\"");
    assertThat(part.structuredContent().path("text").asString()).isEqualTo("Pr");
    assertThat(part.structuredContent().path("nextOffset").asLong()).isEqualTo(2);
    assertThat(part.structuredContent().path("lastModified").asString()).isEqualTo("2026-11-10T14:02:11Z");
    ToolResult rest = connector.execute(call("s3_read_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"reports/q3.csv\", \"offset\": 2}"), cfg, secrets, TestCtx.of());
    assertThat(sent(KEY_SECRET).getLast().getHeaders().get("Range")).isEqualTo("bytes=2-" + (object.length - 1));
    assertThat(rest.structuredContent().path("text").asString()).isEqualTo("üfung,1\nok,2\n");
    assertThat(rest.structuredContent().path("truncated").asBoolean()).isFalse();
    assertThat(rest.content().getFirst().text()).startsWith("s3://acme-reports/reports/q3.csv · bytes 2–");

    // The endpoint's response size lowers maxBytes.
    TestCtx small = TestCtx.of().with(new com.gaterook.connector.api.Limits(100, 200, 8 * 1024, 64 * 1024,
        256 * 1024, 64 * 1024, 8 * 1024, java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30),
        java.time.Duration.ofSeconds(5), null));
    ToolResult bounded = connector.execute(call("s3_read_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"reports/q3.csv\", \"maxBytes\": 262144}"), cfg, secrets, small);
    assertThat(bounded.structuredContent().path("notes").toString()).contains("maxBytes lowered to 1024");
  }

  @Test
  void binaryAndArchivedObjectsAreRefusedWithTheirMetadata() {
    on("HEAD", "acme-reports", "img/logo.png", r -> new MockResponse.Builder().code(200).setHeader("Content-Length",
        "5120").addHeader("Content-Type", "image/png").addHeader("ETag", "\"e1\"").build());
    on("HEAD", "acme-reports", "old/2019.csv", r -> new MockResponse.Builder().code(200).setHeader("Content-Length",
        "99").addHeader("Content-Type", "text/csv").addHeader("x-amz-storage-class", "DEEP_ARCHIVE").build());
    on("HEAD", "acme-reports", "logs/app.log.gz", r -> new MockResponse.Builder().code(200).setHeader(
        "Content-Length", "99").addHeader("Content-Type", "text/plain").addHeader("Content-Encoding", "gzip").build());
    ConnectionConfig cfg = aws(Env.DEV, false, "acme-reports");
    assertThatThrownBy(() -> connector.execute(call("s3_read_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"img/logo.png\"}"), cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("image/png", "5,120 bytes", "\"e1\"", "reads text only");
        });
    assertThatThrownBy(() -> connector.execute(call("s3_read_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"old/2019.csv\"}"), cfg, secrets, TestCtx.of())).hasMessageContaining("DEEP_ARCHIVE");
    assertThatThrownBy(() -> connector.execute(call("s3_read_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"logs/app.log.gz\"}"), cfg, secrets, TestCtx.of())).hasMessageContaining("Content-Encoding gzip");
    assertThat(sent(KEY_SECRET)).extracting(RecordedRequest::getMethod).containsOnly("HEAD");
  }

  @Test
  void metadataNamedLikeASecretIsMasked() throws Exception {
    on("HEAD", "acme-reports", "a.json", r -> new MockResponse.Builder().code(200).setHeader("Content-Length", "2")
        .addHeader("Content-Type", "application/json").addHeader("x-amz-meta-owner", "data-team")
        .addHeader("x-amz-meta-api-token", "tok-very-secret").addHeader("x-amz-meta-db-password", "hunter2")
        .addHeader("x-amz-version-id", "3HL4kqtJlcpXroDTDmJ+rmSpXd3dIbrHY").build());
    ToolResult r = connector.execute(call("s3_head_object", "{\"bucket\": \"acme-reports\", \"key\": \"a.json\"}"),
        aws(Env.DEV, false, "acme-reports"), secrets, TestCtx.of());
    JsonNode out = r.structuredContent();
    assertThat(out.path("metadata").path("owner").asString()).isEqualTo("data-team");
    assertThat(out.path("metadata").path("api-token").asString()).isEqualTo("‹masked›");
    assertThat(out.path("metadata").path("db-password").asString()).isEqualTo("‹masked›");
    assertThat(out.path("maskedMetadata").asInt()).isEqualTo(2);
    assertThat(out.path("versionId").asString()).isEqualTo("3HL4kqtJlcpXroDTDmJ+rmSpXd3dIbrHY");
    assertThat(out.path("readableAsText").asBoolean()).isTrue();
    assertThat(out.toString() + r.content().getFirst().text()).doesNotContain("tok-very-secret", "hunter2");
  }

  @Test
  void s3ErrorsSayWhatToDo() {
    on("HEAD", "acme-reports", "moved.csv", r -> new MockResponse.Builder().code(301)
        .addHeader("x-amz-bucket-region", "us-east-1").build());
    on("GET", "acme-reports", null, r -> xml(403, "<Error><Code>AccessDenied</Code><Message>Access Denied</Message>"
        + "</Error>"));
    ConnectionConfig cfg = aws(Env.DEV, false, "acme-reports");
    assertThatThrownBy(() -> connector.execute(call("s3_head_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"moved.csv\"}"), cfg, secrets, TestCtx.of()))
        .hasMessageContaining("is in us-east-1, not eu-central-1");
    assertThatThrownBy(() -> connector.execute(call("s3_list_objects", "{\"bucket\": \"acme-reports\"}"), cfg, secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("may not ListObjectsV2 on acme-reports", "Access Denied");
        });
    assertThatThrownBy(() -> connector.execute(call("s3_head_object", "{\"bucket\": \"acme-reports\", \"key\": "
        + "\"missing.csv\"}"), cfg, secrets, TestCtx.of())).hasMessageContaining("There is no object missing.csv");
  }

  @Test
  void anAnswerWithADoctypeIsNeverExpanded() {
    on("GET", "acme-reports", null, r -> xml(200, "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM "
        + "\"file:///etc/passwd\">]><ListBucketResult " + NS + "><Name>&x;</Name></ListBucketResult>"));
    assertThatThrownBy(() -> connector.execute(call("s3_list_objects", "{\"bucket\": \"acme-reports\"}"),
        aws(Env.DEV, false, "acme-reports"), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("isn't S3's XML")
            .doesNotContain("root:"));
  }

  @Test
  void withoutABucketListAmazonS3KeepsToTheCredentialsOwnAccount() throws Exception {
    on("GET", "acme-reports", null, r -> xml(200, "<ListBucketResult " + NS + "><Name>acme-reports</Name>"
        + "<IsTruncated>false</IsTruncated></ListBucketResult>"));
    ConnectionConfig cfg = aws(Env.DEV, false);
    connector.execute(call("s3_list_objects", "{\"bucket\": \"acme-reports\"}"), cfg, secrets, TestCtx.of());
    connector.execute(call("s3_list_objects", "{\"bucket\": \"acme-reports\"}"), cfg, secrets, TestCtx.of());
    assertThat(sent(KEY_SECRET)).allSatisfy(r -> assertThat(r.getHeaders().get("x-amz-expected-bucket-owner"))
        .isEqualTo("412398765012"));
    assertThat(sts).as("one lookup of the account, kept for the connection version").hasSize(1);
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aPutIsApprovedExactlyAndComparedBeforeItIsSent() throws Exception {
    on("PUT", "acme-reports", "reports/q3.csv", r -> new MockResponse.Builder().code(200)
        .addHeader("ETag", "\"d41d8cd98f00b204e9800998ecf8427e\"").addHeader("x-amz-version-id", "v2").build());
    ConnectionConfig cfg = aws(Env.DEV, true, "acme-*");
    String args = "{\"bucket\": \"acme-reports\", \"key\": \"reports/q3.csv\", \"content\": \"id,total\\n1,12.5\\n\", "
        + "\"contentType\": \"text/csv\", \"metadata\": {\"source\": \"agent\"}, \"overwrite\": false, \"note\": "
        + "\"weekly export\"}";
    Classification c = classify(cfg, "s3_put_object", args);
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.facts().path("bucket").asString()).isEqualTo("acme-reports");
    ActionPreview p = connector.describeAction(call("s3_put_object", args), cfg);
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(TestCtx.lines(p.text())).contains("PUT http://acme-reports.s3.mock.test:" + port + "/reports/q3.csv",
        "content-type: text/csv", "if-none-match: *", "x-amz-meta-source: agent", "content-length: 16",
        "authorization: AWS4-HMAC-SHA256 ‹masked› (" + ROLE + ")", "id,total");
    JsonNode payload = p.payload();
    assertThat(payload.path("url").asString()).isEqualTo("http://acme-reports.s3.mock.test:" + port
        + "/reports/q3.csv");
    assertThat(payload.path("bodySha256").asString()).isEqualTo(S3Request.sha256(
        "id,total\n1,12.5\n".getBytes(StandardCharsets.UTF_8)));
    assertThat(payload.path("headers").path("if-none-match").asString()).isEqualTo("*");
    assertThat(p.impact()).anySatisfy(f -> assertThat(f.value()).contains("Created; nothing changes"));

    assertThatThrownBy(() -> connector.execute(call("s3_put_object", args), cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    // An approval of another request: nothing is sent.
    ObjectNode otherPayload = (ObjectNode) payload.deepCopy();
    otherPayload.put("url", "http://acme-reports.s3.mock.test:" + port + "/reports/q4.csv");
    assertThatThrownBy(() -> connector.execute(call("s3_put_object", args), cfg, secrets,
        TestCtx.of().approved(otherPayload))).hasMessageContaining("doesn't match the request that was approved");
    assertThat(s3).isEmpty();

    ToolResult done = connector.execute(call("s3_put_object", args), cfg, secrets, TestCtx.of().approved(payload));
    RecordedRequest put = sent(SESSION_SECRET).getLast();
    assertThat(put.getBody().utf8()).isEqualTo("id,total\n1,12.5\n");
    assertThat(put.getHeaders().get("Content-Type")).isEqualTo("text/csv");
    assertThat(put.getHeaders().get("If-None-Match")).isEqualTo("*");
    assertThat(put.getHeaders().get("x-amz-meta-source")).isEqualTo("agent");
    assertThat(put.getHeaders().get("x-amz-security-token")).isEqualTo("session-token");
    assertThat(done.structuredContent().path("changed").asBoolean()).isTrue();
    assertThat(done.structuredContent().path("versionId").asString()).isEqualTo("v2");
    assertThat(done.content().getFirst().text()).isEqualTo("Wrote 16 bytes to acme-reports/reports/q3.csv.");
    String writePolicy = policyOf(assumeRoles().getLast());
    assertThat(writePolicy).contains("\"s3:PutObject\"", "arn:aws:s3:::acme-reports/reports/q3.csv",
        "kms:GenerateDataKey", "kms:ViaService", "s3.eu-central-1.amazonaws.com")
        .doesNotContain("s3:GetObject", "s3:DeleteObject", "acme-*");
  }

  @Test
  void prepareShowsWhatAPutReplaces() throws Exception {
    on("HEAD", "acme-reports", "reports/q3.csv", r -> new MockResponse.Builder().code(200)
        .setHeader("Content-Length", "1204").addHeader("Content-Type", "text/csv")
        .addHeader("Last-Modified", "Tue, 10 Nov 2026 14:02:11 GMT").build());
    String args = "{\"bucket\": \"acme-reports\", \"key\": \"reports/q3.csv\", \"content\": \"x\"}";
    PreparedAction prepared = connector.prepare(call("s3_put_object", args), aws(Env.DEV, true, "acme-*"), secrets,
        TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().impact()).anySatisfy(f -> assertThat(f.value())
        .isEqualTo("Replaces 1,204 bytes of text/csv, modified 2026-11-10T14:02:11Z"));
    assertThat(prepared.preview().warnings()).contains("Replaces the object at acme-reports/reports/q3.csv.");
    String readPolicy = policyOf(assumeRoles().getLast());
    assertThat(readPolicy).contains("s3:GetObject", "arn:aws:s3:::acme-*/*").doesNotContain("PutObject");
    assertThat(connector.prepare(call("s3_put_object", "{\"bucket\": \"payroll\", \"key\": \"a\", \"content\": "
        + "\"x\"}"), aws(Env.DEV, true, "acme-*"), secrets, TestCtx.of()).outcome())
        .isEqualTo(PreparedAction.Outcome.REJECT);
  }

  @Test
  void aPutLargerThanTheEndpointsRequestSizeIsRefused() {
    String big = "x".repeat(64 * 1024 + 1);
    Classification c = connector.classify(call("s3_put_object", JSON.writeValueAsString(Map.of("bucket",
        "acme-reports", "key", "big.txt", "content", big))), aws(Env.PROD, false, "acme-reports"), policy(Env.PROD));
    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("prod.s3.too-large");
    assertThat(classify(aws(Env.DEV, false, "acme-reports"), "s3_put_object", "{\"bucket\": \"acme-reports\", "
        + "\"key\": \"a.bin\", \"content\": \"x\", \"contentType\": \"application/octet-stream\"}").reason())
        .contains("isn't a text content type");
  }

  @Test
  void aLostAnswerToAChangeIsUnknown() {
    on("DELETE", "acme-reports", "a.csv", r -> new MockResponse.Builder()
        .onResponseStart(new SocketEffect.CloseSocket()).build());
    on("PUT", "acme-reports", "b.csv", r -> xml(500, "<Error><Code>InternalError</Code><Message>We encountered an "
        + "internal error.</Message></Error>"));
    ConnectionConfig cfg = aws(Env.DEV, false, "acme-reports");
    String delete = "{\"bucket\": \"acme-reports\", \"key\": \"a.csv\"}";
    JsonNode approvedDelete = connector.describeAction(call("s3_delete_object", delete), cfg).payload();
    assertThatThrownBy(() -> connector.execute(call("s3_delete_object", delete), cfg, secrets,
        TestCtx.of().approved(approvedDelete))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    String put = "{\"bucket\": \"acme-reports\", \"key\": \"b.csv\", \"content\": \"x\"}";
    JsonNode approvedPut = connector.describeAction(call("s3_put_object", put), cfg).payload();
    assertThatThrownBy(() -> connector.execute(call("s3_put_object", put), cfg, secrets,
        TestCtx.of().approved(approvedPut))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
  }

  @Test
  void copyAndDeleteSendTheirOwnHeadersUnderTheirOwnPolicies() throws Exception {
    on("PUT", "acme-archive", "2026/q3 final.csv", r -> xml(200, "<CopyObjectResult><LastModified>"
        + "2026-11-11T09:00:00.000Z</LastModified><ETag>\"c0ffee\"</ETag></CopyObjectResult>"));
    on("DELETE", "acme-reports", "reports/q3.csv", r -> new MockResponse.Builder().code(204)
        .addHeader("x-amz-delete-marker", "true").addHeader("x-amz-version-id", "dm1").build());
    ConnectionConfig cfg = aws(Env.DEV, true, "acme-*");
    String copy = "{\"sourceBucket\": \"acme-reports\", \"sourceKey\": \"reports/q3 final.csv\", \"sourceVersionId\": "
        + "\"v1\", \"bucket\": \"acme-archive\", \"key\": \"2026/q3 final.csv\"}";
    ActionPreview p = connector.describeAction(call("s3_copy_object", copy), cfg);
    assertThat(TestCtx.lines(p.text())).contains("x-amz-copy-source: acme-reports/reports/q3%20final.csv?versionId=v1");
    ToolResult copied = connector.execute(call("s3_copy_object", copy), cfg, secrets, TestCtx.of().approved(p.payload()));
    RecordedRequest sentCopy = sent(SESSION_SECRET).getLast();
    assertThat(sentCopy.getTarget()).isEqualTo("/2026/q3%20final.csv");
    assertThat(sentCopy.getHeaders().get("x-amz-copy-source")).isEqualTo("acme-reports/reports/q3%20final.csv"
        + "?versionId=v1");
    assertThat(sentCopy.getBodySize()).isZero();
    assertThat(copied.structuredContent().path("etag").asString()).isEqualTo("\"c0ffee\"");
    String copyPolicy = policyOf(assumeRoles().getLast());
    assertThat(copyPolicy).contains("s3:GetObjectVersion", "arn:aws:s3:::acme-reports/reports/q3 final.csv",
        "s3:PutObject", "arn:aws:s3:::acme-archive/2026/q3 final.csv").doesNotContain("acme-*");

    String delete = "{\"bucket\": \"acme-reports\", \"key\": \"reports/q3.csv\"}";
    ActionPreview d = connector.describeAction(call("s3_delete_object", delete), cfg);
    assertThat(d.warnings()).anySatisfy(w -> assertThat(w).contains("keeps its earlier versions"));
    ToolResult deleted = connector.execute(call("s3_delete_object", delete), cfg, secrets,
        TestCtx.of().approved(d.payload()));
    assertThat(sent(SESSION_SECRET).getLast().getMethod()).isEqualTo("DELETE");
    assertThat(deleted.content().getFirst().text()).isEqualTo("Added a delete marker to acme-reports/reports/q3.csv.");
    assertThat(policyOf(assumeRoles().getLast())).contains("s3:DeleteObject").doesNotContain("DeleteObjectVersion",
        "PutObject");

    // A key with IAM's wildcards stays one object in the session policy.
    assertThat(SessionPolicies.object(S3Settings.of(cfg), "acme-reports", "a*b?$c", true))
        .isEqualTo("arn:aws:s3:::acme-reports/a${*}b${?}${$}c");
  }

  @Test
  void aCopyThatFailsAfterItsStatusIsNotReportedAsDone() {
    on("PUT", "acme-archive", "x.csv", r -> xml(200, "<Error><Code>InternalError</Code><Message>We encountered an "
        + "internal error.</Message></Error>"));
    ConnectionConfig cfg = aws(Env.DEV, false, "acme-*");
    String copy = "{\"sourceBucket\": \"acme-reports\", \"sourceKey\": \"x.csv\", \"bucket\": \"acme-archive\", "
        + "\"key\": \"x.csv\"}";
    JsonNode approved = connector.describeAction(call("s3_copy_object", copy), cfg).payload();
    assertThatThrownBy(() -> connector.execute(call("s3_copy_object", copy), cfg, secrets,
        TestCtx.of().approved(approved))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    assertThat(classify(cfg, "s3_copy_object", "{\"sourceBucket\": \"acme-reports\", \"sourceKey\": \"x.csv\", "
        + "\"bucket\": \"acme-reports\", \"key\": \"x.csv\"}").reason()).contains("onto itself");
  }

  // ── Other providers ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anotherProviderUsesPathStyleItsRegionAndItsKey() throws Exception {
    on("GET", "media", "docs/readme.md", r -> new MockResponse.Builder().code(206)
        .addHeader("Content-Type", "text/markdown").body("# Hi").build());
    on("HEAD", "media", "docs/readme.md", r -> new MockResponse.Builder().code(200).setHeader("Content-Length", "4")
        .addHeader("Content-Type", "text/markdown").build());
    on("GET", "", null, r -> xml(200, "<ListAllMyBucketsResult><Buckets><Bucket><Name>media</Name></Bucket></Buckets>"
        + "</ListAllMyBucketsResult>"));
    ConnectionConfig cfg = custom("http://r2.mock.test:" + port + "/", "auto", null, "media");
    ToolResult r = connector.execute(call("s3_read_object", "{\"bucket\": \"media\", \"key\": \"docs/readme.md\"}"),
        cfg, secrets, TestCtx.of());
    assertThat(r.structuredContent().path("text").asString()).isEqualTo("# Hi");
    RecordedRequest get = sent(KEY_SECRET).getLast();
    assertThat(get.getHeaders().get("Host")).isEqualTo("r2.mock.test:" + port);
    assertThat(get.getTarget()).isEqualTo("/media/docs/readme.md");
    assertThat(get.getHeaders().get("Authorization")).contains("Credential=r2-key-0123456789abcdef0123456789abcd/",
        "/auto/s3/aws4_request");
    assertThat(sts).as("no STS for another provider").isEmpty();
    TestResult t = connector.test(cfg, secrets, TestCtx.of());
    assertThat(t.hints()).anySatisfy(h -> assertThat(h.code()).isEqualTo("s3.key-permissions"));
    assertThat(t.hints()).anySatisfy(h -> assertThat(h.code()).isEqualTo("s3.plain-http"));
  }

  @Test
  void aPrivateEndpointNeedsItsRangeAllowed() {
    ConnectionConfig cfg = custom("http://127.0.0.1:" + port, "us-east-1", null, "media");
    assertThatThrownBy(() -> connector.execute(call("s3_list_objects", "{\"bucket\": \"media\"}"), cfg, secrets,
        TestCtx.of().publicOnly())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("127.0.0.1");
        });
    assertThat(s3).isEmpty();
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionNamesTheIdentityAndTheBucketsAgentsMayUse() {
    on("GET", "", null, r -> xml(200, "<ListAllMyBucketsResult " + NS + "><Buckets><Bucket><Name>acme-reports</Name>"
        + "</Bucket><Bucket><Name>payroll</Name></Bucket></Buckets></ListAllMyBucketsResult>"));
    TestResult r = connector.test(aws(Env.PROD, false, "acme-*"), secrets, TestCtx.of().env(Env.PROD));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("arn:aws:iam::412398765012:user/reader");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("identity", "buckets");
    assertThat(r.checks().get(1).detail()).isEqualTo("1 bucket agents may use: acme-reports");
    assertThat(r.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("s3.no-role");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
    assertThat(r.metadataUpdate().path("account").asString()).isEqualTo("412398765012");
    TestResult withRole = connector.test(aws(Env.PROD, true, "acme-*"), secrets, TestCtx.of().env(Env.PROD));
    assertThat(withRole.identity()).contains("assumed-role/gw-s3");
    assertThat(withRole.hints()).noneSatisfy(h -> assertThat(h.code()).isEqualTo("s3.no-role"));
  }

  @Test
  void credentialsThatMayNotListBucketsAreCheckedBucketByBucket() {
    on("GET", "", null, r -> xml(403, "<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>"));
    on("HEAD", "acme-reports", null, r -> new MockResponse.Builder().code(200).build());
    TestResult r = connector.test(custom("http://r2.mock.test:" + port, "auto", null, "acme-reports", "acme-*"),
        secrets, TestCtx.of());
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.checks().getLast().detail()).contains("1 bucket agents may use: acme-reports",
        "may not list buckets");
    List<String> methods = new ArrayList<>();
    sent(KEY_SECRET).forEach(x -> methods.add(x.getMethod() + " " + x.getTarget()));
    assertThat(methods).containsExactly("GET /", "HEAD /acme-reports");
  }
}
