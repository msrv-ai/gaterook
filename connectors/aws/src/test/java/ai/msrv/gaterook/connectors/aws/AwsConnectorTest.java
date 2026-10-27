package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AwsConnectorTest {

  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final AwsConnector connector = new AwsConnector(cloud, Clock.fixed(Instant.parse(
      "2026-10-22T12:31:17Z"), ZoneOffset.UTC));

  @AfterEach
  void close() {
    connector.close();
    cloud.close();
  }

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("aws");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("AWS");
    assertThat(m.group()).isEqualTo("Cloud");
    assertThat(m.icon()).isEqualTo("cloud");
    assertThat(m.description()).isEqualTo("Resources, statuses and metrics, read with AssumeRole.");
    assertThat(m.configSchema().path("type").asString()).isEqualTo("object");
    assertThat(m.policySchema().path("x-policy-section").asString()).isEqualTo("cloud");
    assertThat(m.access().files()).contains("~/.aws/config", "~/.aws/credentials");
  }

  @Test
  void publishesTheToolsOfTheKit() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name).containsExactly("aws_describe", "aws_metrics");
    for (ToolDefinition t : tools) {
      assertThat(t.nature()).as(t.name()).isEqualTo(Nature.READ);
      assertThat(t.defaultMode()).as(t.name()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).as(t.name()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.outputSchema()).as(t.name() + " outputSchema").isNotNull();
      assertThat(t.annotations().readOnlyHint()).isTrue();
      assertThat(t.annotations().destructiveHint()).isFalse();
      assertThat(t.annotations().idempotentHint()).isTrue();
      assertThat(t.annotations().openWorldHint()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection").toString()).isEqualTo("{\"type\":\"string\"}");
      assertThat(t.inputSchema().at("/required").toString()).contains("\"connection\"");
    }
    assertThat(tools.get(0).description()).startsWith("Describe resources such as instances, load balancers and "
        + "queues");
    assertThat(tools.get(1).description()).startsWith("Read CloudWatch metrics of one resource");
  }

  @Test
  void anEndpointsToolsNameItsConnectionsAndRegions() {
    ConnectionConfig stage = Configs.stage();
    ConnectionConfig prod = Configs.prod();
    List<ToolDefinition> tools = connector.tools(new EndpointView("cloud", Env.STAGE, List.of(stage, prod),
        Map.of(), List.of()));

    ToolDefinition describe = tools.getFirst();
    assertThat(describe.description()).contains("Regions: aws-prod: eu-central-1 · 412398765099; aws-stage: "
        + "eu-central-1, eu-west-1 · 412398765012 acme-stage.");
    assertThat(describe.inputSchema().at("/properties/region/enum").toString())
        .isEqualTo("[\"eu-central-1\",\"eu-west-1\"]");
    assertThat(describe.outputSchema()).isEqualTo(connector.manifest().tools().getFirst().outputSchema());
  }

  @Test
  void executeWithoutAPolicyDecisionDoesntRun() {
    ConnectionConfig cfg = Configs.stage();
    ToolCall call = Configs.call("aws_describe", cfg.name(), "{\"type\":\"AWS::EC2::Instance\"}");

    assertThatThrownBy(() -> connector.execute(call, cfg, Configs.secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("no policy decision");
        });

    // A decision of another version of the connection is no decision either.
    connector.classify(call, cfg, Configs.policy(Env.STAGE));
    ConnectionConfig edited = new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), cfg.version() + 1,
        cfg.config(), cfg.secretFields(), cfg.metadata());
    assertThatThrownBy(() -> connector.execute(call, edited, Configs.secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void thePersonApprovesTheExactRequestsInAbsoluteTime() {
    ConnectionConfig cfg = Configs.stage();
    ActionPreview metrics = connector.describeAction(Configs.call("aws_metrics", cfg.name(), """
        {"metrics":[{"namespace":"AWS/RDS","metric":"CPUUtilization","dimensions":{"DBInstanceIdentifier":
         "stage-orders"}}],"expressions":[{"id":"e1","expression":"100 * m1 / 4"}],"from":"-1h"}"""), cfg);

    assertThat(metrics.text()).isEqualTo("""
        aws_metrics · aws-stage (AWS · eu-central-1 · 412398765012 acme-stage · stage)
        GetMetricData 2026-10-22T11:31:00Z → 2026-10-22T12:32:00Z (1 h) · period 60 s
        m1 = AWS/RDS CPUUtilization {DBInstanceIdentifier=stage-orders} Average
        e1 = 100 * m1 / 4""");
    JsonNode payload = metrics.payload();
    assertThat(payload.path("from").asString()).isEqualTo("2026-10-22T11:31:00Z");
    assertThat(payload.path("period").asInt()).isEqualTo(60);

    ActionPreview list = connector.describeAction(Configs.call("aws_describe", cfg.name(),
        "{\"type\":\"AWS::ECS::Service\",\"parent\":{\"Cluster\":\"stage\"},\"region\":\"eu-west-1\"}"), cfg);
    assertThat(list.text()).isEqualTo("""
        aws_describe · aws-stage (AWS · eu-west-1 · 412398765012 acme-stage · stage)
        ListResources AWS::ECS::Service in eu-west-1 ResourceModel={"Cluster":"stage"} · up to 5 pages, 25 \
        resources""");
    ActionPreview get = connector.describeAction(Configs.call("aws_describe", cfg.name(),
        "{\"type\":\"AWS::RDS::DBInstance\",\"identifier\":\"stage-orders\"}"), cfg);
    assertThat(get.text()).endsWith("GetResource AWS::RDS::DBInstance stage-orders in eu-central-1");
    assertThat(get.summary()).isEqualTo("aws_describe on aws-stage: AWS::RDS::DBInstance stage-orders");
  }

  @Test
  void describeConnectionTellsTheAgentWhatItCanAsk() throws Exception {
    JsonNode d = connector.describe(Configs.stage(), Configs.secrets(), TestCtx.of(), "AWS::ECS::Service")
        .orElseThrow();

    assertThat(d.path("regions").toString()).isEqualTo("[\"eu-central-1\",\"eu-west-1\"]");
    assertThat(d.path("account").asString()).isEqualTo("412398765012");
    assertThat(d.path("identity").asString()).isEqualTo(Configs.ROLE);
    assertThat(d.path("sessionPolicy").asString()).isEqualTo("read only, enforced by AWS");
    assertThat(d.at("/type/parentExample").asString()).contains("Cluster");
    assertThat(d.path("exampleTypes").toString()).contains("AWS::EC2::Instance");
  }
}
