package ai.msrv.gaterook.connectors.redshift;

import static ai.msrv.gaterook.connectors.redshift.FakeRedshift.body;
import static ai.msrv.gaterook.connectors.redshift.FakeRedshift.policyOf;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.cloudauth.CloudLocal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import mockwebserver3.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the Redshift form: the profiles and regions of this computer, and once the draft can sign in, its clusters,
 * Serverless workgroups, databases and secrets, against the fake Redshift, Redshift Serverless, Secrets Manager, Data
 * API and STS. A Serverless workgroup or a paused cluster is never woken for a suggestion.
 */
class RedshiftSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-redshift";
  private static final String SESSION = "arn:aws:sts::412398765012:assumed-role/gw-redshift/gaterook";
  private static final String WG_ARN = "arn:aws:redshift-serverless:eu-central-1:412398765012:workgroup/"
      + "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b";

  @TempDir
  Path home;

  private FakeRedshift aws;
  private CloudCredentials cloud;
  private RedshiftConnector redshift;
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", "test-only-secret-key");

  @BeforeEach
  void start() throws Exception {
    aws = new FakeRedshift();
    URI base = aws.cloud.endpoints().awsStsEndpoint();
    Path dir = Files.createDirectories(home.resolve(".aws"));
    Files.writeString(dir.resolve("config"), """
        [default]
        region = eu-central-1
        [profile tool]
        credential_process = %s
        """.formatted(home.resolve("tool")));
    Files.writeString(home.resolve("tool"), "#!/bin/sh\necho ran > " + home.resolve("ran") + "\n");
    home.resolve("tool").toFile().setExecutable(true);
    CloudEndpoints endpoints = new CloudEndpoints(null, null, null, base, dir.resolve("config"),
        dir.resolve("credentials"), Map.of("aws.redshift-data", base, "aws.redshift", base, "aws.redshift-serverless",
            base, "aws.secretsmanager", base));
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
    redshift = new RedshiftConnector(cloud, new CloudLocal(endpoints, Map.<String, String>of()::get, home));
    aws.on("DescribeClusters", r -> FakeRedshift.xml("""
        <DescribeClustersResponse xmlns="http://redshift.amazonaws.com/doc/2012-12-01/"><DescribeClustersResult>
          <Clusters>
            <Cluster><ClusterIdentifier>acme-analytics</ClusterIdentifier><NodeType>ra3.xlplus</NodeType>
              <ClusterStatus>available</ClusterStatus><ClusterAvailabilityStatus>Available</ClusterAvailabilityStatus>
              <NumberOfNodes>2</NumberOfNodes><DBName>dev</DBName></Cluster>
            <Cluster><ClusterIdentifier>acme-etl</ClusterIdentifier><NodeType>dc2.large</NodeType>
              <ClusterStatus>paused</ClusterStatus><ClusterAvailabilityStatus>Unavailable</ClusterAvailabilityStatus>
              <NumberOfNodes>1</NumberOfNodes><DBName>etl</DBName></Cluster>
          </Clusters></DescribeClustersResult><ResponseMetadata><RequestId>r1</RequestId></ResponseMetadata>
        </DescribeClustersResponse>"""));
    aws.on("ListWorkgroups", "{\"workgroups\":[{\"workgroupName\":\"acme-wg\",\"workgroupArn\":\"" + WG_ARN + "\","
        + "\"namespaceName\":\"acme-ns\",\"status\":\"AVAILABLE\"}]}");
    aws.on("ListNamespaces", "{\"namespaces\":[{\"namespaceName\":\"acme-ns\",\"dbName\":\"analytics\"}]}");
    aws.on("ListDatabases", "{\"Databases\":[\"dev\",\"sales\"]}");
  }

  @AfterEach
  void stop() {
    redshift.close();
    cloud.close();
    aws.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "redshift", Env.DEV, 0, JSON.readTree(json),
        Set.of("/credentials/secretAccessKey"), null);
  }

  private Suggestions suggest(String json, Suggestions.Scope scope) {
    return redshift.suggest(draft(json), scope == Suggestions.Scope.LOCAL ? new TestSecrets() : secrets,
        TestCtx.of(), scope);
  }

  private static String key() {
    return "\"credentials\":{\"source\":\"accessKey\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}";
  }

  @Test
  void locallyTheProfileAndRegionAndASignInThatCantWorkOnServerless() {
    Suggestions s = suggest("{\"credentials\":{\"source\":\"profile\"},\"target\":{\"kind\":\"serverless\"},"
        + "\"databaseAuth\":{\"method\":\"dbUser\"}}", Suggestions.Scope.LOCAL);

    assertThat(s.fields().get(RedshiftSuggestions.PROFILE).value()).isEqualTo("default");
    assertThat(s.fields().get(RedshiftSuggestions.REGION).value()).isEqualTo("eu-central-1");
    assertThat(s.notes()).containsExactly(RedshiftSettings.DB_USER_ON_SERVERLESS);
    assertThat(aws.requests).isEmpty();
  }

  @Test
  void signedInItListsTheWarehousesAndTheClustersDatabases() {
    Suggestions s = suggest("""
        {"region":"eu-central-1","target":{"kind":"cluster","cluster":"acme-analytics"},"database":"dev",
         "credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE","roleArn":"%s",
         "externalId":"gw-ext-0123456789ab"}}""".formatted(ROLE), Suggestions.Scope.SERVICE);

    Suggestions.Field clusters = s.fields().get(RedshiftSuggestions.CLUSTER);
    assertThat(clusters.options()).extracting(Option::value, Option::detail).containsExactly(
        org.assertj.core.groups.Tuple.tuple("acme-analytics", "available · ra3.xlplus × 2"),
        org.assertj.core.groups.Tuple.tuple("acme-etl", "paused · dc2.large × 1"));
    assertThat(clusters.value()).isNull();
    // A workgroup is offered by its ARN, so the session policy can name it; the name is its label.
    Suggestions.Field workgroups = s.fields().get(RedshiftSuggestions.WORKGROUP);
    assertThat(workgroups.options()).singleElement().satisfies(o -> {
      assertThat(o.value()).isEqualTo(WG_ARN);
      assertThat(o.label()).isEqualTo("acme-wg");
    });
    Suggestions.Field databases = s.fields().get(RedshiftSuggestions.DATABASE);
    assertThat(databases.options()).extracting(Option::value).containsExactly("dev", "sales");
    assertThat(databases.value()).isNull();
    assertThat(s.notes()).containsExactly("Signed in as " + SESSION + ". 2 databases on acme-analytics: choose the "
        + "one the Data API connects to.");
    // One session that lists and signs in as the form says; no statement runs.
    String policy = policyOf(aws.to("AssumeRole").getFirst());
    assertThat(aws.to("AssumeRole")).hasSize(1);
    assertThat(policy).contains("redshift:DescribeClusters", "redshift-serverless:ListWorkgroups",
        "redshift-data:ListDatabases", "redshift:GetClusterCredentialsWithIAM")
        .doesNotContain("ExecuteStatement").doesNotContain("secretsmanager:GetSecretValue");
    assertThat(body(aws.to("ListDatabases").getFirst()).path("ClusterIdentifier").asString())
        .isEqualTo("acme-analytics");
    assertThat(aws.to("DescribeClusters").getFirst().getHeaders().get("Authorization"))
        .contains("/eu-central-1/redshift/aws4_request");
    assertThat(aws.to("ListWorkgroups").getFirst().getHeaders().get("X-Amz-Target"))
        .isEqualTo("RedshiftServerless.ListWorkgroups");
    assertThat(aws.requests).extracting(FakeRedshift::operation).allMatch(op -> Set.of("AssumeRole",
        "GetCallerIdentity", "DescribeClusters", "ListWorkgroups", "ListNamespaces", "ListDatabases").contains(op));
    assertThat(cloud.issued()).isEmpty();
  }

  @Test
  void theClusterIsListedWhileItIsStillEmpty() {
    // x-suggest: the core asks before Cluster identifier is typed.
    Suggestions s = suggest("{\"region\":\"eu-central-1\",\"target\":{\"kind\":\"cluster\"},\"database\":\"dev\","
        + key() + "}", Suggestions.Scope.SERVICE);

    assertThat(s.fields().get(RedshiftSuggestions.CLUSTER).options()).extracting(Option::value)
        .containsExactly("acme-analytics", "acme-etl");
    assertThat(s.notes()).containsExactly("Signed in as arn:aws:iam::412398765012:user/reader.");
    assertThat(aws.to("ListDatabases")).isEmpty();
  }

  @Test
  void aServerlessWorkgroupOffersItsNamespacesDatabaseWithoutWakingIt() {
    Suggestions s = suggest("{\"region\":\"eu-central-1\",\"target\":{\"kind\":\"serverless\",\"workgroup\":"
        + "\"acme-wg\"},\"database\":\"dev\"," + key() + "}", Suggestions.Scope.SERVICE);

    Suggestions.Field db = s.fields().get(RedshiftSuggestions.DATABASE);
    assertThat(db.value()).isEqualTo("analytics");
    assertThat(db.source()).isEqualTo("the namespace");
    assertThat(s.fields().get(RedshiftSuggestions.WORKGROUP).value()).isEqualTo(WG_ARN);
    assertThat(aws.to("ListDatabases")).isEmpty();
  }

  @Test
  void aPausedOrMissingClusterIsSaidAndNotResumed() {
    Suggestions paused = suggest("{\"region\":\"eu-central-1\",\"target\":{\"kind\":\"cluster\",\"cluster\":"
        + "\"acme-etl\"},\"database\":\"dev\"," + key() + "}", Suggestions.Scope.SERVICE);
    assertThat(paused.notes()).contains("Cluster acme-etl is paused: resume it to list its databases.");
    assertThat(paused.fields().get(RedshiftSuggestions.DATABASE).value()).isEqualTo("etl");
    assertThat(aws.to("ListDatabases")).isEmpty();

    Suggestions missing = suggest("{\"region\":\"eu-central-1\",\"target\":{\"kind\":\"cluster\",\"cluster\":"
        + "\"nope\"},\"database\":\"dev\"," + key() + "}", Suggestions.Scope.SERVICE);
    assertThat(missing.notes()).contains("There is no cluster nope in eu-central-1: choose one of acme-analytics, "
        + "acme-etl.");
  }

  @Test
  void aSecretSignInListsTheSecretsByNameNeverTheirValues() {
    aws.on("ListSecrets", """
        {"SecretList":[
          {"ARN":"arn:aws:secretsmanager:eu-central-1:412398765012:secret:app/api-key-Q1w2E3","Name":"app/api-key",
           "Description":"The API key of the app"},
          {"ARN":"arn:aws:secretsmanager:eu-central-1:412398765012:secret:redshift!acme-analytics-awsuser-AbC123",
           "Name":"redshift!acme-analytics-awsuser","OwningService":"redshift"},
          {"ARN":"arn:aws:secretsmanager:eu-central-1:412398765012:secret:redshift/gw-reader-AbCdEf",
           "Name":"redshift/gw-reader","Tags":[{"Key":"RedshiftDataFullAccess","Value":"true"}]}]}""");

    Suggestions s = suggest("{\"region\":\"eu-central-1\",\"target\":{\"kind\":\"cluster\",\"cluster\":"
        + "\"acme-analytics\"},\"database\":\"dev\",\"databaseAuth\":{\"method\":\"secret\",\"secretArn\":"
        + "\"redshift/gw-reader\"}," + key() + "}", Suggestions.Scope.SERVICE);

    Suggestions.Field secret = s.fields().get(RedshiftSuggestions.SECRET);
    // The one made for Redshift first; a name the form can't take (Redshift's own "redshift!…") is left out.
    assertThat(secret.options()).extracting(Option::label).containsExactly("redshift/gw-reader", "app/api-key");
    assertThat(secret.options().getFirst().value())
        .isEqualTo("arn:aws:secretsmanager:eu-central-1:412398765012:secret:redshift/gw-reader-AbCdEf");
    assertThat(secret.options().getFirst().detail()).isEqualTo("tagged RedshiftDataFullAccess");
    assertThat(secret.value()).isNull();
    assertThat(body(aws.to("ListDatabases").getFirst()).path("SecretArn").asString()).isEqualTo("redshift/gw-reader");
    assertThat(aws.to("GetSecretValue")).isEmpty();
  }

  @Test
  void listingsTheCredentialsMayNotReadAreLeftOut() {
    aws.on("DescribeClusters", r -> new MockResponse.Builder().code(403).addHeader("Content-Type", "text/xml")
        .body("<ErrorResponse xmlns=\"http://redshift.amazonaws.com/doc/2012-12-01/\"><Error><Type>Sender</Type>"
            + "<Code>AccessDenied</Code><Message>not authorized</Message></Error><RequestId>x</RequestId>"
            + "</ErrorResponse>").build());

    Suggestions s = suggest("{\"region\":\"eu-central-1\",\"target\":{\"kind\":\"cluster\",\"cluster\":"
        + "\"acme-analytics\"},\"database\":\"dev\"," + key() + "}", Suggestions.Scope.SERVICE);

    assertThat(s.fields()).doesNotContainKeys(RedshiftSuggestions.CLUSTER, RedshiftSuggestions.DATABASE);
    assertThat(s.fields()).containsKey(RedshiftSuggestions.WORKGROUP);
    assertThat(s.notes()).containsExactly("Signed in as arn:aws:iam::412398765012:user/reader.");
  }

  @Test
  void aProfileThatRunsACommandIsNeverSignedInWith() {
    Suggestions s = suggest("{\"region\":\"eu-central-1\",\"target\":{\"kind\":\"cluster\",\"cluster\":"
        + "\"acme-analytics\"},\"database\":\"dev\",\"credentials\":{\"source\":\"profile\",\"profile\":\"tool\"}}",
        Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("This profile runs a command to get credentials.",
        RedshiftSuggestions.NO_SIGN_IN);
    assertThat(aws.requests).isEmpty();
    assertThat(home.resolve("ran")).doesNotExist();
  }
}
