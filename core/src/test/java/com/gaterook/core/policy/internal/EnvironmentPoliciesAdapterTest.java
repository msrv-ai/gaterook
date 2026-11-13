package com.gaterook.core.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.core.policy.PolicySchemas;
import com.gaterook.core.policy.PolicyService;
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

  /**
   * A stored range the egress guard can't read (a leading zero, saved before saves checked it) allows nothing: it is
   * left out, so building the egress guard of every call doesn't fail on it, and the readable ranges still apply.
   */
  @Test
  void aStoredRangeTheEgressGuardCantReadAllowsNothingAndTheOthersStillApply() {
    var json = JsonMapper.builder().build();
    var http = json.createObjectNode();
    var ranges = http.putArray("allowedPrivateRanges");
    ranges.addObject().put("cidr", "192.168.01.0/24");
    ranges.addObject().put("cidr", "10.20.0.0/16");
    ranges.addObject().put("cidr", "10.30.0.0/99");
    ranges.addObject().put("cidr", "fd00:ec2:1::/48").putArray("connections").add("billing-api");
    var policies = mock(PolicyService.class);
    when(policies.section(Env.DEV, "http")).thenReturn(http);
    var adapter = new EnvironmentPoliciesAdapter(policies);

    assertThat(adapter.allowedPrivateRanges(Env.DEV, "orders-api")).containsExactly("10.20.0.0/16");
    assertThat(adapter.allowedPrivateRanges(Env.DEV, "billing-api")).containsExactly("10.20.0.0/16", "fd00:ec2:1::/48");
    for (String range : adapter.allowedPrivateRanges(Env.DEV, "billing-api")) {
      com.gaterook.connectors.support.egress.Cidr.parse(range);
    }
  }
}
