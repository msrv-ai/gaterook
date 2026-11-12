package ai.msrv.gaterook.connectors.athena;

import static ai.msrv.gaterook.connectors.athena.FakeAthena.body;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Help for the Athena form: the profiles and regions of this computer, and once the draft signs in, the workgroups,
 * the workgroup's results location, the data catalogs and the databases, against the fake Athena and STS. Nothing
 * runs a query.
 */
class AthenaSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-athena";

  @TempDir
  Path home;

  private FakeAthena athena;
  private CloudCredentials cloud;
  private AthenaConnector connector;
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", "test-only-secret-key");

  @BeforeEach
  void start() throws Exception {
    athena = new FakeAthena();
    URI base = athena.cloud.endpoints().awsStsEndpoint();
    Path aws = Files.createDirectories(home.resolve(".aws"));
    Files.writeString(aws.resolve("config"), """
        [default]
        region = eu-west-1
        [profile tool]
        credential_process = %s
        """.formatted(home.resolve("tool")));
    Files.writeString(home.resolve("tool"), "#!/bin/sh\necho ran > " + home.resolve("ran") + "\n");
    home.resolve("tool").toFile().setExecutable(true);
    CloudEndpoints endpoints = new CloudEndpoints(null, null, null, base, aws.resolve("config"),
        aws.resolve("credentials"), Map.of("aws.athena", base));
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
    connector = new AthenaConnector(cloud, new CloudLocal(endpoints, Map.<String, String>of()::get, home));
    athena.operation("ListWorkGroups", """
        {"WorkGroups":[
          {"Name":"primary","State":"ENABLED","EngineVersion":{"EffectiveEngineVersion":"Athena engine version 3"}},
          {"Name":"analysts","State":"ENABLED"},{"Name":"old","State":"DISABLED"}]}""");
    athena.operation("ListDataCatalogs", """
        {"DataCatalogsSummary":[{"CatalogName":"AwsDataCatalog","Type":"GLUE"},
          {"CatalogName":"dynamo","Type":"LAMBDA"}]}""");
    athena.operation("ListDatabases", """
        {"DatabaseList":[{"Name":"sales","Description":"Orders and invoices"},{"Name":"web"}]}""");
  }

  @AfterEach
  void stop() {
    connector.close();
    cloud.close();
    athena.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "athena", Env.DEV, 0, JSON.readTree(json),
        Set.of("/credentials/secretAccessKey"), null);
  }

  private Suggestions suggest(String json, Suggestions.Scope scope) {
    return connector.suggest(draft(json), scope == Suggestions.Scope.LOCAL ? new TestSecrets() : secrets,
        TestCtx.of(), scope);
  }

  @Test
  void theProfileAndItsRegionComeFromThisComputer() {
    Suggestions s = suggest("{\"credentials\":{\"source\":\"profile\"}}", Suggestions.Scope.LOCAL);

    assertThat(s.fields().get(AthenaSuggestions.PROFILE).value()).isEqualTo("default");
    assertThat(s.fields().get(AthenaSuggestions.REGION).value()).isEqualTo("eu-west-1");
    assertThat(athena.requests).isEmpty();
  }

  @Test
  void signedInItListsWorkgroupsCatalogsAndDatabasesWithoutRunningAQuery() {
    Suggestions s = suggest("""
        {"region":"eu-central-1","workgroup":"primary","credentials":{"source":"accessKey",
         "accessKeyId":"AKIAIOSFODNN7EXAMPLE","roleArn":"%s","externalId":"gw-ext-0123456789ab"}}""".formatted(ROLE),
        Suggestions.Scope.SERVICE);

    Suggestions.Field wg = s.fields().get(AthenaSuggestions.WORKGROUP);
    assertThat(wg.options()).extracting(Option::value).containsExactly("primary", "analysts", "old");
    assertThat(wg.options().getFirst().detail()).isEqualTo("enabled · Athena engine version 3");
    assertThat(wg.value()).as("two enabled workgroups: the person chooses").isNull();
    Suggestions.Field output = s.fields().get(AthenaSuggestions.OUTPUT);
    assertThat(output.options()).extracting(Option::value).containsExactly("s3://acme-athena-results/gaterook/");
    assertThat(output.value()).as("empty already means the workgroup's location").isNull();
    assertThat(s.fields().get(AthenaSuggestions.CATALOG).options()).extracting(Option::value, Option::detail)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("AwsDataCatalog", "GLUE"),
            org.assertj.core.groups.Tuple.tuple("dynamo", "LAMBDA"));
    Suggestions.Field databases = s.fields().get(AthenaSuggestions.DATABASES);
    assertThat(databases.options()).extracting(Option::value).containsExactly("sales", "web");
    assertThat(databases.options().getFirst().detail()).isEqualTo("Orders and invoices");
    assertThat(databases.source()).isEqualTo("AwsDataCatalog");
    assertThat(s.fields().get(AthenaSuggestions.DATABASE).value()).isNull();
    assertThat(s.notes()).containsExactly("Signed in as arn:aws:sts::412398765012:assumed-role/gw-athena/gaterook. "
        + "2 databases in AwsDataCatalog: choose the ones agents may use.");
    // One session that may only list: no query starts, no table or data is read.
    assertThat(athena.policies()).singleElement().asString().contains("athena:ListWorkGroups",
        "athena:ListDatabases", "glue:GetDatabase*").doesNotContain("StartQueryExecution", "s3:", "GetTable");
    assertThat(athena.started).isEmpty();
    assertThat(body(athena.to("ListDatabases").getFirst()).path("WorkGroup").asString()).isEqualTo("primary");
    assertThat(cloud.issued()).isEmpty();
  }

  @Test
  void aMissingWorkgroupAndOneWithoutResultsLocationAreSaid() {
    Suggestions missing = suggest("""
        {"region":"eu-central-1","workgroup":"reports","credentials":{"source":"accessKey",
         "accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""", Suggestions.Scope.SERVICE);

    assertThat(missing.notes()).contains("There is no workgroup reports in eu-central-1: choose one of primary, "
        + "analysts.");
    assertThat(athena.to("GetWorkGroup")).isEmpty();
    assertThat(body(athena.to("ListDatabases").getFirst()).has("WorkGroup")).isFalse();

    athena.operation("GetWorkGroup", FakeAthena.workgroup(null, false, null, false));
    Suggestions bare = suggest("""
        {"region":"eu-central-1","workgroup":"primary","databases":["sales"],"credentials":{"source":"accessKey",
         "accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""", Suggestions.Scope.SERVICE);

    assertThat(bare.notes()).contains("The workgroup primary has no query results location: enter one under Query "
        + "results location, such as s3://<bucket>/athena-results/.");
    assertThat(bare.fields()).doesNotContainKey(AthenaSuggestions.OUTPUT);
    // Only one database agents may use: it is the default one.
    assertThat(bare.fields().get(AthenaSuggestions.DATABASE).value()).isEqualTo("sales");
  }

  @Test
  void listingsTheCredentialsMayNotReadAreLeftOut() {
    athena.operation("ListWorkGroups", b -> FakeAthena.json(400, "{\"__type\":\"AccessDeniedException\","
        + "\"Message\":\"not authorized to perform athena:ListWorkGroups\"}"));
    athena.operation("ListDataCatalogs", b -> FakeAthena.json(400, "{\"__type\":\"AccessDeniedException\","
        + "\"Message\":\"not authorized\"}"));

    Suggestions s = suggest("""
        {"region":"eu-central-1","credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""",
        Suggestions.Scope.SERVICE);

    assertThat(s.fields()).doesNotContainKeys(AthenaSuggestions.WORKGROUP, AthenaSuggestions.CATALOG);
    assertThat(s.fields().get(AthenaSuggestions.DATABASES).options()).hasSize(2);
    assertThat(s.notes().getFirst()).startsWith("Signed in as arn:aws:iam::412398765012:user/reader. 2 databases");
  }

  @Test
  void aProfileThatRunsACommandIsNeverSignedInWith() {
    Suggestions s = suggest("{\"region\":\"eu-central-1\",\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"tool\"}}", Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("This profile runs a command to get credentials.",
        AthenaSuggestions.NO_SIGN_IN);
    assertThat(athena.requests).isEmpty();
    assertThat(home.resolve("ran")).doesNotExist();
  }
}
