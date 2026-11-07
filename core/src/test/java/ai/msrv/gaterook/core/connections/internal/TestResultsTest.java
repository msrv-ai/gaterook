package ai.msrv.gaterook.core.connections.internal;

import static ai.msrv.gaterook.core.platform.Masker.MASK;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.SecretFingerprints;
import ai.msrv.gaterook.core.platform.Taint;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TestResultsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final Masker masker = new Masker();

  @Test
  void databaseNamesStayExactWhileDiagnosticsAndOtherDetailsRemainMasked() {
    Taint taint = new Taint();
    taint.add("ledger");
    JsonNode details = JSON.readTree("""
        {"databases":["ledger","ledger_mail","other"],"note":"connected to ledger",
         "nested":{"password":"ledger"},"discoveryComplete":true}
        """);
    String original = details.toString();
    TestResult source = TestResult.success(7, "server ledger", "ledger", null).withDetails(details)
        .withHint(new TestResult.Hint(TestResult.Severity.WARNING, "test-warning", null, "echo ledger"));

    TestResult result = TestResults.maskDatabaseDiscovery(source, masker, taint);

    assertThat(result.details().path("databases")).isEqualTo(details.path("databases"));
    assertThat(result.serverVersion()).isEqualTo("server " + MASK);
    assertThat(result.identity()).isEqualTo(MASK);
    assertThat(result.hints().getFirst().text()).isEqualTo("echo " + MASK);
    assertThat(result.details().path("note").asString()).isEqualTo("connected to " + MASK);
    assertThat(result.details().at("/nested/password").asString()).isEqualTo(MASK);
    assertThat(result.details().path("discoveryComplete").asBoolean()).isTrue();
    assertThat(source.details().toString()).isEqualTo(original);
  }

  @Test
  void anotherConnectionsFingerprintDoesNotChangeSelectableNames() {
    String name = "db-fingerprint-93f71a";
    SecretFingerprints.process().learnValue(name);
    JsonNode details = JSON.createObjectNode().set("databases",
        JSON.createArrayNode().add(name).add(name + "_mail"));
    TestResult source = TestResult.success(7, null, null, null).withDetails(details);

    TestResult result = TestResults.maskDatabaseDiscovery(source, masker, new Taint());

    assertThat(result.details().path("databases")).isEqualTo(details.path("databases"));
    assertThat(TestResults.mask(source, masker, new Taint()).details().path("databases").get(0).asString())
        .isEqualTo(MASK);
  }

  @Test
  void redisDatabaseIndexesKeepTheirNumericType() {
    Taint taint = new Taint();
    taint.add("12345");
    JsonNode details = JSON.readTree("{\"databases\":[0,1,12345]}");
    TestResult source = TestResult.success(7, null, null, null).withDetails(details);

    TestResult result = TestResults.maskDatabaseDiscovery(source, masker, taint);

    assertThat(result.details().path("databases")).isEqualTo(details.path("databases"));
    assertThat(result.details().path("databases").get(2).isIntegralNumber()).isTrue();
    assertThat(result.details().path("databases").get(2).asInt()).isEqualTo(12345);
  }

  @Test
  void ordinaryTestsAndFailedDiscoveryStillMaskDatabaseDetails() {
    Taint taint = new Taint();
    taint.add("ledger");
    JsonNode details = JSON.readTree("{\"databases\":[\"ledger\",\"ledger_mail\"]}");
    TestResult ordinary = TestResult.success(7, null, null, null).withDetails(details);
    TestResult failed = TestResult.failure("denied", "echo ledger", "technical ledger").withDetails(details);

    assertThat(TestResults.mask(ordinary, masker, taint).details().path("databases").toString())
        .isEqualTo("[\"" + MASK + "\",\"" + MASK + "_mail\"]");
    TestResult result = TestResults.maskDatabaseDiscovery(failed, masker, taint);
    assertThat(result.details().path("databases").get(0).asString()).isEqualTo(MASK);
    assertThat(result.error().message()).isEqualTo("echo " + MASK);
    assertThat(result.error().technical()).isEqualTo("technical " + MASK);
  }

  @Test
  void aPendingConfirmationDoesNotExposeDatabaseChoices() {
    Taint taint = new Taint();
    taint.add("ledger");
    JsonNode details = JSON.readTree("{\"databases\":[\"ledger\"]}");
    TestResult source = new TestResult(true, 7, null, null, null, List.of(), List.of(), null,
        new TestResult.Confirmation("host-key", "trust ledger", null, null), null, null, details);

    TestResult result = TestResults.maskDatabaseDiscovery(source, masker, taint);

    assertThat(result.details().path("databases").get(0).asString()).isEqualTo(MASK);
    assertThat(result.needsConfirmation().text()).isEqualTo("trust " + MASK);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "[\"ledger\",{\"password\":\"ledger\"}]",
      "[\"ledger\",-1]",
      "[\"ledger\",1.5]",
      "[\"ledger\",true]",
      "[\"ledger\",null]"
  })
  void malformedDatabaseChoicesReceiveNoMaskingExemption(String choices) {
    Taint taint = new Taint();
    taint.add("ledger");
    JsonNode details = JSON.readTree("{\"databases\":" + choices + "}");
    String original = details.toString();
    TestResult source = TestResult.success(7, null, null, null).withDetails(details);

    TestResult result = TestResults.maskDatabaseDiscovery(source, masker, taint);

    assertThat(result.details().path("databases").get(0).asString()).isEqualTo(MASK);
    assertThat(result.details().toString()).doesNotContain("ledger");
    assertThat(source.details().toString()).isEqualTo(original);
  }
}
