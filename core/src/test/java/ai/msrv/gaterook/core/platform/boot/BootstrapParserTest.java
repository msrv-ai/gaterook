package ai.msrv.gaterook.core.platform.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.TestGateway;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BootstrapParserTest {

  private static final String FILE_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static byte[] line(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String valid(String extra) {
    return "{\"v\":1,\"launchSecret\":\"" + TestGateway.LAUNCH_SECRET + "\",\"instanceNonce\":\"n0nce-AbCdEfGhIjKl\","
        + "\"port\":7465,\"publicPort\":7466" + extra + "}";
  }

  @Test
  void aCompleteLine() {
    Bootstrap bootstrap = BootstrapParser.parse(line(valid(",\"fileKey\":\"" + FILE_KEY
        + "\",\"protection\":\"os\",\"futureField\":true")));

    assertThat(bootstrap.port()).isEqualTo(7465);
    assertThat(bootstrap.publicPort()).isEqualTo(7466);
    assertThat(bootstrap.instanceNonce()).isEqualTo("n0nce-AbCdEfGhIjKl");
    assertThat(bootstrap.protection()).isEqualTo("os");
    assertThat(bootstrap.dev()).isFalse();
    assertThat(bootstrap.toString()).doesNotContain(TestGateway.LAUNCH_SECRET).doesNotContain(FILE_KEY);
    assertThat(bootstrap.takeFileKey()).hasValueSatisfying(key -> assertThat(key).hasSize(32));
    assertThat(bootstrap.takeFileKey()).as("the file key is handed out once").isEmpty();
  }

  @Test
  void theFileKeyIsOptional() {
    Bootstrap bootstrap = BootstrapParser.parse(line(valid(",\"fileKey\":null")));

    assertThat(bootstrap.takeFileKey()).isEmpty();
    assertThat(bootstrap.protection()).isEqualTo("off");
  }

  /** {@code FB FF BF}: {@code -_-_} in base64url, {@code +/+/} in standard Base64; padding is optional in both. */
  @ParameterizedTest
  @ValueSource(strings = {
      "-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_8",
      "-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_8=",
      "+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/8=",
      "+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/+/8",
  })
  void theFileKeyReadsInEitherBase64Alphabet(String encoded) {
    byte[] expected = new byte[32];
    for (int i = 0; i < expected.length; i++) {
      expected[i] = new byte[] {(byte) 0xfb, (byte) 0xff, (byte) 0xbf}[i % 3];
    }
    Bootstrap bootstrap = BootstrapParser.parse(line(valid(",\"fileKey\":\"" + encoded + "\",\"protection\":\"os\"")));

    assertThat(bootstrap.takeFileKey()).hasValue(expected);
    assertThat(Bootstrap.decodeFileKey(encoded.toCharArray())).as("the protect endpoint's decoder").isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"AAAA", "not base64!", "-_-_\u00e9-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_8"})
  void aFileKeyThatIsntThirtyTwoBytesIsRefusedWithoutQuotingIt(String encoded) {
    assertThatThrownBy(() -> Bootstrap.decodeFileKey(encoded.toCharArray()))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(encoded));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "not json",
      "[1,2]",
      "{\"v\":2}",
      "{\"v\":1,\"launchSecret\":\"c2hvcnQ\",\"instanceNonce\":\"n0nce-AbCdEfGhIjKl\",\"port\":7465,\"publicPort\":7466}",
      "{\"v\":1,\"launchSecret\":\"%s\",\"instanceNonce\":\"short\",\"port\":7465,\"publicPort\":7466}",
      "{\"v\":1,\"launchSecret\":\"%s\",\"instanceNonce\":\"n0nce-AbCdEfGhIjKl\",\"port\":7465,\"publicPort\":7465}",
      "{\"v\":1,\"launchSecret\":\"%s\",\"instanceNonce\":\"n0nce-AbCdEfGhIjKl\",\"port\":0,\"publicPort\":7466}",
      "{\"v\":1,\"launchSecret\":\"%s\",\"instanceNonce\":\"n0nce-AbCdEfGhIjKl\",\"port\":\"7465\",\"publicPort\":7466}",
      "{\"v\":1,\"launchSecret\":\"%s\",\"instanceNonce\":\"n0nce-AbCdEfGhIjKl\",\"port\":7465,\"publicPort\":7466,\"fileKey\":\"AAAA\"}",
      "{\"v\":1,\"launchSecret\":\"%s\",\"instanceNonce\":\"n0nce-AbCdEfGhIjKl\",\"port\":7465,\"publicPort\":7466,\"protection\":\"Bad Value\"}",
  })
  void invalidLinesAreRefusedWithoutQuotingThem(String json) {
    String text = json.formatted(TestGateway.LAUNCH_SECRET);

    assertThatThrownBy(() -> BootstrapParser.parse(line(text)))
        .isInstanceOfSatisfying(GatewayStartupException.class, e -> {
          assertThat(e.reason()).isEqualTo(StartupReason.BOOTSTRAP_INVALID);
          assertThat(e.getMessage()).doesNotContain(TestGateway.LAUNCH_SECRET);
        });
  }
}
