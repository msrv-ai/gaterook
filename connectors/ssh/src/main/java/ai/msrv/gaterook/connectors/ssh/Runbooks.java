package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.EndpointView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runbooks of each SSH connection, as the core last offered them (ARCHITECTURE.md §3: the {@code ssh-runbook}
 * templates contribute to {@code ssh_run} through {@code EndpointView.contributions}). The core builds an endpoint's
 * tools before any call on it and again whenever a template changes, so the latest offer is what a call may run:
 * {@code classify}, {@code describeAction} and {@code execute} receive no contributions of their own.
 *
 * <p>Two runbooks of one connection with the same name make that name ambiguous: neither runs until one is renamed.
 */
final class Runbooks {

  /**
   * One runbook of a connection and where it comes from.
   *
   * @param ambiguous another runbook of the connection has the same name
   */
  record Entry(String templateId, int version, Runbook runbook, boolean ambiguous) {}

  private final Map<String, Map<String, Entry>> byConnection = new ConcurrentHashMap<>();

  /** What the core offers for {@code connectionId} now, replacing what it offered before. */
  void offer(String connectionId, List<EndpointView.Contribution> contributions) {
    Map<String, Entry> entries = new LinkedHashMap<>();
    List<EndpointView.Contribution> sorted = new ArrayList<>(contributions);
    sorted.sort(Comparator.comparing(EndpointView.Contribution::templateId));
    for (EndpointView.Contribution c : sorted) {
      if (!c.connectionId().equals(connectionId)) {
        continue;
      }
      RunbookCompiler.Outcome o = RunbookCompiler.fromContribution(c.value());
      if (!o.ok()) {
        continue; // a contribution that no longer compiles isn't offered
      }
      Entry e = new Entry(c.templateId(), c.version(), o.runbook(), false);
      Entry before = entries.putIfAbsent(o.runbook().name(), e);
      if (before != null) {
        entries.put(o.runbook().name(), new Entry(before.templateId(), before.version(), before.runbook(), true));
      }
    }
    byConnection.put(connectionId, Map.copyOf(entries));
  }

  Optional<Entry> find(String connectionId, String name) {
    Map<String, Entry> entries = byConnection.get(connectionId);
    return entries == null ? Optional.empty() : Optional.ofNullable(entries.get(name));
  }

  /** By name. */
  List<Entry> of(String connectionId) {
    Map<String, Entry> entries = byConnection.get(connectionId);
    if (entries == null) {
      return List.of();
    }
    List<Entry> out = new ArrayList<>(entries.values());
    out.sort(Comparator.comparing(e -> e.runbook().name()));
    return out;
  }

  boolean known(String connectionId) {
    return byConnection.containsKey(connectionId);
  }

  void forget(String connectionId) {
    byConnection.remove(connectionId);
  }
}
