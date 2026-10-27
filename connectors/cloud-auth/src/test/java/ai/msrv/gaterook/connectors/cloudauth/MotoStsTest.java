package ai.msrv.gaterook.connectors.cloudauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * STS through moto 5.2.4 (report 09 §8): AssumeRole with ExternalId and a session policy gives {@code ASIA…} keys
 * and an assumed-role identity. Profile {@code -Pcloud-it}; moto comes from {@code GW_MOTO_SERVER} or the cached venv
 * {@code ~/.cache/mcp-gateway-test/moto-5.2.4} (README, "Development").
 */
@Tag("cloud-it")
class MotoStsTest {

  private static Process moto;
  private static int port;

  @BeforeAll
  static void startMoto() throws Exception {
    if (TestServices.enabled()) {
      port = TestServices.port("GW_MOTO_PORT"); // moto of the Compose services: STS keeps no state worth a reset
      return;
    }
    Path binary = motoServer();
    if (!Files.isExecutable(binary)) {
      throw new IllegalStateException("moto_server not found at " + binary + ": python3 -m venv "
          + "~/.cache/mcp-gateway-test/moto-5.2.4 && ~/.cache/mcp-gateway-test/moto-5.2.4/bin/pip install "
          + "'moto[server]==5.2.4' (or set GW_MOTO_SERVER)");
    }
    try (ServerSocket probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = probe.getLocalPort();
    }
    Path log = Path.of("target", "moto.log").toAbsolutePath();
    moto = new ProcessBuilder(binary.toString(), "-H", "127.0.0.1", "-p", Integer.toString(port))
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      try (Socket ignored = new Socket("127.0.0.1", port)) {
        return;
      } catch (IOException notYet) {
        Thread.sleep(200);
      }
    }
    throw new IllegalStateException("moto didn't start on port " + port + "; see " + log);
  }

  @AfterAll
  static void stopMoto() {
    if (moto != null) {
      moto.destroy();
    }
  }

  @Test
  void assumesARoleWithExternalIdAndPolicy() throws Exception {
    try (CloudCredentials cloud = new CloudCredentials(new CloudEndpoints(null, null, null,
        URI.create("http://127.0.0.1:" + port), null, null), Clock.systemUTC())) {
      AwsSpec spec = new AwsSpec(AwsSpec.Source.ACCESS_KEY, "AKIAIOSFODNN7EXAMPLE", "/k", null,
          "arn:aws:iam::123456789012:role/gw-readonly", "gw-ext-3f9a1c2b7d4e", 900, "eu-central-1");
      String policy = AwsSessionPolicy.builder().allow(List.of("cloudwatch:GetMetricData", "ec2:Describe*"), List.of())
          .toJson();
      AwsCredentialsProvider provider = cloud.aws(new CloudCredentials.Owner("c", 1, "aws-stage"), spec, policy,
          new FakeSecrets().with("/k", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));

      AwsCredentials session = provider.resolveCredentials();
      assertThat(session).isInstanceOf(AwsSessionCredentials.class);
      assertThat(session.accessKeyId()).startsWith("ASIA");

      CloudCredentials.CallerIdentity identity = cloud.callerIdentity(provider, "eu-central-1");
      assertThat(identity.arn()).isEqualTo("arn:aws:sts::123456789012:assumed-role/gw-readonly/mcp-gateway-aws-stage");
    }
  }

  private static Path motoServer() {
    String env = System.getenv("GW_MOTO_SERVER");
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    return Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "moto-5.2.4", "bin", "moto_server");
  }
}
