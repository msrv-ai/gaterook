package ai.msrv.gaterook.core.admin.clients;

import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.clients.ClientViews.Card;
import ai.msrv.gaterook.core.clients.ClientViews.Clients;
import ai.msrv.gaterook.core.clients.ClientViews.Link;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What the audit adds to the clients' cards, for the Clients page and Overview's clients node alike: each link's last
 * call and the protocol revision its client spoke. A call after Connect proves the client restarted: "needs restart"
 * becomes "connected" — for a sign-in through the browser, a call of the client's own OAuth sign-in
 * ({@link Link#provenBySignIn}).
 */
@Component
public class ClientCalls {

  private static final Duration REVISIONS_SINCE = Duration.ofDays(7);

  private final AuditQueries audit;

  ClientCalls(AuditQueries audit) {
    this.audit = audit;
  }

  /** The page with every card's links as the audit knows them. */
  public Clients withCalls(Clients page) {
    return new Clients(page.scannedAt(), withCalls(page.clients()));
  }

  public List<Card> withCalls(List<Card> cards) {
    Map<String, List<AuditQueries.Revision>> revisions = new HashMap<>();
    List<Card> out = new ArrayList<>();
    for (Card c : cards) {
      List<Link> links = c.links().stream().map(l -> withCall(l, revisions.computeIfAbsent(l.endpoint(),
          this::revisions))).toList();
      out.add(c.withLinks(links));
    }
    return List.copyOf(out);
  }

  /**
   * The cards with each link's last call only (Overview's clients node: connected, needs restart): no protocol
   * revisions, which would read a week of every endpoint's calls at each refresh of the page.
   */
  public List<Card> withLastCalls(List<Card> cards) {
    return cards.stream().map(c -> c.withLinks(c.links().stream().map(l -> withCall(l, List.of())).toList()))
        .toList();
  }

  /** One link with its last call and revision. */
  public Link withCall(Link link) {
    return withCall(link, revisions(link.endpoint()));
  }

  private Link withCall(Link l, List<AuditQueries.Revision> revisions) {
    String label = l.auditLabel();
    boolean signIn = l.provenBySignIn();
    if (label == null && !signIn) {
      return l;
    }
    Instant at = audit.lastCall(l.endpoint(), label, signIn ? l.client() : null, l.createdAt()).orElse(null);
    String revision = revisions.stream().filter(r -> r.clients().contains(l.client())).map(
        AuditQueries.Revision::protocolVersion).findFirst().orElse(null);
    return l.withCall(at, revision);
  }

  private List<AuditQueries.Revision> revisions(String slug) {
    return audit.revisions(slug, Instant.now().minus(REVISIONS_SINCE));
  }
}
