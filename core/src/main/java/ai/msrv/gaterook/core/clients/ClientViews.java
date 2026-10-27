package ai.msrv.gaterook.core.clients;

import java.time.Instant;
import java.util.List;

/**
 * What the Clients screen shows and sends (ARCHITECTURE.md §6 clients, kit {@code client-card}). No record carries a
 * token: a named token goes straight from the core into the client's file, a bridge token straight to the broker.
 */
public final class ClientViews {

  private ClientViews() {}

  /** The Clients page: when this computer was last scanned, and one card per client. */
  public record Clients(Instant scannedAt, List<Card> clients) {}

  /**
   * One client card.
   *
   * @param status   {@code connected}, {@code detected}, {@code not-installed}, {@code needs-restart} or {@code error}
   * @param configPath the user-level config file (absolute), {@code configDisplay} the same with {@code ~}
   * @param evidence what showed the client on this computer ({@code ~/.codex})
   * @param methods  how the client can be connected; the first available one is the default
   */
  public record Card(String client, String name, String status, String statusText, String configPath,
      String configDisplay, boolean detected, List<String> evidence, String version, List<String> warnings,
      List<Method> methods, List<Link> links) {

    /** The card with links that know their last calls; a card waiting for a restart that a call proved is connected. */
    public Card withLinks(List<Link> updated) {
      boolean connected = status.equals("needs-restart") && updated.stream().anyMatch(l -> l.status().equals(
          "connected"));
      return new Card(client, name, connected ? "connected" : status, connected ? "Connected" : statusText,
          configPath, configDisplay, detected, evidence, version, warnings, methods, List.copyOf(updated));
    }
  }

  /**
   * One way to connect a client.
   *
   * @param id     {@code mcpb}, {@code config-bridge}, {@code helper}, {@code token} or {@code oauth}
   * @param reason why it isn't offered for some endpoints ("not on prod endpoints"), or {@code null}
   */
  public record Method(String id, String label, boolean prodAllowed, String reason) {}

  /**
   * One entry GateRook wrote (one endpoint of one client).
   *
   * @param status     {@code connected}, {@code needs-restart}, {@code removed-outside}, {@code needs-repair},
   *                   {@code revoked} or {@code error}
   * @param grantLabel the trusted label of the client's access ({@code codex-laptop}); {@code tokenName} for a named token
   * @param grantPrefix {@code gr_Cx7q…}: lists show the grant's prefix only
   * @param usedAt     when the client last got a token or used its token, {@code null} before that
   */
  public record Link(String id, String client, String endpoint, String env, String method, String entryName,
      String configPath, String configDisplay, String grantPrefix, String grantLabel,
      String tokenName, String status, String statusText, Instant createdAt, Instant usedAt, String mcpbPath,
      Instant lastCallAt, String revision) {

    /**
     * The same link with what the audit knows: its last call and the protocol revision it spoke. A call after Connect
     * proves the client restarted: "needs restart" becomes "connected".
     */
    public Link withCall(Instant at, String rev) {
      boolean proves = at != null && !at.isBefore(createdAt) && status.equals("needs-restart");
      return new Link(id, client, endpoint, env, method, entryName, configPath, configDisplay, grantPrefix, grantLabel,
          tokenName, proves ? "connected" : status, proves ? "Connected" : statusText, createdAt, usedAt, mcpbPath, at,
          rev);
    }

    /** The label calls of this link carry in the audit ({@code tool_call.token_label}). */
    public String auditLabel() {
      return grantLabel != null ? grantLabel : tokenName;
    }

    /**
     * Whether this link's calls are told apart by the client's sign-in rather than by a label: a sign-in through the
     * browser has no access of its own until the client signs in, so its calls are those of an OAuth grant of the
     * client's family on its endpoint ({@code tool_call.client} of a call whose principal is an OAuth grant). The
     * name a caller reports about itself ({@code clientInfo}) under a token proves nothing: any holder of a token of
     * the endpoint could send it.
     */
    public boolean provenBySignIn() {
      return auditLabel() == null && "oauth".equals(method);
    }
  }

  /**
   * "What will change": the files and the exact diffs, with nothing written yet.
   *
   * @param repair   the client already has an entry for this endpoint; Confirm writes it again
   * @param link     an {@code Add to Cursor} link carrying only the endpoint URL, or {@code null}
   */
  public record Preview(String previewId, String client, String endpoint, String env, String method, boolean repair,
      List<FileChange> files, List<String> notes, List<String> warnings, String link, Instant expiresAt) {}

  /**
   * One file a preview would write.
   *
   * @param kind       {@code config}, {@code mcpb} or {@code restore}
   * @param backupPath where the copy goes before it is written ({@code <file>.bak-YYYY-MM-DD}), or {@code null}
   * @param modeChange the permission change the write makes ("0644 → 0600 because it now holds a token"), or
   *                   {@code null}
   */
  public record FileChange(String path, String display, String realPath, boolean exists, String kind, Diff diff,
      String backupPath, String modeChange) {}

  /** The diff of one file ({@code gw-diff}). */
  public record Diff(int added, int removed, List<Hunk> hunks) {}

  public record Hunk(String header, List<Line> lines) {}

  /** {@code type}: {@code context}, {@code added} or {@code removed}. */
  public record Line(String type, Integer oldNo, Integer newNo, String text) {}

  /**
   * What a Connect did.
   *
   * @param mcpbPath the extension Electron opens in Claude Desktop, or {@code null}
   */
  public record Connected(Link link, String mcpbPath, List<String> notes) {}

  /** What a Disconnect did. */
  public record Disconnected(String linkId, boolean entryRemoved, int rejectedApprovals, List<String> notes) {}

  /** The steps of Check (report 03 §5). {@code state}: {@code ok}, {@code warning}, {@code error} or {@code skipped}. */
  public record Check(String linkId, String summary, List<Step> steps) {}

  public record Step(String id, String title, String state, String text, Long ms) {}

  /** One copy of a config file the app kept before writing it. */
  public record Backup(String id, String file, Instant createdAt, long sizeBytes) {}

  /** What a Restore wrote. */
  public record Restored(String client, String path, String backupId) {}

  /** What Remove bridge did: Claude Desktop's accesses revoked, extensions and entries removed. */
  public record BridgeRemoved(List<String> revoked, List<String> removedFiles, int rejectedApprovals,
      List<String> notes) {}

  /** Manual setup (kit "Manual setup"): the endpoint URL and snippets with {@code ‹token›} in place of a token. */
  public record Manual(String endpoint, String url, String transport, List<OtherEndpoint> others,
      List<Snippet> snippets) {}

  public record OtherEndpoint(String slug, String url) {}

  /** {@code language}: {@code json}, {@code toml} or {@code sh}. */
  public record Snippet(String id, String title, String language, String text) {}
}
