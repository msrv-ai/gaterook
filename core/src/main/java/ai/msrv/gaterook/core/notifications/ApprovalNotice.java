package ai.msrv.gaterook.core.notifications;

import ai.msrv.gaterook.connector.api.Env;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything a channel may show of an approval request (ARCHITECTURE.md §7.4, §9 "Approve the exact action"): a
 * desktop notification, a Slack message, a Telegram message. There is no field for the agent's note — by type, so no
 * renderer can send what an agent wrote. Every text here already went through the core's {@code Masker} with the
 * request environment's policy.
 *
 * @param client       the client family the call came from ({@code codex}, {@code claude-desktop}), or {@code null}
 * @param clientLabel  the trusted label of who asked: the token's name or the grant's label ({@code codex-laptop})
 * @param action       the exact action as the channel may show it
 * @param summary      one line about the action (≤ 120 characters), masked
 * @param commentPolicy {@code optional}, {@code recommended} or {@code required}: what Approve needs
 * @param digest       the request's digest; a button carries its first {@link #DIGEST_PREFIX} characters
 * @param plugin       the tool belongs to a plugin: the gateway can't vouch for its signature here, so no channel offers
 *                     Approve for it
 */
public record ApprovalNotice(String id, String tool, String toolKey, String connection, Env env, String endpoint,
    String client, String clientLabel, Action action, String summary, String ruleId, String commentPolicy,
    Instant createdAt, Instant expiresAt, String digest, boolean plugin) {

  /** How much of the digest a button carries: what the person saw, checked again on the click. */
  public static final int DIGEST_PREFIX = 12;

  /**
   * The exact action. {@code text} has its invisible characters written as visible escapes ({@code ‹U+202E›}): a
   * channel can't highlight them the way the window does.
   *
   * @param kind        {@code sql}, {@code command}, {@code http}, {@code diff}, {@code runbook}, {@code json},
   *                    {@code arguments} (the agent's own arguments for a plugin or MCP server tool)
   * @param language    the code block's language ({@code sql}, {@code sh}, {@code json}…), or {@code null}
   * @param length      the length of the action as it runs, in UTF-16 units
   * @param secretLike  the masker found something that looks like a secret: the action is never sent, the length is
   * @param hiddenChars invisible characters (bidi controls, zero-width, tabs, trailing spaces) the escapes show
   * @param confusables letters outside literals that can pass for others
   * @param partial     part of it stays in the app: the agent's {@code note} argument of a plugin or MCP server tool,
   *                    which no channel ever carries
   */
  public record Action(String kind, String language, String text, int length, boolean secretLike, int hiddenChars,
      int confusables, boolean partial) {

    public Action {
      Objects.requireNonNull(text, "text");
    }

    /** Nothing hidden and nothing that passes for something else. */
    public boolean clean() {
      return hiddenChars == 0 && confusables == 0;
    }
  }

  public ApprovalNotice {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(action, "action");
  }

  public boolean prod() {
    return env == Env.PROD;
  }

  public boolean commentRequired() {
    return "required".equals(commentPolicy);
  }

  /** What a button carries of the digest. */
  public String digestPrefix() {
    return digest == null ? "" : digest.substring(0, Math.min(DIGEST_PREFIX, digest.length()));
  }

  /** "Codex", "Claude Desktop": the client family as people call it, or {@code null}. */
  public String clientName() {
    if (client == null || client.isBlank()) {
      return null;
    }
    return switch (client) {
      case "codex" -> "Codex";
      case "claude-desktop" -> "Claude Desktop";
      case "claude-ai" -> "claude.ai";
      case "cursor" -> "Cursor";
      case "antigravity" -> "Antigravity";
      case "inspector" -> "MCP Inspector";
      case "playground" -> "Playground";
      default -> null;
    };
  }

  /** "Codex", else the label, else "an agent": who asked, in one word or two. */
  public String asker() {
    String name = clientName();
    if (name != null) {
      return name;
    }
    return clientLabel == null || clientLabel.isBlank() ? "an agent" : clientLabel;
  }
}
