package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * STS through moto 5.2.4 (report 09 §8, profile {@code -Pcloud-it}): the connector's AssumeRole — ExternalId, 900 s
 * and its generated read-only session policy — gets {@code ASIA…} keys from a real STS implementation, and every Cloud
 * Control and CloudWatch request is signed with them. moto has no Cloud Control and answers RPCv2 CBOR CloudWatch
 * with 404 (report 09 §1 #1), so those two stay the {@link FakeAws} of the other tests. moto comes from
 * {@code GW_MOTO_SERVER} or the cached venv {@code ~/.cache/mcp-gateway-test/moto-5.2.4} (README, "Development").
 */
@Tag("cloud-it")
class MotoAssumeRoleIT {

  private static Process moto;
  private static int port;

  private FakeAws apis;
  private CloudCredentials cloud;
  private AwsConnector connector;

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
    Path log = Path.of("target", "moto-aws.log").toAbsolutePath();
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

  @BeforeEach
  void start() throws Exception {
    apis = new FakeAws();
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, URI.create("http://127.0.0.1:" + port), null,
        null, Map.of("aws.cloudcontrol", apis.base, "aws.monitoring", apis.base)), Clock.systemUTC());
    connector = new AwsConnector(cloud, Clock.systemUTC());
    apis.on("ListResources", r -> FakeAws.json(200, "{\"ResourceDescriptions\":[" + FakeAws.resource("stage-orders",
        "{\"DBInstanceIdentifier\":\"stage-orders\",\"DBInstanceStatus\":\"available\"}") + "]}"));
    apis.on("ListMetrics", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("{\"Metrics\":[]}")));
    apis.on("DescribeAlarms", r -> FakeAws.cbor(200, TestCtx.JSON.readTree("{\"MetricAlarms\":[]}")));
  }

  @AfterEach
  void stop() {
    connector.close();
    cloud.close();
    apis.close();
  }

  private static ConnectionConfig stage() {
    return Configs.of("aws-stage", Env.STAGE, """
        {"regions":["eu-central-1"],"credentials":{"source":"accessKey","accessKeyId":"AKIAIOSFODNN7EXAMPLE",
         "roleArn":"arn:aws:iam::123456789012:role/gw-readonly","externalId":"gw-ext-3f9a1c2b7d4e"}}""");
  }

  @Test
  void theSessionOfMotoSignsEveryCloudControlRequest() throws Exception {
    ConnectionConfig cfg = stage();
    ToolCall call = Configs.call("aws_describe", cfg.name(), "{\"type\":\"AWS::RDS::DBInstance\"}");
    connector.classify(call, cfg, Configs.kitStage());
    TestCtx ctx = TestCtx.of();

    ToolResult r = OutputSchema.describe(connector.execute(call, cfg, Configs.secrets(), ctx));

    assertThat(r.structuredContent().path("count").asInt()).isEqualTo(1);
    RecordedRequest list = apis.to("ListResources").getFirst();
    String auth = list.getHeaders().get("Authorization");
    assertThat(auth).contains("Credential=ASIA").doesNotContain("AKIAIOSFODNN7EXAMPLE");
    String token = list.getHeaders().get("X-Amz-Security-Token");
    assertThat(token).isNotBlank();
    assertThat(ctx.taints).contains(token);
    assertThat(apis.to("AssumeRole")).as("STS was moto, not the fake").isEmpty();
  }

  @Test
  void testConnectionReportsTheAssumedRoleOfMoto() {
    TestResult r = connector.test(stage(), Configs.secrets(), TestCtx.of());

    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("arn:aws:sts::123456789012:assumed-role/gw-readonly/mcp-gateway-aws-stage");
    assertThat(r.metadataUpdate().path("account").asString()).isEqualTo("123456789012");
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.READ);
  }

  private static Path motoServer() {
    String env = System.getenv("GW_MOTO_SERVER");
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    return Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test", "moto-5.2.4", "bin", "moto_server");
  }
}
