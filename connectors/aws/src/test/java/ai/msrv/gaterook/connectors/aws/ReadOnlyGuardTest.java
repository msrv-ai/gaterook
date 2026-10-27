package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.cloudcontrol.CloudControlClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;

/**
 * Step C8 "Completion criteria": {@code deleteResource}/{@code putMetricData} → {@code SecurityException} before the
 * network (report 09 §1 layer 1). The clients are the connector's own ({@link Clients}), with credentials that would
 * sign: nothing reaches the server, and the credentials are never even resolved.
 */
class ReadOnlyGuardTest {

  private MockWebServer server;
  private Clients clients;
  private final AtomicInteger resolved = new AtomicInteger();
  private AwsRequestOverrideConfiguration signed;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    // Anything that does reach the server would get an answer: a refusal must come from the guard.
    for (int i = 0; i < 20; i++) {
      server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/x-amz-json-1.0")
          .body("{}").build());
    }
    clients = new Clients();
    AwsCredentialsProvider counting = () -> {
      resolved.incrementAndGet();
      return AwsBasicCredentials.create("AKIAZ7GWSTAGEDEMO000", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
    };
    signed = AwsRequestOverrideConfiguration.builder().credentialsProvider(counting).build();
  }

  @AfterEach
  void stop() {
    clients.close();
    server.close();
  }

  private URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getPort());
  }

  @Test
  void cloudControlChangesNeverLeaveTheGateway() {
    ConnectionConfig cfg = Configs.stage();
    CloudControlClient cc = clients.cloudControl(cfg, "eu-central-1", endpoint());

    blocked(() -> cc.deleteResource(b -> b.overrideConfiguration(signed).typeName("AWS::RDS::DBInstance")
        .identifier("stage-orders")), "DeleteResource");
    blocked(() -> cc.updateResource(b -> b.overrideConfiguration(signed).typeName("AWS::RDS::DBInstance")
        .identifier("stage-orders").patchDocument("[{\"op\":\"remove\",\"path\":\"/DeletionProtection\"}]")),
        "UpdateResource");
    blocked(() -> cc.createResource(b -> b.overrideConfiguration(signed).typeName("AWS::S3::Bucket")
        .desiredState("{}")), "CreateResource");
    blocked(() -> cc.cancelResourceRequest(b -> b.overrideConfiguration(signed).requestToken("t")),
        "CancelResourceRequest");

    assertThat(server.getRequestCount()).isZero();
    assertThat(resolved).hasValue(0);
  }

  @Test
  void cloudWatchWritesNeverLeaveTheGateway() {
    ConnectionConfig cfg = Configs.stage();
    CloudWatchClient cw = clients.cloudWatch(cfg, "eu-central-1", endpoint());

    blocked(() -> cw.putMetricData(b -> b.overrideConfiguration(signed).namespace("MyApp")
        .metricData(m -> m.metricName("Orders").value(1.0))), "PutMetricData");
    blocked(() -> cw.setAlarmState(b -> b.overrideConfiguration(signed).alarmName("stage-orders-cpu")
        .stateValue(StateValue.OK).stateReason("x")), "SetAlarmState");
    blocked(() -> cw.deleteAlarms(b -> b.overrideConfiguration(signed).alarmNames(List.of("stage-orders-cpu"))),
        "DeleteAlarms");
    blocked(() -> cw.putMetricAlarm(b -> b.overrideConfiguration(signed).alarmName("x").evaluationPeriods(1)
        .comparisonOperator("GreaterThanThreshold").threshold(1.0)), "PutMetricAlarm");
    blocked(() -> cw.disableAlarmActions(b -> b.overrideConfiguration(signed).alarmNames(List.of("x"))),
        "DisableAlarmActions");

    assertThat(server.getRequestCount()).isZero();
    assertThat(resolved).hasValue(0);
  }

  @Test
  void theFiveReadsPass() throws Exception {
    ConnectionConfig cfg = Configs.stage();
    CloudControlClient cc = clients.cloudControl(cfg, "eu-central-1", endpoint());
    cc.listResources(b -> b.overrideConfiguration(signed).typeName("AWS::SQS::Queue"));

    assertThat(server.getRequestCount()).isEqualTo(1);
    assertThat(server.takeRequest().getHeaders().get("X-Amz-Target")).isEqualTo("CloudApiService.ListResources");
    assertThat(ReadOnlyGuard.OPERATIONS).containsExactlyInAnyOrder("ListResources", "GetResource", "GetMetricData",
        "ListMetrics", "DescribeAlarms");
  }

  @Test
  void aRequestWithoutItsCallsCredentialsDoesntGoOut() {
    CloudControlClient cc = clients.cloudControl(Configs.stage(), "eu-central-1", endpoint());

    assertThatThrownBy(() -> cc.listResources(b -> b.typeName("AWS::SQS::Queue")))
        .hasMessageContaining("credentials of its call");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void noApacheOrNettyHttpClientIsOnTheClasspath() {
    // ARCHITECTURE.md §1: every SDK client runs on url-connection-client; the root pom excludes the others.
    for (String name : List.of("software.amazon.awssdk.http.apache5.Apache5HttpClient",
        "software.amazon.awssdk.http.apache.ApacheHttpClient",
        "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient",
        "software.amazon.awssdk.http.crt.AwsCrtHttpClient", "software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient")) {
      assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
    }
  }

  private static void blocked(Executable call, String operation) {
    assertThatThrownBy(call::execute).as(operation).isInstanceOfSatisfying(SecurityException.class,
        e -> assertThat(e.getMessage()).contains(operation).contains("only reads"));
  }
}
