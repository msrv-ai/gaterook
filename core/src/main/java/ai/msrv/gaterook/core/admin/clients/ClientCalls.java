package ai.msrv.gaterook.core.admin.clients;

import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.clients.ClientViews.Card;
import ai.msrv.gaterook.core.clients.ClientViews.Clients;
import ai.msrv.gaterook.core.clients.ClientViews.Link;
import ai.msrv.gaterook.core.oauth.OAuthService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Connection evidence for the Clients page and Overview: audit supplies actual last calls and protocol revisions;
 * current local OAuth sign-ins establish connectivity even before a tool call. Browser-installed sign-ins are
 * observed connections with no config ownership. Historical audit rows alone cannot revive an ended sign-in.
 */
@Component
public class ClientCalls {

  private static final Duration REVISIONS_SINCE = Duration.ofDays(7);

  private final AuditQueries audit;
  private final OAuthService oauth;

  ClientCalls(AuditQueries audit, OAuthService oauth) {
    this.audit = audit;
    this.oauth = oauth;
  }

  /** The page with every card's actual calls and current OAuth connection evidence. */
  public Clients withCalls(Clients page) {
    return new Clients(page.scannedAt(), withCalls(page.clients()));
  }

  public List<Card> withCalls(List<Card> cards) {
    Map<String, List<AuditQueries.Revision>> revisions = new HashMap<>();
    List<OAuthService.ClientUse> signIns = oauth.localClientUses();
    List<Card> out = new ArrayList<>();
    for (Card c : cards) {
      List<Link> links = observedLinks(c, signIns).stream().map(l -> withCall(l, revisions.computeIfAbsent(l.endpoint(),
          this::revisions), signIns)).toList();
      out.add(c.withLinks(links));
    }
    return List.copyOf(out);
  }

  /**
   * The cards with each link's last call only (Overview's clients node: connected, needs restart): no protocol
   * revisions, which would read a week of every endpoint's calls at each refresh of the page.
   */
  public List<Card> withLastCalls(List<Card> cards) {
    List<OAuthService.ClientUse> signIns = oauth.localClientUses();
    return cards.stream().map(c -> c.withLinks(observedLinks(c, signIns).stream()
        .map(l -> withCall(l, List.of(), signIns)).toList()))
        .toList();
  }

  /** One link with its last call and revision. */
  public Link withCall(Link link) {
    return withCall(link, revisions(link.endpoint()), link.provenBySignIn() ? oauth.localClientUses() : List.of());
  }

  private Link withCall(Link l, List<AuditQueries.Revision> revisions, List<OAuthService.ClientUse> signIns) {
    String label = l.auditLabel();
    boolean signIn = l.provenBySignIn();
    if (label == null && !signIn) {
      return l;
    }
    Instant at = audit.lastCall(l.endpoint(), label, signIn ? l.client() : null, l.createdAt()).orElse(null);
    String revision = revisions.stream().filter(r -> r.clients().contains(l.client())).map(
        AuditQueries.Revision::protocolVersion).findFirst().orElse(null);
    Link updated = l.withCall(at, revision);
    if (signIn) {
      Instant used = signIns.stream().filter(s -> s.client().equals(l.client()) && s.endpoint().equals(l.endpoint()))
          .map(OAuthService.ClientUse::usedAt).max(Instant::compareTo).orElse(null);
      updated = updated.withSignIn(used);
    }
    return updated;
  }

  /** Browser installation can create a working sign-in without a config entry owned by GateRook. */
  private List<Link> observedLinks(Card card, List<OAuthService.ClientUse> signIns) {
    Map<String, OAuthService.ClientUse> observed = new LinkedHashMap<>();
    for (OAuthService.ClientUse use : signIns) {
      if (use.client().equals(card.client()) && card.links().stream().noneMatch(l -> l.endpoint().equals(
          use.endpoint()))) {
        observed.merge(use.endpoint(), use, (a, b) -> a.usedAt().isAfter(b.usedAt()) ? a : b);
      }
    }
    List<Link> links = new ArrayList<>(card.links());
    for (OAuthService.ClientUse use : observed.values()) {
      links.add(new Link(use.grantId(), card.client(), use.endpoint(), use.env(), "oauth", null, null, null, null,
          null, null, "connected", "Connected", use.createdAt(), use.usedAt(), null, null, null));
    }
    return links;
  }

  private List<AuditQueries.Revision> revisions(String slug) {
    return audit.revisions(slug, Instant.now().minus(REVISIONS_SINCE));
  }
}
