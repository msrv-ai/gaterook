package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Status and ARN of a resource beyond what Cloud Control gives (report 09 §1 #5, §9 "Resource masks"). */
class TypeCatalogTest {

  @Test
  void statusFromTheCatalogOrByName() {
    assertThat(TypeCatalog.status("AWS::EC2::Instance", json("{\"State\":{\"Code\":16,\"Name\":\"running\"}}")))
        .isEqualTo("running");
    assertThat(TypeCatalog.status("AWS::RDS::DBInstance", json("{\"DBInstanceStatus\":\"available\"}")))
        .isEqualTo("available");
    assertThat(TypeCatalog.status("AWS::EKS::Cluster", json("{\"Status\":\"ACTIVE\"}"))).isEqualTo("ACTIVE");
    assertThat(TypeCatalog.status("AWS::ElastiCache::CacheCluster", json("{\"CacheClusterStatus\":\"available\"}")))
        .isEqualTo("available");
    assertThat(TypeCatalog.status("AWS::EC2::VPC", json("{\"CidrBlock\":\"10.0.0.0/16\"}"))).isNull();
  }

  @Test
  void arnFromTheModelTheIdentifierOrTheTemplate() {
    String region = "eu-central-1";
    String account = "412398765012";
    assertThat(TypeCatalog.arn("AWS::SQS::Queue", "https://sqs/q", json("{\"Arn\":\"arn:aws:sqs:eu-central-1:1:q\"}"),
        region, account)).isEqualTo("arn:aws:sqs:eu-central-1:1:q");
    assertThat(TypeCatalog.arn("AWS::ECS::Service", "arn:aws:ecs:eu-central-1:1:service/stage/api|stage",
        json("{\"Name\":\"api\"}"), region, account)).isEqualTo("arn:aws:ecs:eu-central-1:1:service/stage/api");
    assertThat(TypeCatalog.arn("AWS::ECS::Service", "x", json("{\"ServiceArn\":\"arn:aws:ecs:r:1:service/s/x\"}"),
        region, account)).isEqualTo("arn:aws:ecs:r:1:service/s/x");
    assertThat(TypeCatalog.arn("AWS::EC2::Instance", "i-0abc", json("{}"), region, account))
        .isEqualTo("arn:aws:ec2:eu-central-1:412398765012:instance/i-0abc");
    assertThat(TypeCatalog.arn("AWS::EC2::Instance", "i-0abc", json("{}"), "cn-north-1", account))
        .isEqualTo("arn:aws-cn:ec2:cn-north-1:412398765012:instance/i-0abc");
    assertThat(TypeCatalog.arn("AWS::S3::Bucket", "logs", json("{}"), region, null)).isEqualTo("arn:aws:s3:::logs");
    assertThat(TypeCatalog.arn("AWS::EC2::Instance", "i-0abc", json("{}"), region, null))
        .as("no account, no ARN: masks hide it").isNull();
    assertThat(TypeCatalog.arn("AWS::Kinesis::StreamConsumer", "x", json("{}"), region, account)).isNull();
  }

  private static JsonNode json(String s) {
    return TestCtx.JSON.readTree(s);
  }
}
