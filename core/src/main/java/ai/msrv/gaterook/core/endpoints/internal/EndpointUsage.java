package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionRemovalParticipant;
import ai.msrv.gaterook.core.connections.ConnectionRenameParticipant;
import ai.msrv.gaterook.core.connections.ConnectionUsage;
import ai.msrv.gaterook.core.endpoints.EndpointChanged;
import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the connections module asks of endpoints: "Used by", whether a connection may change env, and its part in a
 * connection's deletion (its pairs leave every endpoint, which derive their env again) and rename (an MCP server's
 * pairs follow its new name).
 */
@Component
@Order(10)
class EndpointUsage implements ConnectionUsage, ConnectionRemovalParticipant, ConnectionRenameParticipant {

  private final EndpointConnectionRepository wiring;
  private final EndpointToolRepository tools;
  private final EndpointSnapshotService snapshots;
  private final EndpointRows rows;
  private final Wiring writer;
  private final ConnectionConfigs connections;
  private final ObjectProvider<EndpointRemovalParticipant> participants;
  private final ApplicationEventPublisher publisher;
  private final IntegrityAlerts alerts;
  private final Clock clock;
  private final TransactionTemplate readTx;

  EndpointUsage(EndpointConnectionRepository wiring, EndpointToolRepository tools, EndpointSnapshotService snapshots,
      EndpointRows rows, Wiring writer,
      ConnectionConfigs connections, ObjectProvider<EndpointRemovalParticipant> participants,
      ApplicationEventPublisher publisher, IntegrityAlerts alerts, Clock clock,
      PlatformTransactionManager transactions) {
    this.wiring = wiring;
    this.tools = tools;
    this.snapshots = snapshots;
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
      Map<String, EndpointSnapshot> byEndpoint = new HashMap<>();
      for (EndpointConnectionEntity w : wiring.findByConnectionId(connectionId)) {
        snapshots.uncached(w.getEndpoint().getId()).ifPresent(snapshot -> byEndpoint.put(snapshot.id(), snapshot));
      }
      for (EndpointToolEntity t : tools.findByConnectionId(connectionId)) {
        if (!unlisted(t, byEndpoint.get(t.getEndpoint().getId()))) {
          out.add(new UsedBy(Wiring.toolName(t.getToolKey()), t.getEndpoint().getSlug(),
              ToolPairs.label(t.getMode())));
        }
      }
      // Newly available tools have no stored grant until the person chooses a mode. Include the snapshot's Off
      // pairs so they can be configured here, while retaining saved defaults for connections still needing setup.
      for (EndpointSnapshot snapshot : byEndpoint.values()) {
        snapshot.pairs().stream().filter(p -> p.config().id().equals(connectionId) && p.rowId() == null)
            .forEach(p -> out.add(new UsedBy(p.tool().name(), snapshot.slug(), ToolPairs.label(p.storedMode()))));
      }
      out.sort(java.util.Comparator.comparing(UsedBy::endpoint).thenComparing(UsedBy::tool));
      return out;
    });
  }

  /**
   * The row of an external server's tool that the server doesn't list now: it is kept for the day the tool comes back
   * with the mode a person chose, and isn't in use meanwhile.
   */
  private static boolean unlisted(EndpointToolEntity t, EndpointSnapshot snapshot) {
    return snapshot != null && t.getToolKey().startsWith("mcp:") && snapshot.pairs().stream()
        .noneMatch(p -> p.toolKey().equals(t.getToolKey()) && p.config().id().equals(t.getConnectionId()));
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

  /**
   * An MCP server's tools are keyed by its connection's name ({@code mcp:<name>:<tool>}): each of its pairs takes the
   * key of the new name with its mode, overrides and confirmation, and is signed again, so the rename neither turns the
   * tools Off nor leaves pairs behind under the old name. A pair changed outside GateRook vetoes the rename: it is never
   * signed again before the person reviews its endpoint.
   */
  @Override
  public void onRenaming(String connectionId, String from, String to, String reason) {
    String before = "mcp:" + from + ":";
    String after = "mcp:" + to + ":";
    for (EndpointToolEntity t : tools.findByConnectionId(connectionId)) {
      if (!t.getToolKey().startsWith(before)) {
        continue;
      }
      EndpointEntity e = t.getEndpoint();
      String key = after + t.getToolKey().substring(before.length());
      if (!rows.verify(t) || tools.findByEndpointIdAndConnectionIdAndToolKey(e.getId(), connectionId, key)
          .isPresent()) {
        alerts.report("endpoint", e.getId(), e.getSlug(), "tool " + t.getToolKey());
        throw ProblemException.conflict("Changed outside GateRook", "Endpoint " + e.getSlug() + " was changed "
            + "outside GateRook. Review it before you rename " + from + ".");
      }
      t.rekey(key, clock.instant());
      rows.save(t);
    }
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
