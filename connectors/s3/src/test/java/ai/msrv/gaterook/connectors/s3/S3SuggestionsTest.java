package ai.msrv.gaterook.connectors.s3;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import ai.msrv.gaterook.connectors.support.LocalPorts;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the S3 form: the profiles and regions of this computer, what a provider's endpoint names, and once the
 * draft signs in, the buckets, against a fake of STS, Amazon S3 and an S3-compatible server.
 */
class S3SuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-s3";
  private static final String SESSION_ARN = "arn:aws:sts::412398765012:assumed-role/gw-s3/gaterook-draft";
  private static final String NS = "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"";

  @TempDir
  Path home;

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> sts = new CopyOnWriteArrayList<>();
  private final List<RecordedRequest> s3 = new CopyOnWriteArrayList<>();
  private volatile Function<RecordedRequest, MockResponse> buckets = r -> xml(200, "<ListAllMyBucketsResult " + NS
      + "><Buckets/></ListAllMyBucketsResult>");
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", "test-only-secret-key");
  private CloudCredentials cloud;
  private S3Connector connector;
  private int port;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest r) {
        String body = r.getBody() == null ? "" : r.getBody().utf8();
        if (body.contains("Action=AssumeRole")) {
          sts.add(r);
          return xml(200, "<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
              + "<Credentials><AccessKeyId>ASIATESTSESSION00001</AccessKeyId><SecretAccessKey>session-secret"
              + "</SecretAccessKey><SessionToken>session-token</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
              + "</Credentials><AssumedRoleUser><AssumedRoleId>AROATEST:gaterook</AssumedRoleId><Arn>" + SESSION_ARN
              + "</Arn></AssumedRoleUser></AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId>"
              + "</ResponseMetadata></AssumeRoleResponse>");
        }
        if (body.contains("Action=GetCallerIdentity")) {
          sts.add(r);
          boolean session = String.valueOf(r.getHeaders().get("Authorization")).contains("Credential=ASIA");
          return xml(200, "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<GetCallerIdentityResult><Arn>" + (session ? SESSION_ARN : "arn:aws:iam::412398765012:user/reader")
              + "</Arn><UserId>AIDATEST</UserId><Account>412398765012</Account></GetCallerIdentityResult>"
              + "<ResponseMetadata><RequestId>2</RequestId></ResponseMetadata></GetCallerIdentityResponse>");
        }
        s3.add(r);
        return r.getMethod().equals("GET") && r.getUrl().encodedPath().equals("/") ? buckets.apply(r)
            : xml(404, "<Error><Code>NoSuchBucket</Code><Message>no fake</Message></Error>");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    port = server.getPort();
    URI base = URI.create("http://127.0.0.1:" + port);
    Path aws = Files.createDirectories(home.resolve(".aws"));
    Files.writeString(aws.resolve("config"), """
        [default]
        region = eu-central-1
        [profile tool]
        credential_process = %s
        """.formatted(home.resolve("tool")));
    Files.writeString(home.resolve("tool"), "#!/bin/sh\necho ran > " + home.resolve("ran") + "\n");
    home.resolve("tool").toFile().setExecutable(true);
    CloudEndpoints endpoints = new CloudEndpoints(null, null, null, base, aws.resolve("config"),
        aws.resolve("credentials"), Map.of("aws.s3", URI.create("http://s3.mock.test:" + port)));
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
    connector = new S3Connector(cloud, new CloudLocal(endpoints, Map.<String, String>of()::get, home));
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  private static MockResponse xml(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/xml").body(body).build();
  }

  private static String bucketList(String... namesAndRegions) {
    StringBuilder b = new StringBuilder("<ListAllMyBucketsResult " + NS + "><Buckets>");
    for (String item : namesAndRegions) {
      String[] p = item.split("@");
      b.append("<Bucket><Name>").append(p[0]).append("</Name>")
          .append(p.length > 1 ? "<BucketRegion>" + p[1] + "</BucketRegion>" : "").append("</Bucket>");
    }
    return b.append("</Buckets></ListAllMyBucketsResult>").toString();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "s3", Env.DEV, 0, JSON.readTree(json),
        Set.of("/credentials/secretAccessKey"), null);
  }

  private Suggestions local(String json) {
    return connector.suggest(draft(json), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);
  }

  private Suggestions service(String json) {
    return connector.suggest(draft(json), secrets, TestCtx.of(), Suggestions.Scope.SERVICE);
  }

  @Test
  void amazonS3TakesTheProfilesAndRegionOfThisComputer() {
    Suggestions s = local("{\"credentials\":{\"source\":\"profile\"}}");

    assertThat(s.fields().get(S3Suggestions.PROFILE).value()).isEqualTo("default");
    assertThat(s.fields().get(S3Suggestions.REGION).value()).isEqualTo("eu-central-1");
    // An S3-compatible server on this computer is offered as an endpoint, never filled in.
    boolean server = LocalPorts.listening(9000) || LocalPorts.listening(4566);
    assertThat(s.fields().containsKey(S3Suggestions.ENDPOINT)).isEqualTo(server);
    if (server) {
      assertThat(s.fields().get(S3Suggestions.ENDPOINT).value()).isNull();
    }
    assertThat(sts).isEmpty();
    assertThat(s3).isEmpty();
  }

  @Test
  void aProvidersEndpointNamesItsRegionAndAddressing() {
    Suggestions r2 = local("{\"endpoint\":\"https://0123abcd.r2.cloudflarestorage.com\",\"credentials\":{}}");
    assertThat(r2.fields().get(S3Suggestions.REGION).value()).isEqualTo("auto");
    assertThat(r2.fields().get(S3Suggestions.REGION).source()).isEqualTo("the Cloudflare R2 endpoint");
    assertThat(r2.fields()).doesNotContainKeys(S3Suggestions.PATH_STYLE, S3Suggestions.PROFILE);

    Suggestions hetzner = local("{\"endpoint\":\"fsn1.your-objectstorage.com\",\"credentials\":{}}");
    assertThat(hetzner.fields().get(S3Suggestions.REGION).value()).isEqualTo("fsn1");
    assertThat(hetzner.fields().get(S3Suggestions.PATH_STYLE).value()).isEqualTo("false");

    Suggestions minio = local("{\"endpoint\":\"http://minio.internal:9000\",\"credentials\":{}}");
    assertThat(minio.fields()).isEmpty();
    assertThat(minio.notes()).isEmpty();

    Suggestions amazon = local("{\"endpoint\":\"https://s3.eu-central-1.amazonaws.com\",\"credentials\":{}}");
    assertThat(amazon.notes()).containsExactly("For Amazon S3, leave Endpoint empty: GateRook uses the region's "
        + "endpoint.");
  }

  @Test
  void theProviderTableReadsTheHost() {
    assertThat(S3Suggestions.provider("s3.us-west-002.backblazeb2.com"))
        .isEqualTo(new S3Suggestions.Provider("Backblaze B2", "us-west-002", false));
    assertThat(S3Suggestions.provider("s3.eu-central-2.wasabisys.com").region()).isEqualTo("eu-central-2");
    assertThat(S3Suggestions.provider("s3.wasabisys.com").region()).isEqualTo("us-east-1");
    assertThat(S3Suggestions.provider("nyc3.digitaloceanspaces.com"))
        .isEqualTo(new S3Suggestions.Provider("DigitalOcean Spaces", "us-east-1", true));
    assertThat(S3Suggestions.provider("acme.eu.r2.cloudflarestorage.com").region()).isEqualTo("auto");
    assertThat(S3Suggestions.provider("s3.gra.io.cloud.ovh.net").region()).isEqualTo("gra");
    assertThat(S3Suggestions.provider("s3.fr-par.scw.cloud").region()).isEqualTo("fr-par");
    assertThat(S3Suggestions.provider("ewr1.vultrobjects.com").virtualHosted()).isTrue();
    assertThat(S3Suggestions.provider("axaxnpcrorw5.compat.objectstorage.us-ashburn-1.oraclecloud.com").region())
        .isEqualTo("us-ashburn-1");
    assertThat(S3Suggestions.provider("s3.direct.us-south.cloud-object-storage.appdomain.cloud").region())
        .isEqualTo("us-south");
    assertThat(S3Suggestions.provider("storage.googleapis.com").region()).isEqualTo("auto");
    assertThat(S3Suggestions.provider("minio.internal")).isNull();
    // A look-alike host isn't the provider.
    assertThat(S3Suggestions.provider("s3.us-west-002.backblazeb2.com.example.test")).isNull();
  }

  @Test
  void signedInToAmazonS3ItOffersTheBucketsOfTheRegion() {
    buckets = r -> xml(200, bucketList("acme-reports@eu-central-1", "payroll@eu-central-1"));

    Suggestions s = service("""
        {"region":"eu-central-1","credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE",
         "roleArn":"%s","externalId":"gw-ext-0123456789ab"}}""".formatted(ROLE));

    Suggestions.Field b = s.fields().get(S3Suggestions.BUCKETS);
    assertThat(b.options()).extracting(Option::value).containsExactly("acme-reports", "payroll");
    assertThat(b.source()).isEqualTo("the account");
    assertThat(b.complete()).isTrue();
    assertThat(s.notes()).containsExactly("Signed in as " + SESSION_ARN + ". 2 buckets in eu-central-1: choose the "
        + "ones agents may use.");
    RecordedRequest list = s3.getFirst();
    assertThat(list.getUrl().queryParameter("bucket-region")).isEqualTo("eu-central-1");
    assertThat(list.getHeaders().get("Authorization")).contains("Credential=ASIATESTSESSION00001/");
    // The session allows listing the buckets and nothing else.
    String policy = URLDecoder.decode(sts.getFirst().getBody().utf8(), StandardCharsets.UTF_8);
    assertThat(policy).contains("s3:ListAllMyBuckets").doesNotContain("s3:GetObject").doesNotContain("s3:Put");
    assertThat(cloud.issued()).isEmpty();
  }

  @Test
  void withoutBucketsInTheRegionItSaysWhereTheAccountsBucketsAre() {
    buckets = r -> r.getUrl().queryParameter("bucket-region") != null ? xml(200, bucketList())
        : xml(200, bucketList("logs@us-east-1", "media@us-east-1", "backup@eu-west-1"));

    Suggestions s = service("""
        {"region":"eu-central-1","credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""");

    assertThat(s.fields()).doesNotContainKey(S3Suggestions.BUCKETS);
    Suggestions.Field region = s.fields().get(S3Suggestions.REGION);
    assertThat(region.value()).isNull();
    assertThat(region.options()).extracting(Option::value, Option::detail)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("us-east-1", "2 buckets"),
            org.assertj.core.groups.Tuple.tuple("eu-west-1", "1 bucket"));
    assertThat(s.notes()).containsExactly("Signed in as arn:aws:iam::412398765012:user/reader. No buckets in "
        + "eu-central-1; the account has buckets in us-east-1 (2), eu-west-1 (1): choose that region, or add a "
        + "connection for each.");
  }

  @Test
  void withoutARegionYetItOffersTheRegionsOfTheAccountsBuckets() {
    // x-suggest: the core asks while Amazon S3's Region is still empty.
    buckets = r -> r.getUrl().queryParameter("bucket-region") != null ? xml(400, "<Error/>")
        : xml(200, bucketList("logs@us-east-1", "media@us-east-1", "backup@eu-west-1"));

    Suggestions s = service("{\"credentials\":{\"source\":\"accessKey\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}}");

    Suggestions.Field region = s.fields().get(S3Suggestions.REGION);
    assertThat(region.options()).extracting(Option::value).containsExactly("us-east-1", "eu-west-1");
    assertThat(region.value()).as("two regions: the person chooses").isNull();
    assertThat(s.fields()).doesNotContainKey(S3Suggestions.BUCKETS);
    assertThat(s.notes()).containsExactly("Signed in as arn:aws:iam::412398765012:user/reader. The account has "
        + "buckets in us-east-1 (2), eu-west-1 (1): choose the region of the buckets agents may use.");

    buckets = r -> xml(200, bucketList("logs@us-east-1"));
    Suggestions one = service("{\"region\":\"us-ea\",\"credentials\":{\"source\":\"accessKey\","
        + "\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}}");
    assertThat(one.fields().get(S3Suggestions.REGION).value()).isEqualTo("us-east-1");
  }

  @Test
  void anS3CompatibleServerListsItsBucketsWithTheAccessKey() {
    buckets = r -> xml(200, bucketList("media"));

    Suggestions s = service("""
        {"endpoint":"http://minio.mock.test:%d","region":"us-east-1","credentials":{"accessKeyId":"minio-reader"}}"""
        .formatted(port));

    assertThat(s.fields().get(S3Suggestions.BUCKETS).options()).extracting(Option::value).containsExactly("media");
    assertThat(s.fields().get(S3Suggestions.BUCKETS).source()).isEqualTo("the storage service");
    assertThat(s.notes()).containsExactly("Signed in to minio.mock.test:" + port + ". 1 bucket: choose the ones "
        + "agents may use.");
    assertThat(sts).isEmpty();
    assertThat(s3.getFirst().getHeaders().get("Host")).isEqualTo("minio.mock.test:" + port);
    assertThat(s3.getFirst().getHeaders().get("Authorization")).contains("Credential=minio-reader/");
  }

  @Test
  void aKeyThatMayNotListBucketsIsToldToTypeThem() {
    buckets = r -> xml(403, "<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>");

    Suggestions s = service("""
        {"endpoint":"http://r2.mock.test:%d","region":"auto","credentials":{"accessKeyId":"r2-bucket-token"}}"""
        .formatted(port));

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("The credentials may not list buckets: type the buckets agents may use.");
  }

  @Test
  void aProfileThatRunsACommandIsNeverSignedInWith() {
    Suggestions s = service("{\"region\":\"eu-central-1\",\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"tool\"}}");

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("This profile runs a command to get credentials.", S3Suggestions.NO_SIGN_IN);
    assertThat(sts).isEmpty();
    assertThat(s3).isEmpty();
    assertThat(home.resolve("ran")).doesNotExist();
  }
}
