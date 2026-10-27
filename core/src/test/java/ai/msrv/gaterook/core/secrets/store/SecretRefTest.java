package ai.msrv.gaterook.core.secrets.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The reference syntax of report 12 §4.2: one canonical spelling, and nothing a lenient URL parser would let by. */
class SecretRefTest {

  @Test
  void vaultReferencesKeepPathVersionAndKey() {
    SecretRef.Vault ref = (SecretRef.Vault) SecretRef.parse("vault://team/kv/app/x?version=3#api_key");
    assertThat(ref.path()).isEqualTo("team/kv/app/x");
    assertThat(ref.version()).isEqualTo(3);
    assertThat(ref.key()).isEqualTo("api_key");
    assertThat(ref.kind()).isEqualTo(StoreKind.VAULT);
    assertThat(ref.pinned()).isTrue();
    assertThat(ref.credsPath()).isFalse();
    assertThat(ref.uri()).isEqualTo("vault://team/kv/app/x?version=3#api_key");
  }

  @Test
  void theDraftOrderKeyThenQueryIsStoredQueryFirst() {
    assertThat(SecretRef.parse("vault://kv/stage/db#password?version=2").uri())
        .isEqualTo("vault://kv/stage/db?version=2#password");
    assertThat(SecretRef.parse("  vault://kv/stage/db#password  ").uri()).isEqualTo("vault://kv/stage/db#password");
  }

  @Test
  void dynamicCredentialsAreACredsPathWithoutKeyOrVersion() {
    assertThat(((SecretRef.Vault) SecretRef.parse("vault://database/creds/prod-replica-readonly")).credsPath())
        .isTrue();
    assertThat(((SecretRef.Vault) SecretRef.parse("vault://team/database/creds/ro")).credsPath()).isTrue();
    assertThat(((SecretRef.Vault) SecretRef.parse("vault://database/creds/ro#password")).credsPath()).isFalse();
    assertThat(((SecretRef.Vault) SecretRef.parse("vault://database/creds/ro/x")).credsPath()).isFalse();
    assertThat(((SecretRef.Vault) SecretRef.parse("vault://creds/x")).credsPath()).isFalse();
  }

  @Test
  void gcpReferencesNameAVersionOrLatest() {
    SecretRef.GcpSm ref = (SecretRef.GcpSm) SecretRef.parse(
        "gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest");
    assertThat(ref.project()).isEqualTo("acme-stage");
    assertThat(ref.secret()).isEqualTo("billing-api-token");
    assertThat(ref.version()).isEqualTo("latest");
    assertThat(ref.pinned()).isFalse();
    assertThat(ref.resourceName()).isEqualTo("projects/acme-stage/secrets/billing-api-token/versions/latest");

    SecretRef.GcpSm regional = (SecretRef.GcpSm) SecretRef.parse(
        "gcp-sm://projects/acme-stage/locations/europe-west1/secrets/db/versions/4#password");
    assertThat(regional.location()).isEqualTo("europe-west1");
    assertThat(regional.pinned()).isTrue();
    assertThat(regional.secretName()).isEqualTo("projects/acme-stage/locations/europe-west1/secrets/db");
    assertThat(regional.uri()).isEqualTo(
        "gcp-sm://projects/acme-stage/locations/europe-west1/secrets/db/versions/4#password");

    // Without a version: latest, spelled out in the stored form.
    assertThat(SecretRef.parse("gcp-sm://projects/acme-stage/secrets/db").uri())
        .isEqualTo("gcp-sm://projects/acme-stage/secrets/db/versions/latest");
  }

  @Test
  void awsReferencesByRegionAndNameOrByArn() {
    SecretRef.AwsSm ref = (SecretRef.AwsSm) SecretRef.parse("aws-sm://eu-central-1/stage/billing-api#webhook_secret");
    assertThat(ref.region()).isEqualTo("eu-central-1");
    assertThat(ref.name()).isEqualTo("stage/billing-api");
    assertThat(ref.secretId()).isEqualTo("stage/billing-api");
    assertThat(ref.key()).isEqualTo("webhook_secret");
    assertThat(ref.pinned()).isFalse();

    SecretRef.AwsSm arn = (SecretRef.AwsSm) SecretRef.parse(
        "aws-sm://arn:aws:secretsmanager:us-east-1:123456789012:secret:prod/db-AbCdEf?versionStage=AWSPREVIOUS");
    assertThat(arn.region()).isEqualTo("us-east-1");
    assertThat(arn.secretId()).isEqualTo("arn:aws:secretsmanager:us-east-1:123456789012:secret:prod/db-AbCdEf");
    assertThat(arn.pinned()).isTrue();
    assertThat(SecretRef.parse("aws-sm://eu-central-1/x?versionStage=AWSCURRENT").pinned()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", "kv/stage/db", "http://vault/kv", "VAULT://kv/x", "vault://kv", "vault:///kv/x", "vault://kv/x/",
      "vault://kv//x", "vault://kv/../x", "vault://kv/./x", "vault://kv/%2e%2e/x", "vault://kv/st age",
      "vault://kv/x?version=0", "vault://kv/x?version=01", "vault://kv/x?version=a", "vault://kv/x?ver=1",
      "vault://kv/x?version=1&version=2", "vault://kv/x?", "vault://kv/x#", "vault://kv/x#a/b", "vault://kv/x#a#b",
      "vault://kv/sécret", "vault://kv/x\u0000", "gcp-sm://acme/secrets/x", "gcp-sm://projects/acme-stage/x",
      "gcp-sm://projects/ACME/secrets/x", "gcp-sm://projects/acme-stage/secrets/x/versions/0",
      "gcp-sm://projects/acme-stage/secrets/x/versions/latest/extra", "gcp-sm://projects/acme-stage/secrets/x?v=1",
      "gcp-sm://projects/acme-stage/secrets/a.b", "aws-sm://stage/billing", "aws-sm://eu-central-1/",
      "aws-sm://eu-central-1//x", "aws-sm://EU/x", "aws-sm://eu-central-1/x?versionStage=A&versionId="
          + "0123456789abcdef0123456789abcdef", "aws-sm://eu-central-1/x?versionId=short",
      "aws-sm://arn:aws:s3:::bucket", "aws-sm://eu-central-1/x?foo=bar"})
  void refusesWhatIsNotOneCanonicalReference(String text) {
    assertThatThrownBy(() -> SecretRef.parse(text)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesNullAndOverlongText() {
    assertThatThrownBy(() -> SecretRef.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SecretRef.parse("vault://kv/" + "a".repeat(1100)))
        .hasMessageContaining("at most 1024");
  }

  @ParameterizedTest
  @CsvSource({
      "vault://kv/stage/db#password, kv/stage/db",
      "gcp-sm://projects/acme-stage/secrets/billing-api-token/versions/latest, billing-api-token",
      "aws-sm://eu-central-1/stage/billing-api#webhook_secret, stage/billing-api"})
  void displayNamesTheSecretNotItsValue(String text, String display) {
    assertThat(SecretRef.parse(text).display()).isEqualTo(display);
  }

  @Test
  void theCanonicalFormParsesToItself() {
    for (String t : new String[] {"vault://kv/stage/db?version=7#password",
        "gcp-sm://projects/123456/secrets/x_y/versions/9#k", "aws-sm://eu-central-1/a/b?versionStage=AWSPENDING#k"}) {
      SecretRef once = SecretRef.parse(t);
      assertThat(SecretRef.parse(once.uri())).isEqualTo(once);
    }
  }
}
