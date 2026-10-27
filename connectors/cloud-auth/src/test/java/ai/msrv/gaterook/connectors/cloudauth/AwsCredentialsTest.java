package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

/** AssumeRole with ExternalId and a session policy against a fake STS speaking the Query protocol (report 09 §8). */
class AwsCredentialsTest {

  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-readonly";
  private static final String ASSUMED = "arn:aws:sts::412398765012:assumed-role/gw-readonly/gaterook-aws-stage";
  private static final CloudCredentials.Owner OWNER = new CloudCredentials.Owner("conn-7", 3, "aws-stage");

  private MockWebServer server;
  private CloudCredentials cloud;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile String assumeRoleError;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String body = request.getBody().utf8();
        if (body.contains("Action=AssumeRole")) {
          if (assumeRoleError != null) {
            return xml(403, "<ErrorResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><Error><Type>Sender"
                + "</Type><Code>" + assumeRoleError + "</Code><Message>no</Message></Error><RequestId>e</RequestId>"
                + "</ErrorResponse>");
          }
          return xml(200, "<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
              + "<Credentials><AccessKeyId>ASIAEXAMPLESESSION01</AccessKeyId><SecretAccessKey>cnry-session-secret"
              + "</SecretAccessKey><SessionToken>cnry-session-token</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration></Credentials>"
              + "<AssumedRoleUser><AssumedRoleId>AROAEXAMPLE:gaterook-aws-stage</AssumedRoleId><Arn>" + ASSUMED
              + "</Arn></AssumedRoleUser></AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId>"
              + "</ResponseMetadata></AssumeRoleResponse>");
        }
        if (body.contains("Action=GetCallerIdentity")) {
          return xml(200, "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<GetCallerIdentityResult><Arn>" + ASSUMED + "</Arn><UserId>AROAEXAMPLE:gaterook-aws-stage</UserId>"
              + "<Account>412398765012</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>2</RequestId>"
              + "</ResponseMetadata></GetCallerIdentityResponse>");
        }
        return xml(400, "<ErrorResponse><Error><Code>InvalidAction</Code></Error></ErrorResponse>");
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, server.url("/").uri(), null, null),
        Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    cloud.close();
    server.close();
  }

  @Test
  void assumesTheRoleWithExternalIdAndSessionPolicyAndSignsWithTheSessionKey() throws Exception {
    String policy = AwsSessionPolicy.builder()
        .allow(List.of("secretsmanager:GetSecretValue"), List.of("arn:aws:secretsmanager:eu-central-1:412398765012:secret:stage/*"))
        .toJson();
    AwsSpec spec = new AwsSpec(AwsSpec.Source.ACCESS_KEY, "AKIAEXAMPLEBASEKEY01", "/credentials/secretAccessKey",
        null, ROLE, "gw-ext-3f9a1c2b7d4e", 900, "eu-central-1");
    FakeSecrets secrets = new FakeSecrets().with("/credentials/secretAccessKey", "cnry-base-secret-key");
    List<CharSequence> taint = new ArrayList<>();

    AwsCredentialsProvider provider = cloud.aws(OWNER, spec, policy, secrets);
    CloudCredentials.CallerIdentity identity =
        cloud.callerIdentity(CloudCredentials.tainting(provider, taint::add), "eu-central-1");

    assertThat(identity.arn()).isEqualTo(ASSUMED);
    assertThat(identity.account()).isEqualTo("412398765012");
    assertThat(taint).extracting(CharSequence::toString).contains("cnry-session-secret", "cnry-session-token");

    RecordedRequest assume = requests.getFirst();
    String form = URLDecoder.decode(assume.getBody().utf8(), StandardCharsets.UTF_8);
    assertThat(form).contains("RoleArn=" + ROLE, "RoleSessionName=gaterook-aws-stage", "ExternalId=gw-ext-3f9a1c2b7d4e",
        "DurationSeconds=900", "Policy=" + policy);
    assertThat(assume.getHeaders().get("Authorization")).contains("Credential=AKIAEXAMPLEBASEKEY01/");
    RecordedRequest whoami = requests.get(1);
    assertThat(whoami.getHeaders().get("Authorization")).contains("Credential=ASIAEXAMPLESESSION01/");
    assertThat(whoami.getHeaders().get("X-Amz-Security-Token")).isEqualTo("cnry-session-token");
    assertThat(requests).noneMatch(r -> r.getBody().utf8().contains("cnry-base-secret-key"));

    assertThat(cloud.aws(OWNER, spec, policy, secrets)).as("cached").isSameAs(provider);
    assertThat(cloud.issued()).singleElement().satisfies(issued -> {
      assertThat(issued.mechanism()).isEqualTo("AWS AssumeRole");
      assertThat(issued.identity()).isEqualTo(ROLE);
      assertThat(issued.lastIssuedAt()).isNotNull();
      // Secrets › Short-lived: "AWS AssumeRole with ExternalId · ExternalId gw-ext-…".
      assertThat(issued.externalId()).isEqualTo("gw-ext-3f9a1c2b7d4e");
    });
    cloud.evict("conn-7");
    assertThat(cloud.issued()).isEmpty();
  }

  @Test
  void explainsADeniedRole() throws Exception {
    assumeRoleError = "AccessDenied";
    AwsSpec spec = new AwsSpec(AwsSpec.Source.ACCESS_KEY, "AKIAEXAMPLEBASEKEY01", "/s", null, ROLE, "gw-ext-1", 900,
        "eu-central-1");
    AwsCredentialsProvider provider = cloud.aws(OWNER, spec, null, new FakeSecrets().with("/s", "k"));

    assertThatThrownBy(() -> cloud.callerIdentity(provider, "eu-central-1"))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("trust policy");
  }

  @Test
  void stsClientsCanOnlyAskForCredentials() {
    try (StsClient sts = StsClient.builder().region(Region.EU_CENTRAL_1).httpClient(UrlConnectionHttpClient.create())
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIAEXAMPLEBASEKEY01", "x")))
        .endpointOverride(server.url("/").uri())
        .overrideConfiguration(o -> o.addExecutionInterceptor(new CloudCredentials.StsOperationGuard())).build()) {
      assertThatThrownBy(() -> sts.decodeAuthorizationMessage(r -> r.encodedMessage("m")))
          .isInstanceOf(SecurityException.class).hasMessageContaining("DecodeAuthorizationMessage");
      // Credential operations this class doesn't use are closed too.
      assertThatThrownBy(() -> sts.getFederationToken(r -> r.name("x")))
          .isInstanceOf(SecurityException.class).hasMessageContaining("GetFederationToken");
      assertThatThrownBy(() -> sts.getSessionToken(r -> r.durationSeconds(900)))
          .isInstanceOf(SecurityException.class).hasMessageContaining("GetSessionToken");
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void readsProfilesOfThisComputerAndWarnsAboutCommands() throws Exception {
    Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "aws-");
    Path config = Files.writeString(dir.resolve("config"), """
        [profile acme-stage]
        region = eu-central-1
        [profile runs-things]
        credential_process = /usr/local/bin/get-creds
        [profile sso]
        sso_session = acme
        [profile on-ec2]
        role_arn = arn:aws:iam::123456789012:role/reader
        credential_source = Ec2InstanceMetadata
        [profile in-env]
        role_arn = arn:aws:iam::123456789012:role/reader
        credential_source = Environment
        [profile web-identity]
        role_arn = arn:aws:iam::123456789012:role/reader
        web_identity_token_file = /var/run/secrets/token
        [profile chained]
        role_arn = arn:aws:iam::123456789012:role/reader
        source_profile = on-ec2
        [profile loop]
        role_arn = arn:aws:iam::123456789012:role/reader
        source_profile = loop
        [profile assumes]
        role_arn = arn:aws:iam::123456789012:role/reader
        source_profile = acme-stage
        """);
    Path credentials = Files.writeString(dir.resolve("credentials"), """
        [acme-stage]
        aws_access_key_id = AKIAEXAMPLEPROFILE01
        aws_secret_access_key = cnry-profile-secret
        """);
    try (CloudCredentials local = new CloudCredentials(new CloudEndpoints(null, null, null, server.url("/").uri(),
        config, credentials), Clock.systemUTC())) {
      AwsSpec acme = new AwsSpec(AwsSpec.Source.PROFILE, null, null, "acme-stage", null, null, 900, "eu-central-1");
      AwsCredentials resolved = local.aws(OWNER, acme, null, new FakeSecrets()).resolveCredentials();
      assertThat(resolved.accessKeyId()).isEqualTo("AKIAEXAMPLEPROFILE01");
      assertThat(local.profileWarnings(acme)).isEmpty();

      AwsSpec process = new AwsSpec(AwsSpec.Source.PROFILE, null, null, "runs-things", null, null, 900, "eu-central-1");
      assertThat(local.profileWarnings(process)).containsExactly("This profile runs a command to get credentials.");

      AwsSpec sso = new AwsSpec(AwsSpec.Source.PROFILE, null, null, "sso", null, null, 900, "eu-central-1");
      assertThatThrownBy(() -> local.aws(OWNER, sso, null, new FakeSecrets())).hasMessageContaining("SSO");
      AwsSpec missing = new AwsSpec(AwsSpec.Source.PROFILE, null, null, "nope", null, null, 900, "eu-central-1");
      assertThatThrownBy(() -> local.aws(OWNER, missing, null, new FakeSecrets())).hasMessageContaining("no profile nope");

      // Never the instance metadata, the container or the environment, not even at the end of a chain.
      for (String[] refused : new String[][] {
          {"on-ec2", "the instance metadata of this computer (credential_source)"},
          {"in-env", "environment variables (credential_source)"},
          {"web-identity", "web_identity_token_file"},
          {"chained", "the instance metadata of this computer (credential_source)"},
          {"loop", "takes its keys from itself"}}) {
        AwsSpec spec = new AwsSpec(AwsSpec.Source.PROFILE, null, null, refused[0], null, null, 900, "eu-central-1");
        assertThatThrownBy(() -> local.aws(OWNER, spec, null, new FakeSecrets())).as(refused[0])
            .isInstanceOfSatisfying(ConnectorException.class, e -> {
              assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
              assertThat(e.getMessage()).contains(refused[1]).contains("access key with a role");
            });
        assertThat(local.profileWarnings(spec)).as(refused[0]).last().asString().contains(refused[1]);
      }
      // A profile that assumes a role itself: the SDK's own STS client, no ExternalId, no session policy.
      AwsSpec assumes = new AwsSpec(AwsSpec.Source.PROFILE, null, null, "assumes", null, null, 900, "eu-central-1");
      int before = server.getRequestCount();
      assertThatThrownBy(() -> local.aws(OWNER, assumes, null, new FakeSecrets()))
          .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
              .contains("assumes a role itself (role_arn)").contains("Role ARN of the connection"));
      assertThat(server.getRequestCount()).as("no STS call of its own").isEqualTo(before);
    }
  }

  @Test
  void sessionPoliciesStayWithinWhatStsAccepts() {
    String json = AwsSessionPolicy.builder().allow(List.of("ec2:Describe*", "cloudwatch:GetMetricData"), List.of())
        .deny(List.of("secretsmanager:GetSecretValue"), List.of()).toJson();
    assertThat(json).isEqualTo("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
        + "[\"ec2:Describe*\",\"cloudwatch:GetMetricData\"],\"Resource\":[\"*\"]},{\"Effect\":\"Deny\",\"Action\":"
        + "[\"secretsmanager:GetSecretValue\"],\"Resource\":[\"*\"]}]}");
    AwsSessionPolicy huge = AwsSessionPolicy.builder();
    for (int i = 0; i < 100; i++) {
      huge.allow(List.of("service" + i + ":Describe*"), List.of("arn:aws:service" + i + ":eu-central-1:*:thing/*"));
    }
    assertThatThrownBy(huge::toJson).hasMessageContaining("2048");
    assertThat(CloudCredentials.sessionName("aws stage/eu")).isEqualTo("gaterook-aws-stage-eu");
    assertThat(CloudCredentials.sessionName("x".repeat(100))).hasSize(64);
    assertThat(CloudCredentials.stsEndpoint("cn-north-1")).hasToString("https://sts.cn-north-1.amazonaws.com.cn");
  }

  private static MockResponse xml(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "text/xml").body(body).build();
  }
}
