package com.gaterook.core.platform;

import java.util.Objects;

/**
 * Who made an MCP call, as established before JSON-RPC is parsed (ARCHITECTURE.md §5 step 2). Connectors never see
 * it.
 *
 * @param principalId what approvals, rate limits and revocation key on: the id of a named token, of a grant, of an
 *                    {@code oauth_grant} or {@code "playground"}; never a rotating token and never the whole endpoint
 * @param label       trusted label for people: token name, grant label ({@code codex-laptop}) or OAuth client host
 * @param clientHint  client family for Activity ({@code codex}, {@code claude-desktop}, {@code claude-ai}, …) when
 *                    the credential implies it; {@code clientInfo} sent by the client is only "reports itself as"
 * @param ip          remote address as seen by the listener, for Activity
 */
public record Caller(Kind kind, String principalId, String label, String clientHint, Ingress ingress, String ip) {

  /** Stored as {@code approval.principal_kind} and {@code tool_call.principal_kind}. */
  public enum Kind { LOCAL_TOKEN, BRIDGE, OAUTH, PLAYGROUND }

  public Caller {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(ingress, "ingress");
    if (principalId == null || principalId.isBlank()) {
      throw new IllegalArgumentException("A caller needs a principal id");
    }
  }

  /**
   * Whether a client family recorded for a call of this kind of caller is only what the client says about itself
   * ({@code clientInfo}): a named token implies no client, so the family the audit records for its calls is a guess
   * from that name, and every screen and message shows it as reported by the client, never as who asked. Who asked is
   * the token's label. A bridge grant, an OAuth sign-in and the Playground imply their family, or record none.
   */
  public static boolean familyReported(Kind kind) {
    return kind == Kind.LOCAL_TOKEN;
  }
}
