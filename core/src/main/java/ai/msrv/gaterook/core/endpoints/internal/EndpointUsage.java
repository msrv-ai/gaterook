package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionRemovalParticipant;
import ai.msrv.gaterook.core.connections.ConnectionUsage;
import ai.msrv.gaterook.core.endpoints.EndpointChanged;
import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the connections module asks of endpoints: "Used by", whether a connection may change env, and its part in a
 * connection's deletion (its pairs leave every endpoint, which derive their env again).
 */
@Component
@Order(10)
class EndpointUsage implements ConnectionUsage, ConnectionRemovalParticipant {

  private final EndpointConnectionRepository wiring;
  private final EndpointToolRepository tools;
  private final EndpointRows rows;
  private final Wiring writer;
  private final ConnectionConfigs connections;
  private final ObjectProvider<EndpointRemovalParticipant> participants;
  private final ApplicationEventPublisher publisher;
  private final IntegrityAlerts alerts;
  private final Clock clock;
  private final TransactionTemplate readTx;

  EndpointUsage(EndpointConnectionRepository wiring, EndpointToolRepository tools, EndpointRows rows, Wiring writer,
      ConnectionConfigs connections, ObjectProvider<EndpointRemovalParticipant> participants,
      ApplicationEventPublisher publisher, IntegrityAlerts alerts, Clock clock,
      PlatformTransactionManager transactions) {
    this.wiring = wiring;
    this.tools = tools;
    this.rows = rows;
    this.writer = writer;
    this.connections = connections;
    this.participants = participants;
    this.publisher = publisher;
    this.alerts = alerts;
    this.clock = clock;
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  @Override
  public List<UsedBy> usedBy(String connectionId) {
    return readTx.execute(s -> {
      List<UsedBy> out = new ArrayList<>();
      for (EndpointToolEntity t : tools.findByConnectionId(connectionId)) {
        out.add(new UsedBy(Wiring.toolName(t.getToolKey()), t.getEndpoint().getSlug(), ToolPairs.label(t.getMode())));
      }
      out.sort(java.util.Comparator.comparing(UsedBy::endpoint).thenComparing(UsedBy::tool));
      return out;
    });
  }

  @Override
  public List<String> endpoints(String connectionId) {
    return readTx.execute(s -> wiring.findEndpointSlugsByConnectionId(connectionId));
  }

  /**
   * A wired connection may move between dev and stage, never across the prod line: that would turn its endpoint into
   * a prod endpoint (or the reverse) that kept the modes, tokens, grants and publication of the other side, while the
   * prod rules are checked only when those are made (no Cursor token on prod, prod never published, the first bridge
   * token confirmed). Even when it is the endpoint's only connection: then it leaves the endpoint first.
   */
  @Override
  public Optional<String> envChangeProblem(String connectionId, Env env) {
    return readTx.execute(s -> {
      for (EndpointConnectionEntity w : wiring.findByConnectionId(connectionId)) {
        EndpointEntity e = w.getEndpoint();
        if ((e.getEnv() == Env.PROD) != (env == Env.PROD)) {
          return Optional.of("It is on endpoint " + e.getSlug() + " (" + e.getEnv().id() + "): remove it from the "
              + "endpoint first. Prod never mixes with dev or stage, and an endpoint doesn't cross between them.");
        }
        for (EndpointConnectionEntity other : wiring.findByEndpointIdOrderByAddedAtAscIdAsc(e.getId())) {
          if (other.getConnectionId().equals(connectionId)) {
            continue;
          }
          Optional<ConnectionConfigs.Stored> c = connections.byId(other.getConnectionId());
          if (c.isPresent() && (c.get().config().env() == Env.PROD) != (env == Env.PROD)) {
            return Optional.of("It is on endpoint " + e.getSlug() + " with " + c.get().name() + " ("
                + c.get().config().env().id() + "): prod never mixes with dev or stage on one endpoint.");
          }
        }
      }
      return Optional.<String>empty();
    });
  }

  @Override
  public Removal onRemoving(String connectionId, String connectionName, String reason) {
    List<String> stopped = new ArrayList<>();
    int rejected = 0;
    for (EndpointConnectionEntity w : wiring.findByConnectionId(connectionId)) {
      EndpointEntity e = w.getEndpoint();
      for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
        rejected += p.onConnectionUnwiring(e.getId(), e.getSlug(), connectionId, connectionName, reason)
            .rejectedApprovals();
      }
      for (EndpointToolEntity t : tools.findByEndpointIdAndConnectionId(e.getId(), connectionId)) {
        if (t.getMode() != Mode.OFF) {
          stopped.add(Wiring.toolName(t.getToolKey()) + " on " + e.getSlug());
        }
      }
      writer.unwire(e, connectionId);
      if (rows.verify(e)) {
        e.setEnv(writer.deriveEnv(e.getId(), e.getEnv()));
        e.touch(clock.instant());
        rows.save(e);
      } else {
        // The pairs go, but a row edited outside the core is never signed again: the endpoint stays off, and its env
        // is derived again when the person reviews it.
        alerts.report("endpoint", e.getId(), e.getSlug(), "endpoint row");
      }
      publisher.publishEvent(new EndpointChanged(e.getId(), e.getSlug()));
    }
    return new Removal(List.copyOf(stopped), rejected, List.of(), List.of());
  }
}
