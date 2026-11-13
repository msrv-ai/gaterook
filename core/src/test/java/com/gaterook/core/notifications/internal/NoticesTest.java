package com.gaterook.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Env;
import com.gaterook.core.approvals.ApprovalService;
import com.gaterook.core.approvals.ApprovalView;
import com.gaterook.core.notifications.ApprovalNotice;
import com.gaterook.core.platform.Caller;
import com.gaterook.core.platform.MaskPolicy;
import com.gaterook.core.platform.Masker;
import com.gaterook.core.policy.PolicyService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Who a channel says asked: the credential, never the name a named token's client gives itself. */
class NoticesTest {

  private final Notices notices = notices();

  private static Notices notices() {
    PolicyService policies = mock(PolicyService.class);
    when(policies.maskPolicy(any())).thenReturn(MaskPolicy.SECRETS_ONLY);
    return new Notices(mock(ApprovalService.class), policies, new Masker());
  }

  private static ApprovalView request(Caller.Kind kind, String label, String client) {
    ObjectNode call = JsonNodeFactory.instance.objectNode();
    call.putObject("origin").put("client", client);
    Instant at = Instant.parse("2026-11-10T10:00:00Z");
    ActionPreview action = ActionPreview.of(ActionPreview.Kind.SQL, "sql", "DELETE FROM orders WHERE id = 1",
        "dev-db", "1 row in orders");
    return new ApprovalView("ap_1", "tc_1", "ep_1", "dev", "cn_1", "dev-db", "sql_execute", "sql_execute", Env.DEV,
        kind, "pr_1", label, "pending", "d".repeat(64), action, "a".repeat(64), null, "dev.sql.write-approval",
        "optional", null, null, null, at, at.plusSeconds(300), null, null, null, null, null, call);
  }

  @Test
  void aNamedTokensRequestIsAnnouncedAsTheTokenNotAsTheAppItsClientNames() {
    ApprovalNotice n = notices.of(request(Caller.Kind.LOCAL_TOKEN, "ci-runner", "claude-desktop"));

    assertThat(n.client()).isNull();
    assertThat(n.clientName()).isNull();
    assertThat(n.asker()).isEqualTo("ci-runner");
    assertThat(n.clientLabel()).isEqualTo("ci-runner");
  }

  @Test
  void aGrantsRequestNamesTheAppTheGrantWasMadeFor() {
    ApprovalNotice n = notices.of(request(Caller.Kind.BRIDGE, "claude-desktop", "claude-desktop"));

    assertThat(n.client()).isEqualTo("claude-desktop");
    assertThat(n.asker()).isEqualTo("Claude Desktop");
  }
}
