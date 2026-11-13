package com.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.cloudauth.CloudLocal;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Help for the AWS form: the profiles and regions of this computer, and once the draft signs in, the account, its
 * alias and the regions enabled for it, against the fake STS, IAM and EC2. A profile that runs a command is never
 * used for a suggestion.
 */
class AwsSuggestionsTest {

  @TempDir
  Path home;

  private FakeAws aws;
  private CloudCredentials cloud;
  private AwsConnector connector;

  @BeforeEach
  void start() throws IOException {
    aws = new FakeAws();
    Path dir = Files.createDirectories(home.resolve(".aws"));
    Files.writeString(dir.resolve("config"), """
        [default]
        region = eu-central-1
        [profile reader]
        region = eu-west-1
        [profile assumer]
        role_arn = arn:aws:iam::412398765012:role/reader
        source_profile = default
        [profile tool]
        region = us-east-2
        credential_process = %s
        """.formatted(home.resolve("tool")));
    Files.writeString(dir.resolve("credentials"), """
        [default]
        aws_access_key_id = %s
        aws_secret_access_key = %s
        [reader]
        aws_access_key_id = %s
        aws_secret_access_key = %s
        """.formatted(Configs.ACCESS_KEY_ID, FakeAws.BASE_SECRET, Configs.ACCESS_KEY_ID, FakeAws.BASE_SECRET));
    // The command exists, as the AWS CLI's helpers do: a suggestion must still never run it.
    Path tool = home.resolve("tool");
    Files.writeString(tool, "#!/bin/sh\necho ran > " + home.resolve("ran") + "\n");
    tool.toFile().setExecutable(true);
    CloudEndpoints endpoints = new CloudEndpoints(null, null, null, aws.base, dir.resolve("config"),
        dir.resolve("credentials"), Map.of("aws.cloudcontrol", aws.base, "aws.monitoring", aws.base, "aws.api",
            aws.base));
    cloud = new CloudCredentials(endpoints, Clock.systemUTC());
    connector = new AwsConnector(cloud, Clock.systemUTC(), new CloudLocal(endpoints, Map.<String, String>of()::get,
        home));
  }

  @AfterEach
  void stop() {
    connector.close();
    cloud.close();
    aws.close();
  }

  private static ConnectionConfig draft(String json) {
    return new ConnectionConfig("draft-1", "draft", "aws", Env.DEV, 0, TestCtx.JSON.readTree(json),
        Set.of(Configs.SECRET_FIELD), null);
  }

  private Suggestions local(String json) {
    return connector.suggest(draft(json), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL);
  }

  private Suggestions service(String json) {
    return connector.suggest(draft(json), Configs.secrets(), TestCtx.of(), Suggestions.Scope.SERVICE);
  }

  /** The actions an AssumeRole's session policy allows. */
  private static List<String> allowed(RecordedRequest assumeRole) {
    List<String> out = new ArrayList<>();
    for (String pair : assumeRole.getBody().utf8().split("&")) {
      if (pair.startsWith("Policy=")) {
        JsonNode policy = TestCtx.JSON.readTree(URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8));
        for (JsonNode st : policy.path("Statement")) {
          if (st.path("Effect").asString("").equals("Allow")) {
            JsonNode actions = st.path("Action");
            if (actions.isString()) {
              out.add(actions.asString());
            }
            actions.forEach(a -> out.add(a.asString()));
          }
        }
      }
    }
    return out;
  }

  private void regionsAndAlias() {
    aws.on("ListAccountAliases", r -> FakeAws.xml("""
        <ListAccountAliasesResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/"><ListAccountAliasesResult>
          <IsTruncated>false</IsTruncated><AccountAliases><member>acme-stage</member></AccountAliases>
          </ListAccountAliasesResult><ResponseMetadata><RequestId>rq-iam</RequestId></ResponseMetadata>
          </ListAccountAliasesResponse>"""));
    aws.on("DescribeRegions", r -> FakeAws.xml("""
        <DescribeRegionsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"><requestId>rq-ec2</requestId>
          <regionInfo>
            <item><regionName>us-east-1</regionName><optInStatus>opt-in-not-required</optInStatus></item>
            <item><regionName>ap-south-1</regionName><optInStatus>opt-in-not-required</optInStatus></item>
            <item><regionName>eu-north-1</regionName><optInStatus>opt-in-not-required</optInStatus></item>
            <item><regionName>eu-west-1</regionName><optInStatus>opt-in-not-required</optInStatus></item>
            <item><regionName>eu-central-1</regionName><optInStatus>opt-in-not-required</optInStatus></item>
          </regionInfo></DescribeRegionsResponse>"""));
  }

  @Test
  void theProfilesAreOfferedAndThePreferredOneFilledInOnlyForAProfileSource() {
    Suggestions key = local("{\"regions\":[],\"credentials\":{\"source\":\"accessKey\"}}");
    // The default source with no key typed: this computer's profiles sign in, the source takes them.
    assertThat(key.fields()).doesNotContainKey(AwsSuggestions.PROFILE);
    assertThat(key.fields().get("/credentials/source").value()).isEqualTo("profile");
    assertThat(key.fields().get("/credentials/source").source()).isEqualTo("~/.aws/config");
    assertThat(key.fields().get("/regions").value()).isEqualTo("eu-central-1");
    assertThat(key.fields().get("/regions").source()).isEqualTo("~/.aws/config");
    // An access key ID typed: the person signs in with a key, the source stays.
    Suggestions typed = local("{\"regions\":[],\"credentials\":{\"source\":\"accessKey\","
        + "\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}}");
    assertThat(typed.fields()).doesNotContainKey("/credentials/source").doesNotContainKey(AwsSuggestions.PROFILE);

    Suggestions profile = local("{\"regions\":[],\"credentials\":{\"source\":\"profile\"}}");
    Suggestions.Field p = profile.fields().get(AwsSuggestions.PROFILE);
    assertThat(p.value()).isEqualTo("default");
    assertThat(p.options()).extracting(Option::value).containsExactly("default", "reader", "assumer", "tool");
    assertThat(p.options().get(1).detail()).isEqualTo("eu-west-1");

    Suggestions reader = local("{\"regions\":[],\"credentials\":{\"source\":\"profile\",\"profile\":\"reader\"}}");
    assertThat(reader.fields().get(AwsSuggestions.PROFILE).value()).isNull();
    assertThat(reader.fields().get("/regions").value()).isEqualTo("eu-west-1");
    assertThat(reader.fields().get("/regions").options()).anySatisfy(o -> {
      assertThat(o.value()).isEqualTo("ap-southeast-2");
      assertThat(o.detail()).isEqualTo("Asia Pacific (Sydney)");
    });
    assertThat(reader.notes()).isEmpty();
    // Nothing left this computer, and no key was read into a suggestion.
    assertThat(aws.requests).isEmpty();
    assertThat(profile.toString() + reader).doesNotContain(FakeAws.BASE_SECRET);
  }

  @Test
  void aProfileThatCantBeUsedSaysWhyWhileItIsChosen() {
    Suggestions assumer = local("{\"regions\":[\"eu-central-1\"],\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"assumer\"}}");
    assertThat(assumer.notes()).singleElement().asString().contains("assumes a role itself")
        .contains("Role ARN");

    Suggestions missing = local("{\"regions\":[\"eu-central-1\"],\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"nope\"}}");
    assertThat(missing.notes()).singleElement().asString().startsWith("There is no profile nope");
  }

  @Test
  void signedInItFillsInTheAccountAndAliasAndOffersTheEnabledRegions() {
    regionsAndAlias();

    Suggestions s = service("""
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"%s","roleArn":"%s",
         "externalId":"gw-ext-3f9a1c2b7d4e"}}""".formatted(Configs.ACCESS_KEY_ID, Configs.ROLE));

    Suggestions.Field account = s.fields().get(AwsSuggestions.ACCOUNT_ID);
    assertThat(account.value()).isEqualTo(FakeAws.ACCOUNT);
    assertThat(account.source()).isEqualTo("the credentials");
    assertThat(account.options().getFirst().detail()).isEqualTo(FakeAws.SESSION_ARN);
    assertThat(s.fields().get(AwsSuggestions.ALIAS).value()).isEqualTo("acme-stage");
    Suggestions.Field regions = s.fields().get("/regions");
    assertThat(regions.value()).isNull();
    // Near the connection's region first, then in the console's order.
    assertThat(regions.options()).extracting(Option::value)
        .containsExactly("eu-central-1", "eu-west-1", "eu-north-1", "us-east-1", "ap-south-1");
    assertThat(regions.options().get(1).detail()).isEqualTo("Europe (Ireland)");
    assertThat(s.notes()).containsExactly("Signed in as " + FakeAws.SESSION_ARN + ". 5 regions are enabled for "
        + "the account: add the ones agents may read.");
    // The role's session signs every read; IAM is global and signs for us-east-1.
    assertThat(aws.to("ListAccountAliases").getFirst().getHeaders().get("Authorization"))
        .contains("Credential=" + FakeAws.SESSION_KEY_ID + "/").contains("/us-east-1/iam/aws4_request");
    assertThat(aws.to("DescribeRegions").getFirst().getHeaders().get("Authorization"))
        .contains("/eu-central-1/ec2/aws4_request");
    // Each AssumeRole carried a read-only session policy: none allows a change.
    assertThat(aws.to("AssumeRole")).hasSize(3).allSatisfy(r -> assertThat(allowed(r)).isNotEmpty()
        .allMatch(a -> a.matches("[a-z0-9-]+:(Describe|List|Get|BatchGet|Search|Lookup)\\w*\\*?")));
    assertThat(aws.requests).extracting(FakeAws::operation)
        .allMatch(op -> Set.of("AssumeRole", "GetCallerIdentity", "ListAccountAliases", "DescribeRegions")
            .contains(op));
    // The draft's sessions went with the suggestion.
    assertThat(cloud.issued()).isEmpty();
    assertThat(connector.cachedClients()).isZero();
  }

  @Test
  void anotherAccountAndARegionNotEnabledAreSaid() {
    regionsAndAlias();

    Suggestions s = service("""
        {"regions":["eu-central-1","eu-south-2"],"accountId":"412398765099","credentials":{"source":"accessKey",
         "accessKeyId":"%s"}}""".formatted(Configs.ACCESS_KEY_ID));

    assertThat(s.notes()).contains("The credentials are for account 412398765012, not 412398765099: fix Account ID "
        + "or the credentials.", "eu-south-2 isn't enabled for the account: calls there fail.");
    assertThat(s.notes().getFirst()).startsWith("Signed in as " + FakeAws.USER_ARN + ".");
  }

  @Test
  void regionsStillEmptyOrHalfTypedAreAskedInTheCliRegion() {
    regionsAndAlias();
    // x-suggest: the core asks while Regions is empty, or holds what the person is still typing.
    for (String regions : new String[] {"[]", "[\"eu-cen\"]"}) {
      aws.requests.clear();

      Suggestions s = service("""
          {"regions":%s,"credentials":{"source":"accessKey","accessKeyId":"%s"}}"""
          .formatted(regions, Configs.ACCESS_KEY_ID));

      assertThat(s.fields().get("/regions").options()).extracting(Option::value)
          .startsWith("eu-central-1", "eu-west-1", "eu-north-1");
      assertThat(s.fields().get(AwsSuggestions.ACCOUNT_ID).value()).isEqualTo(FakeAws.ACCOUNT);
      assertThat(s.notes()).as(regions).containsExactly("Signed in as " + FakeAws.USER_ARN + ". 5 regions are "
          + "enabled for the account: add the ones agents may read.");
      // The default profile's region, as the AWS CLI would use it.
      assertThat(aws.to("DescribeRegions").getFirst().getHeaders().get("Authorization"))
          .contains("/eu-central-1/ec2/aws4_request");
    }
  }

  @Test
  void listsTheCredentialsMayNotReadAreLeftOut() {
    aws.on("ListAccountAliases", r -> new MockResponse.Builder().code(403).addHeader("Content-Type", "text/xml")
        .body("<ErrorResponse xmlns=\"https://iam.amazonaws.com/doc/2010-05-08/\"><Error><Type>Sender</Type>"
            + "<Code>AccessDenied</Code><Message>not authorized</Message></Error><RequestId>x</RequestId>"
            + "</ErrorResponse>").build());
    aws.on("DescribeRegions", r -> new MockResponse.Builder().code(403).addHeader("Content-Type", "text/xml")
        .body("<Response><Errors><Error><Code>UnauthorizedOperation</Code><Message>not authorized</Message>"
            + "</Error></Errors><RequestID>x</RequestID></Response>").build());

    Suggestions s = service("""
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"%s"}}"""
        .formatted(Configs.ACCESS_KEY_ID));

    assertThat(s.fields()).containsOnlyKeys(AwsSuggestions.ACCOUNT_ID);
    assertThat(s.notes()).containsExactly("Signed in as " + FakeAws.USER_ARN + ".");
  }

  @Test
  void refusedCredentialsAreANote() {
    aws.on("GetCallerIdentity", r -> FakeAws.stsError(403, "InvalidClientTokenId", "The security token included "
        + "in the request is invalid."));

    Suggestions s = service("""
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"%s"}}"""
        .formatted(Configs.ACCESS_KEY_ID));

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).singleElement().asString().contains("refused the access key");
  }

  @Test
  void aProfileThatRunsACommandIsNeverSignedInWith() {
    Suggestions s = service("{\"regions\":[\"us-east-2\"],\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"tool\"}}");

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("This profile runs a command to get credentials.",
        AwsSuggestions.NO_SIGN_IN);
    assertThat(aws.requests).isEmpty();
    assertThat(home.resolve("ran")).doesNotExist();
  }

  @Test
  void aProfileWithKeysSignsInAsTestConnectionDoes() {
    regionsAndAlias();

    Suggestions s = service("{\"regions\":[\"eu-west-1\"],\"credentials\":{\"source\":\"profile\","
        + "\"profile\":\"reader\"}}");

    assertThat(s.fields().get(AwsSuggestions.ACCOUNT_ID).value()).isEqualTo(FakeAws.ACCOUNT);
    // The area of the connection's region (eu-) first, the region itself left for the form to drop.
    assertThat(s.fields().get("/regions").options()).extracting(Option::value)
        .startsWith("eu-central-1", "eu-west-1", "eu-north-1");
    assertThat(aws.to("GetCallerIdentity").getFirst().getHeaders().get("Authorization"))
        .contains("Credential=" + Configs.ACCESS_KEY_ID + "/");
  }
}
