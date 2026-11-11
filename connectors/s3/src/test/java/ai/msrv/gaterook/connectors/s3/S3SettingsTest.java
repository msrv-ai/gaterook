package ai.msrv.gaterook.connectors.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Connection settings: Amazon S3 by region and partition, other providers by endpoint, and the form's schema. */
class S3SettingsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static S3Settings settings(String config) throws ConnectorException {
    return S3Settings.of(new ConnectionConfig("conn-s3", "s3", "s3", Env.DEV, 1, (ObjectNode) JSON.readTree(config),
        Set.of(), null));
  }

  private static String aws(String region) {
    return "{\"region\": \"" + region + "\", \"credentials\": {\"source\": \"accessKey\", \"accessKeyId\": "
        + "\"AKIAIOSFODNN7EXAMPLE\"}}";
  }

  private static String custom(String endpoint, String region) {
    return "{\"endpoint\": \"" + endpoint + "\", \"region\": \"" + region + "\", \"credentials\": {\"accessKeyId\": "
        + "\"0123456789abcdef0123456789abcdef\"}}";
  }

  @Test
  void amazonS3TakesTheRegionsEndpointInEveryPartition() throws Exception {
    S3Settings frankfurt = settings(aws("eu-central-1"));
    assertThat(frankfurt.aws()).isTrue();
    assertThat(frankfurt.pathStyle()).isFalse();
    assertThat(frankfurt.base(CloudEndpoints.PRODUCTION)).hasToString("https://s3.eu-central-1.amazonaws.com");
    assertThat(settings(aws("cn-north-1")).base(CloudEndpoints.PRODUCTION))
        .hasToString("https://s3.cn-north-1.amazonaws.com.cn");
    assertThat(settings(aws("us-gov-west-1")).base(CloudEndpoints.PRODUCTION))
        .hasToString("https://s3.us-gov-west-1.amazonaws.com");
    assertThat(SessionPolicies.object(settings(aws("cn-north-1")), "b", "k", true)).isEqualTo("arn:aws-cn:s3:::b/k");
    assertThat(SessionPolicies.object(settings(aws("us-gov-east-1")), "b", "k", true))
        .isEqualTo("arn:aws-us-gov:s3:::b/k");
    assertThat(SessionPolicies.viaS3(settings(aws("cn-northwest-1")))).isEqualTo("s3.cn-northwest-1.amazonaws.com.cn");
    assertThatThrownBy(() -> settings(aws("auto"))).hasMessageContaining("AWS region");

    // Virtual-hosted style, except for a bucket with dots (a TLS wildcard covers one label).
    URI base = frankfurt.base(CloudEndpoints.PRODUCTION);
    S3Request plain = S3Request.of("HeadObject", "HEAD", "acme-reports", "a.csv");
    assertThat(plain.uri(base, plain.virtualHosted(base, false)))
        .hasToString("https://acme-reports.s3.eu-central-1.amazonaws.com/a.csv");
    S3Request dotted = S3Request.of("HeadObject", "HEAD", "reports.acme.example", "a.csv");
    assertThat(dotted.uri(base, dotted.virtualHosted(base, false)))
        .hasToString("https://s3.eu-central-1.amazonaws.com/reports.acme.example/a.csv");
  }

  @Test
  void otherProvidersAreReachedAtTheirEndpointWithPathStyleByDefault() throws Exception {
    Map<String, String> endpoints = Map.of(
        "https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com/", "https://0123456789abcdef0123456789abcdef"
            + ".r2.cloudflarestorage.com/media/a.csv",
        "https://S3.US-WEST-002.backblazeb2.com", "https://s3.us-west-002.backblazeb2.com/media/a.csv",
        "http://minio.internal:9000", "http://minio.internal:9000/media/a.csv",
        "https://abcdefghijklmnop.storage.supabase.co/storage/v1/s3", "https://abcdefghijklmnop.storage.supabase.co"
            + "/storage/v1/s3/media/a.csv",
        "https://storage.googleapis.com:443", "https://storage.googleapis.com/media/a.csv");
    for (Map.Entry<String, String> e : endpoints.entrySet()) {
      S3Settings s = settings(custom(e.getKey(), "auto"));
      assertThat(s.aws()).isFalse();
      assertThat(s.pathStyle()).as(e.getKey()).isTrue();
      URI base = s.base(CloudEndpoints.PRODUCTION);
      S3Request r = S3Request.of("GetObject", "GET", "media", "a.csv");
      assertThat(r.uri(base, r.virtualHosted(base, s.pathStyle()))).as(e.getKey()).hasToString(e.getValue());
    }
    ObjectNode virtual = (ObjectNode) JSON.readTree(custom("https://oss-cn-hangzhou.aliyuncs.com", "oss-cn-hangzhou"));
    virtual.put("pathStyle", false);
    S3Settings oss = S3Settings.of(new ConnectionConfig("c", "c", "s3", Env.DEV, 1, virtual, Set.of(), null));
    URI base = oss.base(CloudEndpoints.PRODUCTION);
    S3Request r = S3Request.of("GetObject", "GET", "media", "a.csv");
    assertThat(r.uri(base, r.virtualHosted(base, oss.pathStyle())))
        .hasToString("https://media.oss-cn-hangzhou.aliyuncs.com/a.csv");
    // An IP address can't lead with a bucket: path style.
    S3Settings ip = settings(custom("http://10.20.0.14:9000", "us-east-1"));
    URI ipBase = ip.base(CloudEndpoints.PRODUCTION);
    assertThat(S3Request.of("GetObject", "GET", "media", "a.csv").virtualHosted(ipBase, false)).isFalse();
  }

  @Test
  void aLongBucketListKeepsTheActionsAndDropsTheNames() throws Exception {
    List<String> buckets = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      buckets.add("acme-department-" + i + "-reports-archive-*");
    }
    ObjectNode c = (ObjectNode) JSON.readTree(aws("eu-central-1"));
    var list = c.putArray("buckets");
    buckets.forEach(list::add);
    ((ObjectNode) c.get("credentials")).put("roleArn", "arn:aws:iam::412398765012:role/gw-s3");
    S3Settings s = S3Settings.of(new ConnectionConfig("c", "c", "s3", Env.DEV, 1, c, Set.of(), null));
    assertThat(s.generatedPolicy()).isTrue();
    String policy = SessionPolicies.read(s, S3Tools.READ_OBJECT);
    assertThat(policy).contains("s3:GetObject", "arn:aws:s3:::*/*").doesNotContain("acme-department");
    assertThat(policy.length()).isLessThanOrEqualTo(2048);
    assertThat(SessionPolicies.read(s, S3Tools.LIST_BUCKETS)).contains("s3:ListAllMyBuckets")
        .doesNotContain("GetObject");
  }

  @Test
  void theFormsSchemaTellsAmazonS3FromOtherProviders() {
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema schema = registry.getSchema(SchemaLocation.of("urn:mcp-gateway:test:s3"),
        SchemaResources.of(S3Connector.class, "s3").load("config"));
    java.util.function.Function<String, Integer> errors = config -> schema.validate(JSON.readTree(config)).size();
    String key = "\"secretAccessKey\": \"set\"";
    assertThat(errors.apply("{\"region\": \"eu-central-1\", \"credentials\": {\"source\": \"accessKey\", "
        + "\"accessKeyId\": \"AKIAIOSFODNN7EXAMPLE\", " + key + "}}")).isZero();
    assertThat(errors.apply("{\"region\": \"eu-central-1\", \"credentials\": {\"source\": \"profile\", \"profile\": "
        + "\"stage\", \"roleArn\": \"arn:aws:iam::412398765012:role/gw-s3\", \"sessionPolicy\": \"generated\"}, "
        + "\"buckets\": [\"acme-*\"]}")).isZero();
    assertThat(errors.apply("{\"endpoint\": \"https://0123.r2.cloudflarestorage.com\", \"region\": \"auto\", "
        + "\"credentials\": {\"accessKeyId\": \"0123456789abcdef\", " + key + "}}")).isZero();
    assertThat(errors.apply("{\"endpoint\": \"http://minio.internal:9000\", \"region\": \"us-east-1\", \"pathStyle\": "
        + "true, \"credentials\": {\"source\": \"accessKey\", \"accessKeyId\": \"minio-reader\", " + key + "}}"))
        .isZero();
    assertThat(errors.apply("{\"endpoint\": \"https://0123.r2.cloudflarestorage.com\", \"region\": \"auto\", "
        + "\"credentials\": {\"accessKeyId\": \"abc123\", " + key + ", \"roleArn\": "
        + "\"arn:aws:iam::412398765012:role/x\"}}")).as("no role for another provider").isPositive();
    assertThat(errors.apply("{\"endpoint\": \"https://0123.r2.cloudflarestorage.com\", \"region\": \"auto\", "
        + "\"credentials\": {\"source\": \"profile\", \"profile\": \"r2\"}}"))
        .as("an access key for another provider").isPositive();
    assertThat(errors.apply("{\"region\": \"auto\", \"credentials\": {\"source\": \"accessKey\", \"accessKeyId\": "
        + "\"AKIAIOSFODNN7EXAMPLE\", " + key + "}}")).as("an AWS region for Amazon S3").isPositive();
    assertThat(errors.apply("{\"region\": \"eu-central-1\", \"credentials\": {\"source\": \"accessKey\", "
        + "\"accessKeyId\": \"abc123\", " + key + "}}")).as("an AWS access key for Amazon S3").isPositive();
    assertThat(errors.apply("{\"region\": \"eu-central-1\", \"credentials\": {\"source\": \"accessKey\", "
        + "\"accessKeyId\": \"AKIAIOSFODNN7EXAMPLE\", " + key + "}, \"unknown\": 1}")).isPositive();
  }
}
