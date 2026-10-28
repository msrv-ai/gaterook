package ai.msrv.gaterook.core.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.policy.PolicySchemas;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EnvironmentPoliciesAdapterTest {
  @Test
  void environmentViewsKeepTheHttpDeadlineSeparateFromSshLogCalls() {
    var json = JsonMapper.builder().build();
    var http = ConnectorManifest.builder("custom-http", "1.0.0", ConnectorManifest.Kind.PLUGIN)
        .policySchema(json.createObjectNode().put("x-policy-section", "http")).build();
    var ssh = ConnectorManifest.builder("ssh-logs", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
        .policySchema(json.createObjectNode().put("x-policy-section", "ssh")).build();
    var sections = json.createObjectNode();
    sections.putObject("limits").put("toolTimeoutSeconds", 30);
    sections.putObject("http").put("toolTimeoutSeconds", 10);
    sections.putObject("ssh").put("commandTimeoutSeconds", 30);
    var policies = mock(PolicyService.class);
    when(policies.schemas()).thenReturn(PolicySchemas.forManifests(List.of(http, ssh), json));
    when(policies.effective(Env.DEV)).thenReturn(sections);
    when(policies.limits(Env.DEV)).thenReturn(LimitsOf.from(sections, Env.DEV));
    var adapter = new EnvironmentPoliciesAdapter(policies);

    assertThat(adapter.limits(Env.DEV).toolTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(adapter.view(Env.DEV, ssh).limits().toolTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(adapter.view(Env.DEV, http).limits().toolTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(adapter.view(Env.DEV, http).rulePrefix()).isEqualTo("dev.http");
  }
}
