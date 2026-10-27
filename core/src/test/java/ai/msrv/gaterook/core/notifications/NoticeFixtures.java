package ai.msrv.gaterook.core.notifications;

import ai.msrv.gaterook.connector.api.Env;
import java.time.Instant;

/** Notices for the renderer and rule tests: the kit's {@code sql_execute} on {@code dev-db} and friends. */
public final class NoticeFixtures {

  /** 2026-10-22 14:29:12 UTC, the request; 14:34:12 UTC, its auto-deny (report 13 §3: 1792672452). */
  public static final Instant CREATED = Instant.ofEpochSecond(1_792_672_152L);
  public static final Instant EXPIRES = Instant.ofEpochSecond(1_792_672_452L);

  public static final String SQL = "UPDATE orders SET status = 'cancelled' WHERE id = 48213;";

  private NoticeFixtures() {}

  public static ApprovalNotice dev() {
    return notice(Env.DEV, "sql_execute", "dev-db", action("sql", "sql", SQL));
  }

  public static ApprovalNotice prod() {
    return new ApprovalNotice("ap_9pz2kq7m3x1v", "ssh_run", "tpl:tpl_restart:run", "prod-vm", Env.PROD,
        "incident-prod", "claude-desktop", "claude-desktop-mbp", action("runbook", "sh",
            "docker compose -f /srv/api/compose.yml restart api"), "Restart api on prod-vm",
        "prod.ssh.runbook-approval", "required", CREATED, EXPIRES,
        "7c1d9e3a5b2f4c6d8e0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c7d9e1f3a5b7c9d1e", false);
  }

  public static ApprovalNotice notice(Env env, String tool, String connection, ApprovalNotice.Action action) {
    return new ApprovalNotice("ap_3kq7m2x9vt4p", tool, tool, connection, env, "dev", "codex", "codex-laptop", action,
        "1 row in orders", env.id() + ".sql.write-approval", env == Env.PROD ? "required" : "optional", CREATED,
        EXPIRES, "9f2c1a7e5d3b4a6c8e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d", false);
  }

  public static ApprovalNotice.Action action(String kind, String language, String text) {
    return new ApprovalNotice.Action(kind, language, text, text.length(), false, 0, 0, false);
  }

  public static ApprovalNotice with(ApprovalNotice n, ApprovalNotice.Action action) {
    return new ApprovalNotice(n.id(), n.tool(), n.toolKey(), n.connection(), n.env(), n.endpoint(), n.client(),
        n.clientLabel(), action, n.summary(), n.ruleId(), n.commentPolicy(), n.createdAt(), n.expiresAt(), n.digest(),
        n.plugin());
  }

  public static ApprovalNotice withComment(ApprovalNotice n, String commentPolicy) {
    return new ApprovalNotice(n.id(), n.tool(), n.toolKey(), n.connection(), n.env(), n.endpoint(), n.client(),
        n.clientLabel(), n.action(), n.summary(), n.ruleId(), commentPolicy, n.createdAt(), n.expiresAt(),
        n.digest(), n.plugin());
  }

  public static ApprovalNotice plugin(ApprovalNotice n) {
    return new ApprovalNotice(n.id(), "jira_add_comment", "plugin:jira-service-desk:jira_add_comment", "jira",
        n.env(), n.endpoint(), n.client(), n.clientLabel(), n.action(), n.summary(), n.ruleId(), n.commentPolicy(),
        n.createdAt(), n.expiresAt(), n.digest(), true);
  }
}
