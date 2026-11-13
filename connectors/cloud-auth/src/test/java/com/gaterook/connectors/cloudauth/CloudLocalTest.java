package com.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the cloud CLIs of this computer know, read for the setup forms: names and regions, never a key. */
class CloudLocalTest {

  @TempDir
  Path home;

  private CloudLocal local(Map<String, String> env) throws IOException {
    Path aws = Files.createDirectories(home.resolve(".aws"));
    Files.writeString(aws.resolve("config"), """
        [default]
        region = eu-central-1
        [profile staging]
        region = us-west-2
        role_arn = arn:aws:iam::123456789012:role/reader
        source_profile = default
        [profile noregion]
        output = json
        """);
    Files.writeString(aws.resolve("credentials"), """
        [default]
        aws_access_key_id = AKIAIOSFODNN7EXAMPLE
        aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
        """);
    CloudEndpoints endpoints = new CloudEndpoints(null, null, home.resolve("adc.json"), null,
        aws.resolve("config"), aws.resolve("credentials"));
    return new CloudLocal(endpoints, env::get, home);
  }

  @Test
  void awsProfilesComeWithTheirRegionsTheChosenOneFirst() throws IOException {
    CloudLocal local = local(Map.of("AWS_PROFILE", "staging"));

    assertThat(local.awsProfiles()).extracting(CloudLocal.AwsProfile::name)
        .containsExactly("staging", "default", "noregion");
    assertThat(local.awsRegion(null)).isEqualTo("us-west-2");
    assertThat(local.awsRegion("default")).isEqualTo("eu-central-1");
    assertThat(local.awsRegion("noregion")).isNull();

    Suggestions s = local.aws(Suggestions.builder(), "/credentials/profile", null, true, "/regions").build();
    assertThat(s.fields().get("/credentials/profile").value()).isEqualTo("staging");
    assertThat(s.fields().get("/regions").value()).isEqualTo("us-west-2");
    // The regions profiles use come first, then every other region with its name.
    assertThat(s.fields().get("/regions").options()).extracting(Option::value).startsWith("us-west-2", "eu-central-1");
    assertThat(s.fields().get("/regions").options()).anySatisfy(o -> {
      assertThat(o.value()).isEqualTo("ap-southeast-2");
      assertThat(o.detail()).isEqualTo("Asia Pacific (Sydney)");
    });
    assertThat(s.toString()).doesNotContain("AKIAIOSFODNN7EXAMPLE").doesNotContain("wJalrXUtnFEMI");
  }

  @Test
  void withoutADefaultProfileTheFirstProfilesRegionIsFilledIn() throws IOException {
    Path aws = Files.createDirectories(home.resolve(".aws"));
    Files.writeString(aws.resolve("config"), "[profile work]\nregion = ap-southeast-2\n");
    CloudLocal local = new CloudLocal(new CloudEndpoints(null, null, home.resolve("adc.json"), null,
        aws.resolve("config"), aws.resolve("none")), Map.<String, String>of()::get, home);

    Suggestions s = local.aws(Suggestions.builder(), null, null, true, "/regions").build();

    assertThat(s.fields().get("/regions").value()).isEqualTo("ap-southeast-2");
  }

  @Test
  void aNewAwsDraftWithoutAKeyTakesThisComputersProfiles() throws IOException {
    CloudLocal local = local(Map.of());
    Suggestions.Field source = local.preferAwsProfile(Suggestions.builder(), "/credentials/source", false).build()
        .fields().get("/credentials/source");
    assertThat(source.value()).isEqualTo("profile");
    assertThat(source.source()).isEqualTo("~/.aws/config");
    // An access key ID typed, or no profile on this computer: the source stays as it is.
    assertThat(local.preferAwsProfile(Suggestions.builder(), "/credentials/source", true).build().fields()).isEmpty();
    CloudLocal none = new CloudLocal(new CloudEndpoints(null, null, home.resolve("none.json"), null,
        home.resolve("none-config"), home.resolve("none-credentials")), Map.<String, String>of()::get, home);
    assertThat(none.preferAwsProfile(Suggestions.builder(), "/credentials/source", false).build().fields()).isEmpty();
  }

  @Test
  void anEnvironmentRegionStandsInForAProfileWithout() throws IOException {
    CloudLocal local = local(Map.of("AWS_REGION", "ap-south-1"));
    assertThat(local.awsRegion("noregion")).isEqualTo("ap-south-1");
  }

  @Test
  void gcloudsProjectComesFromTheActiveConfigurationThenTheSignIn() throws IOException {
    Path gcloud = Files.createDirectories(home.resolve(".config/gcloud/configurations"));
    Files.writeString(home.resolve(".config/gcloud/active_config"), "work\n");
    Files.writeString(gcloud.resolve("config_work"), "[core]\naccount = dev@example.test\nproject = example-dev\n");
    CloudLocal local = local(Map.of());
    assertThat(local.gcpProject()).isEqualTo("example-dev");

    Files.writeString(gcloud.resolve("config_work"), "[core]\naccount = dev@example.test\n");
    Files.writeString(home.resolve("adc.json"), "{\"type\":\"authorized_user\",\"quota_project_id\":\"example-billing\"}");
    assertThat(local.gcpProject()).isEqualTo("example-billing");
    assertThat(local.gcpSignedIn()).isTrue();
    assertThat(local(Map.of("GOOGLE_CLOUD_PROJECT", "from-env")).gcpProject()).isEqualTo("from-env");
    assertThat(CloudLocal.keyProject("{\"type\":\"service_account\",\"project_id\":\"keyed\"}")).isEqualTo("keyed");
    assertThat(CloudLocal.keyProject("not json")).isNull();
  }

  @Test
  void azureSubscriptionsComeFromTheCliProfileTheDefaultFirst() throws IOException {
    Path azure = Files.createDirectories(home.resolve(".azure"));
    Files.writeString(azure.resolve("azureProfile.json"), "﻿" + """
        {"installationId":"x","subscriptions":[
          {"id":"11111111-1111-1111-1111-111111111111","name":"Dev","state":"Enabled","isDefault":false,
           "tenantId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","environmentName":"AzureCloud"},
          {"id":"22222222-2222-2222-2222-222222222222","name":"Prod","state":"Enabled","isDefault":true,
           "tenantId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","environmentName":"AzureCloud"},
          {"id":"33333333-3333-3333-3333-333333333333","name":"Old","state":"Disabled","isDefault":false,
           "tenantId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}]}""");
    CloudLocal local = local(Map.of());

    assertThat(local.azureSubscriptionOptions()).extracting(Option::value)
        .containsExactly("22222222-2222-2222-2222-222222222222", "11111111-1111-1111-1111-111111111111");
    assertThat(local.azureSubscriptionOptions().get(0).label()).isEqualTo("Prod (default)");
    assertThat(local.azureTenantOptions()).singleElement().satisfies(o -> {
      assertThat(o.value()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      assertThat(o.detail()).isEqualTo("Prod, Dev");
    });
  }

  @Test
  void nothingOnThisComputerIsNothingSuggested() {
    CloudLocal local = new CloudLocal(new CloudEndpoints(null, null, home.resolve("none.json"), null,
        home.resolve("none-config"), home.resolve("none-credentials")), Map.<String, String>of()::get, home);
    assertThat(local.awsProfiles()).isEmpty();
    assertThat(local.awsRegion(null)).isNull();
    assertThat(local.gcpProject()).isNull();
    assertThat(local.gcpSignedIn()).isFalse();
    assertThat(local.azureSubscriptions()).isEmpty();
  }
}
