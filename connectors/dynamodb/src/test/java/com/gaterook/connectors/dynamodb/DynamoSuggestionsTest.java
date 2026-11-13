package com.gaterook.connectors.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.CloudLocal;
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
import java.util.concurrent.ConcurrentHashMap;
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
 * Help for the DynamoDB form: the profiles and regions of this computer, and once the draft signs in, the tables of
 * its region, against a fake of STS and DynamoDB. A profile that runs a command is never used for a suggestion.
 */
class DynamoSuggestionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-dynamodb";
  private static final String SESSION_ARN = "arn:aws:sts::412398765012:assumed-role/gw-dynamodb/gaterook-draft";

  @TempDir
  Path home;

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> handlers = new ConcurrentHashMap<>();
  private CloudCredentials cloud;
  private DynamoDbConnector dynamo;
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", "test-only-secret-key");

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String op = operation(request);
        Function<RecordedRequest, MockResponse> h = handlers.get(op);
        if (h != null) {
          return h.apply(request);
        }
        return switch (op) {
          case "AssumeRole" -> xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<AssumeRoleResult><Credentials><AccessKeyId>ASIATESTSESSION00001</AccessKeyId><SecretAccessKey>"
              + "session-secret</SecretAccessKey><SessionToken>session-token</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
              + "</Credentials><AssumedRoleUser><AssumedRoleId>AROATEST:gaterook</AssumedRoleId><Arn>" + SESSION_ARN
              + "</Arn></AssumedRoleUser></AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId>"
              + "</ResponseMetadata></AssumeRoleResponse>");
          case "GetCallerIdentity" -> xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/"
              + "2011-06-15/\"><GetCallerIdentityResult><Arn>" + (String.valueOf(request.getHeaders()
              .get("Authorization")).contains("Credential=ASIA") ? SESSION_ARN
              : "arn:aws:iam::412398765012:user/reader") + "</Arn><UserId>AIDATEST</UserId><Account>412398765012"
              + "</Account></GetCallerIdentityResult><ResponseMetadata><RequestId>2</RequestId></ResponseMetadata>"
              + "</GetCallerIdentityResponse>");
          default -> new MockResponse.Builder().code(404).body("no fake for " + op).build();
        };
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getPort());
    Path aws = Files.createDirectories(home.resolve(".aws"));
    Files.writeString(aws.resolve("config"), """
        [default]
        region = eu-central-1
        [profile analytics]
        region = us-west-2
        [profile tool]
        credential_process = %s
        """.formatted(home.resolve("tool")));
    Files.writeString(home.resolve("tool"), "#!/bin/sh\necho ran > " + home.resolve("ran") + "\n");
    home.resolve("tool").toFile().setExecutable(true);
    CloudEndpoints endpoints = new CloudEndpoints(null, null, null, base, aws.resolve("config"),
        aws.resolve("credentials"), Map.of("aws.dynamodb", base));
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
    dynamo = new DynamoDbConnector(cloud, new CloudLocal(endpoints, Map.<String, String>of()::get, home));
  }

  @AfterEach
  void stop() throws IOException {
    dynamo.close();
    cloud.close();
    server.close();
  }

  private static String operation(RecordedRequest r) {
    String target = r.getHeaders().get("X-Amz-Target");
    if (target != null) {
      return target.substring(target.indexOf('.') + 1);
    }
    String body = r.getBody() == null ? "" : r.getBody().utf8();
    int at = body.indexOf("Action=");
    if (at < 0) {
      return r.getUrl().encodedPath();
    }
    int end = body.indexOf('&', at);
    return body.substring(at + 7, end < 0 ? body.length() : end);
  }

  private List<RecordedRequest> to(String operation) {
    return requests.stream().filter(r -> operation(r).equals(operation)).toList();
  }

  private static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", DynamoApi.CONTENT_TYPE).body(body).build();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "dynamodb", Env.DEV, 0, JSON.readTree(json),
        Set.of("/credentials/secretAccessKey"), null);
  }

  private Suggestions suggest(String json, Suggestions.Scope scope) {
    return dynamo.suggest(draft(json), scope == Suggestions.Scope.LOCAL ? new TestSecrets() : secrets, TestCtx.of(),
        scope);
  }

  @Test
  void theProfilesAndTheProfilesRegionComeFromThisComputer() {
    Suggestions key = suggest("{\"credentials\":{\"source\":\"accessKey\"}}", Suggestions.Scope.LOCAL);
    assertThat(key.fields()).doesNotContainKey(DynamoSuggestions.PROFILE);
    assertThat(key.fields().get("/region").value()).isEqualTo("eu-central-1");

    Suggestions profile = suggest("{\"credentials\":{\"source\":\"profile\"}}", Suggestions.Scope.LOCAL);
    assertThat(profile.fields().get(DynamoSuggestions.PROFILE).value()).isEqualTo("default");
    assertThat(profile.fields().get(DynamoSuggestions.PROFILE).options()).extracting(Option::value)
        .containsExactly("default", "analytics", "tool");

    Suggestions analytics = suggest("{\"credentials\":{\"source\":\"profile\",\"profile\":\"analytics\"}}",
        Suggestions.Scope.LOCAL);
    assertThat(analytics.fields().get("/region").value()).isEqualTo("us-west-2");
    assertThat(analytics.fields().get("/region").options()).extracting(Option::value).contains("eu-central-1",
        "ap-south-1");

    Suggestions tool = suggest("{\"credentials\":{\"source\":\"profile\",\"profile\":\"tool\"}}",
        Suggestions.Scope.LOCAL);
    assertThat(tool.notes()).containsExactly("This profile runs a command to get credentials.");
    assertThat(requests).isEmpty();
  }

  @Test
  void signedInItOffersTheTablesOfTheRegion() {
    handlers.put("ListTables", r -> json(200, "{\"TableNames\":[\"customers\",\"orders\",\"orders-2025\"]}"));

    Suggestions s = suggest("""
        {"region":"eu-central-1","tables":["orders","orders-*","orders-old"],"credentials":{"source":"accessKey",
         "accessKeyId":"AKIAIOSFODNN7EXAMPLE","roleArn":"%s","externalId":"gw-ext-0123456789ab"}}""".formatted(ROLE),
        Suggestions.Scope.SERVICE);

    Suggestions.Field tables = s.fields().get(DynamoSuggestions.TABLES);
    assertThat(tables.options()).extracting(Option::value).containsExactly("customers", "orders", "orders-2025");
    assertThat(tables.complete()).isTrue();
    assertThat(tables.value()).isNull();
    assertThat(tables.source()).isEqualTo("the account");
    assertThat(s.notes()).containsExactly("Signed in as " + SESSION_ARN + ". 3 tables in eu-central-1: choose the "
        + "ones agents may use.", "No table orders-old in eu-central-1.");
    // The test's read session: AWS allows ListTables and the reads, no write.
    String policy = URLDecoder.decode(to("AssumeRole").getFirst().getBody().utf8(), StandardCharsets.UTF_8);
    assertThat(policy).contains("dynamodb:ListTables").doesNotContain("PartiQLInsert").doesNotContain("PutItem");
    assertThat(to("ListTables").getFirst().getHeaders().get("Authorization"))
        .contains("Credential=ASIATESTSESSION00001/");
    assertThat(requests).extracting(DynamoSuggestionsTest::operation)
        .allMatch(op -> Set.of("AssumeRole", "GetCallerIdentity", "ListTables").contains(op));
    assertThat(cloud.issued()).isEmpty();
  }

  @Test
  void aLongListIsCutAtTwoPages() {
    handlers.put("ListTables", r -> {
      boolean second = r.getBody().utf8().contains("ExclusiveStartTableName");
      StringBuilder names = new StringBuilder();
      for (int i = 0; i < 100; i++) {
        names.append(i == 0 ? "" : ",").append("\"t").append(second ? 100 + i : i).append('"');
      }
      return json(200, "{\"TableNames\":[" + names + "],\"LastEvaluatedTableName\":\"t" + (second ? 199 : 99)
          + "\"}");
    });

    Suggestions s = suggest("""
        {"region":"eu-central-1","credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""",
        Suggestions.Scope.SERVICE);

    assertThat(s.fields().get(DynamoSuggestions.TABLES).options()).hasSize(200);
    assertThat(s.fields().get(DynamoSuggestions.TABLES).complete()).isFalse();
    assertThat(s.notes().getFirst()).endsWith("200+ tables in eu-central-1: choose the ones agents may use.");
    assertThat(to("ListTables")).hasSize(2);
  }

  @Test
  void aRefusedListIsANote() {
    handlers.put("ListTables", r -> json(400, "{\"__type\":\"com.amazon.coral.service#AccessDeniedException\","
        + "\"message\":\"User is not authorized to perform: dynamodb:ListTables\"}"));

    Suggestions s = suggest("""
        {"region":"eu-central-1","credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE"}}""",
        Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().asString()
        .startsWith("Signed in as arn:aws:iam::412398765012:user/reader. The tables can't be listed:")
        .contains("dynamodb:ListTables");
  }

  @Test
  void aProfileThatRunsACommandIsNeverSignedInWith() {
    Suggestions s = suggest("{\"region\":\"eu-central-1\",\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"tool\"}}", Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("This profile runs a command to get credentials.",
        DynamoSuggestions.NO_SIGN_IN);
    assertThat(requests).isEmpty();
    assertThat(home.resolve("ran")).doesNotExist();
  }
}
