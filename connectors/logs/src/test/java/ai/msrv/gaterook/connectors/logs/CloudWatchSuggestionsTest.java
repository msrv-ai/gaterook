package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import tools.jackson.databind.JsonNode;

/**
 * Help for the AWS CloudWatch form: the profiles of ~/.aws/config with their regions on this computer, and once the
 * draft signs in, the log groups of its region, also while Log groups is still empty. A profile that runs a command for
 * its credentials is never used for a suggestion.
 */
class CloudWatchSuggestionsTest {

  private static final String SECRET_KEY = "cnry-cw-suggest-" + Long.toHexString(System.nanoTime());
  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile Function<JsonNode, MockResponse> describe = b -> json(200, "{\"logGroups\":[]}");
  private CloudCredentials cloud;
  private BackendConnector connector;

  @TempDir
  Path home;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String body = request.getBody() == null ? "" : request.getBody().utf8();
        if (body.contains("Action=GetCallerIdentity")) {
          return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(
              "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><GetCallerIdentityResult>"
              + "<Arn>arn:aws:iam::123456789012:user/gw-logs</Arn><UserId>AIDA1</UserId><Account>123456789012"
              + "</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
              + "</GetCallerIdentityResponse>").build();
        }
        String target = request.getHeaders().get("X-Amz-Target");
        if (target != null && target.endsWith(".DescribeLogGroups")) {
          return describe.apply(TestCtx.JSON.readTree(body.isEmpty() ? "{}" : body));
        }
        return json(400, "{\"__type\":\"UnknownOperationException\"}");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    // The credential command exists, as a real tool would: it must still never run for a suggestion.
    Path tool = Files.writeString(home.resolve("never-run.sh"), "#!/bin/sh\necho ran > " + home.resolve("ran")
        + "\necho '{\"Version\":1,\"AccessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\"SecretAccessKey\":\"x\"}'\n");
    tool.toFile().setExecutable(true);
    Path config = Files.writeString(home.resolve("aws-config"), """
        [default]
        region = eu-west-1
        [profile logs-dev]
        region = eu-central-1
        [profile sso-dev]
        sso_session = acme
        region = us-east-1
        [profile tool]
        credential_process = %s
        region = eu-central-1
        """.formatted(tool));
    URI base = server.url("/").uri();
    CloudEndpoints endpoints = new CloudEndpoints(null, null, null, base, config, null, Map.of("aws.logs", base));
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
    connector = new BackendConnector("cloudwatch", cloud, Clock.systemUTC(), new CloudLocal(endpoints, k -> null,
        home));
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  private Suggestions suggest(String json, Suggestions.Scope scope) throws Exception {
    ConnectionConfig cfg = new ConnectionConfig("draft-1", "draft", "cloudwatch", Env.DEV, 0,
        TestCtx.JSON.readTree(json), Set.of("/credentials/secretAccessKey"), null);
    return connector.suggest(cfg, new TestSecrets().with("/credentials/secretAccessKey", SECRET_KEY), TestCtx.of(),
        scope);
  }

  @Test
  void theProfilesOfThisComputerAndTheirRegionsAreOffered() throws Exception {
    Suggestions withKey = suggest("{\"backend\":\"cloudwatch\",\"credentials\":{\"source\":\"accessKey\"}}",
        Suggestions.Scope.LOCAL);
    Suggestions withProfile = suggest("{\"backend\":\"cloudwatch\",\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"logs-dev\"}}", Suggestions.Scope.LOCAL);

    assertThat(withKey.fields().get("/region").value()).as("the default profile's region").isEqualTo("eu-west-1");
    assertThat(withKey.fields().get("/region").source()).isEqualTo("~/.aws/config");
    assertThat(withKey.fields().get("/region").options()).extracting(Option::value).contains("eu-west-1",
        "eu-central-1", "us-east-1", "ap-southeast-2");
    assertThat(withKey.fields().get("/credentials/profile").value()).isEqualTo("default");
    assertThat(withKey.fields().get("/credentials/profile").options()).extracting(Option::value)
        .containsExactlyInAnyOrder("default", "logs-dev", "sso-dev", "tool").first().isEqualTo("default");
    assertThat(withKey.notes()).containsExactly("Found 4 profiles in ~/.aws/config: choose AWS profile under "
        + "Credential source to sign in with one.");
    assertThat(withProfile.fields().get("/region").value()).isEqualTo("eu-central-1");
    assertThat(withProfile.notes()).isEmpty();
    assertThat(requests).isEmpty();
  }

  @Test
  void aProfileGateRookCantUseSaysWhy() throws Exception {
    Suggestions sso = suggest("{\"backend\":\"cloudwatch\",\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"sso-dev\"}}", Suggestions.Scope.LOCAL);
    Suggestions command = suggest("{\"backend\":\"cloudwatch\",\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"tool\"}}", Suggestions.Scope.LOCAL);

    assertThat(sso.notes()).anyMatch(n -> n.startsWith("SSO profiles aren't supported yet"));
    assertThat(command.notes()).contains("This profile runs a command to get credentials.");
  }

  @Test
  void signedInItListsTheLogGroupsOfTheRegionAlsoBeforeOneIsNamed() throws Exception {
    describe = b -> b.has("nextToken")
        ? json(200, "{\"logGroups\":[{\"logGroupName\":\"/aws/lambda/billing\"}]}")
        : json(200, "{\"logGroups\":[{\"logGroupName\":\"/aws/ecs/api\",\"retentionInDays\":30,"
            + "\"storedBytes\":12582912},{\"logGroupName\":\"/aws/ecs/worker\"}],\"nextToken\":\"t-2\"}");

    Suggestions s = suggest("""
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":[],
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""", Suggestions.Scope.SERVICE);

    Suggestions.Field groups = s.fields().get("/logGroups");
    assertThat(groups.source()).isEqualTo("CloudWatch Logs");
    assertThat(groups.complete()).isTrue();
    assertThat(groups.value()).as("the person chooses what agents read").isNull();
    assertThat(groups.options()).extracting(Option::value)
        .containsExactly("/aws/ecs/api", "/aws/ecs/worker", "/aws/lambda/billing");
    assertThat(groups.options().getFirst().detail()).isEqualTo("kept 30 days · 12 MB");
    assertThat(groups.options().get(1).detail()).isEqualTo("kept forever");
    assertThat(s.notes()).containsExactly("Signed in as arn:aws:iam::123456789012:user/gw-logs. 3 log groups in "
        + "eu-central-1: choose the ones agents may read.");
    List<RecordedRequest> calls = requests.stream().filter(r -> r.getHeaders().get("X-Amz-Target") != null).toList();
    assertThat(calls).hasSize(2).allSatisfy(r -> {
      assertThat(r.getHeaders().get("X-Amz-Target")).isEqualTo("Logs_20140328.DescribeLogGroups");
      assertThat(r.getHeaders().get("Authorization")).contains("AKIAIOSFODNN7EXAMPLE/");
    });
  }

  @Test
  void aNamedLogGroupTheRegionDoesNotHaveIsANote() throws Exception {
    describe = b -> json(200, "{\"logGroups\":[{\"logGroupName\":\"/aws/ecs/api\"}]}");

    Suggestions s = suggest("""
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/aws/ecs/api","/aws/ecs/old"],
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""", Suggestions.Scope.SERVICE);

    assertThat(s.notes()).contains("Log group /aws/ecs/old isn't in eu-central-1.");
  }

  @Test
  void anAccountThatMayNotListLogGroupsIsToldWhy() throws Exception {
    describe = b -> json(400, "{\"__type\":\"AccessDeniedException\",\"message\":\"User is not authorized to perform: "
        + "logs:DescribeLogGroups\"}");

    Suggestions s = suggest("""
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/aws/ecs/api"],
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""", Suggestions.Scope.SERVICE);

    assertThat(s.fields()).doesNotContainKey("/logGroups");
    assertThat(s.notes()).singleElement().asString().startsWith("Signed in as arn:aws:iam::123456789012:user/gw-logs. "
        + "AWS denied DescribeLogGroups");
  }

  @Test
  void aProfileThatRunsACommandIsNeverUsedForASuggestion() throws Exception {
    Suggestions s = suggest("""
        {"backend":"cloudwatch","region":"eu-central-1","logGroups":[],
         "credentials":{"source":"profile","profile":"tool"}}""", Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("The profile tool runs a command to get credentials, which GateRook doesn't "
        + "do for suggestions: type the log groups, and Test connection checks them.");
    assertThat(requests).isEmpty();
    assertThat(home.resolve("ran")).doesNotExist();
  }

  private static MockResponse json(int status, String body) {
    return new MockResponse.Builder().code(status).addHeader("Content-Type", "application/x-amz-json-1.1").body(body)
        .build();
  }
}
