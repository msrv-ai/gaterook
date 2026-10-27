package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What Cloud Control returns as configuration and is a secret more often than not (report 09 §1 #4, §6). */
class RedactorTest {

  @Test
  void lambdaEnvironmentsEcsContainersAndInstanceUserDataAreMasked() {
    JsonNode lambda = TestCtx.JSON.readTree("""
        {"FunctionName":"orders-worker","Environment":{"Variables":{"DB_URL":"postgres://u:p@db/app","MODE":"x"}},
         "KmsKeyArn":"arn:aws:kms:eu-central-1:412398765012:key/1"}""");
    assertThat(Redactor.redact(lambda)).isEqualTo(2);
    assertThat(lambda.at("/Environment/Variables/DB_URL").asString()).isEqualTo("‹masked›");
    assertThat(lambda.at("/KmsKeyArn").asString()).startsWith("arn:");

    JsonNode task = TestCtx.JSON.readTree("""
        {"Family":"api","ContainerDefinitions":[{"Name":"api","Environment":[{"Name":"API_KEY","Value":"k-123"},
         {"Name":"LOG","Value":"info"}],"Secrets":[{"Name":"DB_PASSWORD","ValueFrom":"arn:aws:secretsmanager:x"}],
         "RepositoryCredentials":{"CredentialsParameter":"arn:aws:secretsmanager:eu-central-1:1:secret:reg"}}]}""");
    assertThat(Redactor.redact(task)).isEqualTo(3);
    assertThat(task.at("/ContainerDefinitions/0/Environment/0/Value").asString()).isEqualTo("‹masked›");
    assertThat(task.at("/ContainerDefinitions/0/Environment/0/Name").asString()).isEqualTo("API_KEY");
    assertThat(task.at("/ContainerDefinitions/0/Secrets/0/ValueFrom").asString()).isEqualTo("arn:aws:secretsmanager:x");
    assertThat(task.at("/ContainerDefinitions/0/RepositoryCredentials/CredentialsParameter").asString())
        .isEqualTo("‹masked›");

    JsonNode instance = TestCtx.JSON.readTree("""
        {"InstanceId":"i-1","UserData":"IyEvYmluL2Jhc2g=","LaunchTemplate":{"LaunchTemplateName":"lt"}}""");
    assertThat(Redactor.redact(instance)).isEqualTo(1);
    assertThat(instance.path("UserData").asString()).isEqualTo("‹masked›");
  }

  @Test
  void environmentVariablesInTheirOtherShapesAndOriginHeadersAreMasked() {
    JsonNode codeBuild = TestCtx.JSON.readTree("""
        {"Name":"build","Environment":{"ComputeType":"BUILD_GENERAL1_SMALL","Image":"aws/codebuild/standard:7.0",
         "Type":"LINUX_CONTAINER","EnvironmentVariables":[{"Name":"NPM_TOKEN","Value":"cnry-codebuild",
         "Type":"PLAINTEXT"}]}}""");
    JsonNode amplify = TestCtx.JSON.readTree("""
        {"Name":"web","EnvironmentVariables":[{"Name":"API_HOST","Value":"cnry-amplify"}]}""");
    JsonNode appRunner = TestCtx.JSON.readTree("""
        {"SourceConfiguration":{"ImageRepository":{"ImageConfiguration":{"RuntimeEnvironmentVariables":[
         {"Name":"DB","Value":"cnry-apprunner"}]}}}}""");
    JsonNode sageMaker = TestCtx.JSON.readTree("""
        {"ModelName":"m","PrimaryContainer":{"Image":"123.dkr.ecr/x:1","Environment":{"SAGEMAKER_PROGRAM":"train.py",
         "DB_PASS":"hunter2"}}}""");
    JsonNode beanstalk = TestCtx.JSON.readTree("""
        {"OptionSettings":[{"Namespace":"aws:elasticbeanstalk:application:environment","OptionName":"DB_HOST",
         "Value":"cnry-eb"},{"Namespace":"aws:autoscaling:asg","OptionName":"MinSize","Value":"1"}]}""");
    JsonNode cloudFront = TestCtx.JSON.readTree("""
        {"DistributionConfig":{"Origins":[{"Id":"api","OriginCustomHeaders":[{"HeaderName":"X-Origin-Verify",
         "HeaderValue":"cnry-origin"}]}]}}""");

    assertThat(Redactor.redact(codeBuild)).isEqualTo(1);
    assertThat(codeBuild.at("/Environment/Image").asString()).isEqualTo("aws/codebuild/standard:7.0");
    assertThat(codeBuild.at("/Environment/EnvironmentVariables/0/Name").asString()).isEqualTo("NPM_TOKEN");
    assertThat(Redactor.redact(amplify)).isEqualTo(1);
    assertThat(Redactor.redact(appRunner)).isEqualTo(1);
    assertThat(Redactor.redact(sageMaker)).isEqualTo(2);
    assertThat(sageMaker.at("/PrimaryContainer/Image").asString()).isEqualTo("123.dkr.ecr/x:1");
    assertThat(Redactor.redact(beanstalk)).isEqualTo(1);
    assertThat(beanstalk.at("/OptionSettings/1/Value").asString()).isEqualTo("1");
    assertThat(Redactor.redact(cloudFront)).isEqualTo(1);
    assertThat(cloudFront.at("/DistributionConfig/Origins/0/OriginCustomHeaders/0/HeaderName").asString())
        .isEqualTo("X-Origin-Verify");
    for (JsonNode n : java.util.List.of(codeBuild, amplify, appRunner, sageMaker, beanstalk, cloudFront)) {
      assertThat(n.toString()).doesNotContain("cnry-").doesNotContain("hunter2");
    }
  }

  @Test
  void keysThatNameASecretAreMaskedAndReferencesStay() {
    JsonNode n = TestCtx.JSON.readTree("""
        {"AuthToken":"redis-token","MasterUserPassword":"pw","MasterUserSecret":{"SecretArn":"arn:aws:x","KmsKeyId":"k"},
         "PasswordPolicy":"strict","TokenValidityUnits":{"AccessToken":"hours"},"ClientSecretRotationDays":30,
         "Nested":[{"connectionString":"Server=x;Password=y"}],"PasswordLength":12,"SecretString":"{\\"a\\":1}"}""");
    int masked = Redactor.redact(n);

    assertThat(n.path("AuthToken").asString()).isEqualTo("‹masked›");
    assertThat(n.path("MasterUserPassword").asString()).isEqualTo("‹masked›");
    assertThat(n.at("/MasterUserSecret/SecretArn").asString()).isEqualTo("arn:aws:x");
    assertThat(n.path("PasswordPolicy").asString()).isEqualTo("strict");
    assertThat(n.path("PasswordLength").asInt()).isEqualTo(12);
    assertThat(n.at("/Nested/0/connectionString").asString()).isEqualTo("‹masked›");
    assertThat(n.path("SecretString").asString()).isEqualTo("‹masked›");
    assertThat(n.at("/TokenValidityUnits/AccessToken").asString()).isEqualTo("‹masked›");
    assertThat(masked).isEqualTo(5);
  }
}
