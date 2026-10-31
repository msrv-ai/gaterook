package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A secret any call was handed is masked in every later call, whatever connection that call is for. */
class SecretFingerprintsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void aSecretHandedOutOnceIsMaskedInEveryLaterCallWithoutItsTaint() {
    SecretFingerprints fingerprints = new SecretFingerprints();
    Masker masker = new Masker(fingerprints);
    String secret = "hunter2-prod-db";
    String line = "auth failed for " + secret + " at 12:00";
    assertThat(masker.text(line, new Taint(), MaskPolicy.SECRETS_ONLY).text()).as("before").isEqualTo(line);

    fingerprints.learnValue(secret);

    Masker.MaskedText masked = masker.text(line, new Taint(), MaskPolicy.SECRETS_ONLY);
    assertThat(masked.text()).isEqualTo("auth failed for " + Masker.MASK + " at 12:00");
    assertThat(masked.outcome().fields()).isEqualTo(1);
    assertThat(masked.outcome().byRule()).containsEntry("stored-secret", 1);
    String base64 = Base64.getEncoder().encodeToString(("x" + secret).getBytes(StandardCharsets.UTF_8));
    assertThat(masker.text("token " + base64, new Taint(), MaskPolicy.SECRETS_ONLY).text())
        .as("an encoding, at another alignment").doesNotContain(base64.substring(2, 12));
    assertThat(masker.text(secret + " twice " + secret, null, MaskPolicy.SECRETS_ONLY).text())
        .as("without any taint at all").isEqualTo(Masker.MASK + " twice " + Masker.MASK);
  }

  @Test
  void nearValuesAndUnknownSecretsStay() {
    SecretFingerprints fingerprints = new SecretFingerprints();
    fingerprints.learnValue("hunter2-prod-db");
    fingerprints.learnLogin("app", "s3cr3t-db-pass");
    Masker masker = new Masker(fingerprints);
    // Another character, one less, a capital the forms don't have: none is the secret.
    for (String text : List.of("hunter2-prod-dc", "hunter2-prod-d", "Hunter2-prod-db is another", "app:password",
        "a plain line about nothing")) {
      assertThat(masker.text(text, new Taint(), MaskPolicy.SECRETS_ONLY).text()).as(text).isEqualTo(text);
    }
    // Its upper case is a form of its own; the number of a login stays the password's forms only.
    assertThat(masker.text("HUNTER2-PROD-DB", new Taint(), MaskPolicy.SECRETS_ONLY).text()).isEqualTo(Masker.MASK);
    assertThat(masker.text("app logged in", new Taint(), MaskPolicy.SECRETS_ONLY).text()).isEqualTo("app logged in");
  }

  @Test
  void namesAndNumbersAreCheckedToo() {
    SecretFingerprints fingerprints = new SecretFingerprints();
    fingerprints.learnValue("84726193000");
    fingerprints.learnValue("sess-9f3ab27c41d0");
    Masker masker = new Masker(fingerprints);

    var tree = masker.tree(JSON.readTree("{\"pin\":84726193000,\"sess-9f3ab27c41d0\":{\"ttl\":60},\"other\":1}"),
        new Taint(), MaskPolicy.SECRETS_ONLY).tree();

    assertThat(tree.path("pin").asString()).isEqualTo(Masker.MASK);
    assertThat(tree.has("sess-9f3ab27c41d0")).isFalse();
    assertThat(tree.path(Masker.MASK).path("ttl").asInt()).isEqualTo(60);
    assertThat(tree.path("other").asInt()).isEqualTo(1);
  }

  @Test
  void theProcessFingerprintsAreOneSharedSet() {
    assertThat(SecretFingerprints.process()).isSameAs(SecretFingerprints.process());
  }
}
