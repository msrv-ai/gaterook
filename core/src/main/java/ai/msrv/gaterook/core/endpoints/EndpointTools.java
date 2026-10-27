package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.ToolPairs;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Tool modes of an endpoint (kit {@code tool-policy-list}, ARCHITECTURE.md §6 endpoint-detail Tools): Off · Read only ·
 * Approval · Bypass per tool × connection. Bypass skips approval but keeps policy limits. Read only isn't offered to a tool that
 * changes data, and a plugin or MCP server tool gets it only after the person confirmed it for that tool's definition
 * and version. "Set all to…" skips what it can't set and says why. Every change is a Setup change.
 */
@Service
public class EndpointTools {

  /** One row of the tool list. */
  public record ToolRow(String toolKey, String name, ToolDefinition.Annotations annotations, String declaredBy,
      String mode, String storedMode, boolean readOnlyAllowed, String reason, boolean readOnlyConfirmed,
      String definitionHash, JsonNode overrides, String rowId, long version) {}

  /**
   * One group: a connection's tools, or the endpoint-wide core tools ({@code connection == null}); a read-only
   * endpoint offers its core tools Off or Read only.
   */
  public record Group(String group, String connection, String env, List<ToolRow> tools) {}

  /** A tool "Set all to…" left alone. */
  public record Skipped(String toolKey, String reason) {}

  /** What "Set all to…" did. */
  public record BulkResult(List<String> changed, List<Skipped> skipped) {}

  private final EndpointRepository endpoints;
  private final EndpointToolRepository tools;
  private final EndpointRows rows;
  private final EndpointSnapshotService snapshots;
  private final PolicyService policies;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  public EndpointTools(EndpointRepository endpoints, EndpointToolRepository tools, EndpointRows rows,
      EndpointSnapshotService snapshots, PolicyService policies, ObjectProvider<SetupChangeRecorder> setupChanges,
      ApplicationEventPublisher publisher, Clock clock, PlatformTransactionManager transactions) {
    this.endpoints = endpoints;
    this.tools = tools;
    this.rows = rows;
    this.snapshots = snapshots;
    this.policies = policies;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
  }

  /** {@code GET /api/endpoints/{slug}/tools}: one group per connection, then the core tools. */
  public List<Group> list(String slug) {
    EndpointSnapshot s = snapshot(slug);
    Map<String, List<ToolRow>> byConnection = new LinkedHashMap<>();
    for (EndpointSnapshot.Pair p : s.pairs()) {
      Optional<String> why = readOnlyRefusal(s, p);
      byConnection.computeIfAbsent(p.connectionName(), k -> new ArrayList<>()).add(new ToolRow(p.toolKey(),
          p.tool().name(), p.tool().annotations(), p.declaredBy(), label(p.mode()), label(p.storedMode()),
          why.isEmpty(), why.orElse(null), p.readOnlyConfirmed(), ToolPairs.definitionHash(p.tool()), p.overrides(),
          p.rowId(), p.rowVersion()));
    }
    List<Group> out = new ArrayList<>();
    for (EndpointSnapshot.Wired w : s.connections()) {
      out.add(new Group(w.manifest().name(), w.config().name(), w.config().env().id(),
          byConnection.getOrDefault(w.config().name(), List.of())));
    }
    List<ToolRow> core = new ArrayList<>();
    for (EndpointSnapshot.CorePair c : s.coreTools()) {
      ToolDefinition d = CoreTools.withMode(c.tool());
      core.add(new ToolRow(c.tool(), c.tool(), d.annotations(), null, label(c.mode()), label(c.storedMode()), true,
          null, true, ToolPairs.definitionHash(d), null, c.rowId(), c.rowVersion()));
    }
    out.add(new Group("Core", null, null, core));
    return out;
  }

  /**
   * {@code PUT /api/endpoints/{slug}/tools {toolKey, connection, mode, overrides, version}}.
   *
   * @param connection the connection of the pair, or {@code null} for a core tool
   * @param overrides  stricter limits of this pair only, or {@code null} to keep them
   */
  public void setMode(String slug, String toolKey, String connection, Mode mode, JsonNode overrides, long version,
      String reason) {
    snapshot(slug);
    writeTx.executeWithoutResult(st -> {
      EndpointEntity e = requireEndpoint(slug);
      // Decided on the rows as they are now, each verified: the row changed here is signed again.
      EndpointSnapshot s = fresh(e);
      if (connection == null) {
        EndpointSnapshot.CorePair core = s.coreTool(toolKey).orElseThrow(() -> ProblemException.notFound(
            "Tool not found", toolKey + " is not a tool of " + slug + "."));
        if (s.readOnly() && mode != Mode.OFF
            && ToolPairs.clampReadOnly(CoreTools.withMode(toolKey), mode) == Mode.OFF) {
          throw ProblemException.unprocessable("Mode not available", slug + " is read only: " + toolKey
              + " can be Off or Read only there", "/mode");
        }
        EndpointToolEntity row = tools.findById(core.rowId()).orElseThrow();
        checkVersion(row, version);
        apply(e, row, toolKey, null, mode, null, reason);
        return;
      }
      EndpointSnapshot.Pair p = pair(s, toolKey, connection);
      Optional<String> refusal = refusal(s, p, mode);
      if (refusal.isPresent()) {
        throw ProblemException.unprocessable("Mode not available", refusal.get(), "/mode");
      }
      if (overrides != null) {
        checkOverrides(overrides);
      }
      EndpointToolEntity row = p.rowId() == null ? null : tools.findById(p.rowId()).orElse(null);
      if (row == null) {
        row = rows.save(new EndpointToolEntity(e, p.config().id(), p.toolKey(), Mode.OFF, clock.instant()));
      } else {
        checkVersion(row, version);
      }
      apply(e, row, p.tool().name(), p.connectionName(), mode, overrides, reason);
    });
  }

  /**
   * {@code POST /api/endpoints/{slug}/tools/bulk {group, mode, reason}}: {@code mode} {@code DEFAULT} puts every tool
   * of the group back to its initial mode.
   *
   * @param connection the group: a connection's name, or {@code null} for every tool of the endpoint
   */
  public BulkResult bulk(String slug, String connection, String mode, String reason) {
    snapshot(slug);
    List<String> changed = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    writeTx.executeWithoutResult(st -> {
      EndpointEntity e = requireEndpoint(slug);
      EndpointSnapshot s = fresh(e);
      for (EndpointSnapshot.Pair p : s.pairs()) {
        if (connection != null && !connection.equals(p.connectionName())) {
          continue;
        }
        Mode target;
        if ("DEFAULT".equals(mode)) {
          target = ToolPairs.initial(offered(p), s.env(), s.readOnly());
        } else {
          target = Mode.valueOf(mode);
        }
        if (target == Mode.READ_ONLY && p.declaredBy() != null) {
          skipped.add(new Skipped(p.toolKey(), "Declared by " + p.declaredBy() + ", not verified: confirm Read only "
              + "for this tool on its own"));
          continue;
        }
        Optional<String> refusal = refusal(s, p, target);
        if (refusal.isPresent()) {
          skipped.add(new Skipped(p.toolKey(), p.tool().mutating() && target == Mode.READ_ONLY
              ? p.tool().name() + " stays as it is: it changes data" : refusal.get()));
          continue;
        }
        if (target == p.storedMode()) {
          continue;
        }
        EndpointToolEntity row = p.rowId() == null
            ? rows.save(new EndpointToolEntity(e, p.config().id(), p.toolKey(), Mode.OFF, clock.instant()))
            : tools.findById(p.rowId()).orElseThrow();
        apply(e, row, p.tool().name(), p.connectionName(), target, null, reason);
        changed.add(p.toolKey());
      }
    });
    return new BulkResult(List.copyOf(changed), List.copyOf(skipped));
  }

  /**
   * {@code POST /api/endpoints/{slug}/tools/confirm-read-only {toolKey, connection, definitionHash}}: the person
   * checked that a plugin or MCP server tool only reads. Bound to the definition they saw and the connector's
   * version: a changed tool loses it.
   */
  public void confirmReadOnly(String slug, String toolKey, String connection, String definitionHash,
      String reason) {
    snapshot(slug);
    writeTx.executeWithoutResult(st -> {
      EndpointEntity e = requireEndpoint(slug);
      EndpointSnapshot.Pair p = pair(fresh(e), toolKey, connection);
      if (p.declaredBy() == null) {
        throw ProblemException.unprocessable("Nothing to confirm", p.tool().name() + " is built in: its annotations "
            + "are the gateway's own.", "/toolKey");
      }
      if (!p.tool().readOnlyModeAllowed()) {
        throw ProblemException.unprocessable("Mode not available", p.tool().name() + " declares that it changes "
            + "data.", "/toolKey");
      }
      if (!ToolPairs.definitionHash(p.tool()).equals(definitionHash)) {
        throw ProblemException.conflict("The tool changed", p.tool().name() + " changed since you looked at it. "
            + "Review it again.");
      }
      EndpointToolEntity row = p.rowId() == null
          ? rows.save(new EndpointToolEntity(e, p.config().id(), p.toolKey(), Mode.APPROVAL, clock.instant()))
          : tools.findById(p.rowId()).orElseThrow();
      if (p.provenanceVersion() == null) {
        throw ProblemException.unprocessable("Nothing to confirm", "The version of " + p.declaredBy() + " is "
            + "unknown, so a confirmation couldn't be bound to it.", "/toolKey");
      }
      Map<String, Object> confirmation = new LinkedHashMap<>();
      // The version of what declared the tool (plugin, upstream MCP server): a new one takes the confirmation away.
      confirmation.put("version", p.provenanceVersion());
      confirmation.put("definitionHash", definitionHash);
      confirmation.put("at", clock.instant().toString());
      row.setRoConfirmation(confirmation, clock.instant());
      rows.save(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE,
          p.tool().name() + " on " + p.connectionName() + ", endpoint " + slug, "Unconfirmed",
          "Confirmed read only (declared by " + p.declaredBy() + ")", reason, e.getEnv(), slug, p.connectionName(),
          null));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
    });
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private void apply(EndpointEntity e, EndpointToolEntity row, String tool, String connection, Mode mode,
      JsonNode overrides, String reason) {
    Mode before = row.getMode();
    boolean overridesChanged = overrides != null && !overrides.equals(rows.tree(row.getOverrides()));
    if (before == mode && !overridesChanged) {
      return;
    }
    if (before != mode) {
      row.setMode(mode, clock.instant());
    }
    if (overridesChanged) {
      row.setOverrides(overrides.isEmpty() ? null : rows.map(overrides), clock.instant());
    }
    rows.save(row);
    String subject = tool + (connection == null ? "" : " on " + connection) + ", endpoint " + e.getSlug();
    if (before != mode) {
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE, subject, ToolPairs.label(before),
          ToolPairs.label(mode), reason, e.getEnv(), e.getSlug(), connection, null));
    }
    if (overridesChanged) {
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE, subject + " · limits", null,
          overrides.toString(), reason, e.getEnv(), e.getSlug(), connection, null));
    }
    publisher.publishEvent(new EndpointChanged(e.getId(), e.getSlug()));
  }

  private static Optional<String> refusal(EndpointSnapshot s, EndpointSnapshot.Pair p, Mode mode) {
    return ToolPairs.refusal(offered(p), mode, s.readOnly(), p.readOnlyConfirmed(), s.slug());
  }

  private static ToolPairs.Offered offered(EndpointSnapshot.Pair p) {
    return new ToolPairs.Offered(p.toolKey(), p.tool(), p.manifest().kind(), p.declaredBy(), p.provenanceVersion(),
        null, null);
  }

  private static Optional<String> readOnlyRefusal(EndpointSnapshot s, EndpointSnapshot.Pair p) {
    return refusal(s, p, Mode.READ_ONLY);
  }

  private void checkOverrides(JsonNode overrides) {
    policies.checkNarrowing(overrides, "/overrides", "per tool");
  }

  private static void checkVersion(EndpointToolEntity row, long version) {
    if (row.getVersion() != version) {
      throw new OptimisticLockingFailureException("Tool " + row.getToolKey() + " is at version " + row.getVersion());
    }
  }

  private static EndpointSnapshot.Pair pair(EndpointSnapshot s, String toolKey, String connection) {
    return s.pairs().stream().filter(p -> p.toolKey().equals(toolKey) && p.connectionName().equals(connection))
        .findFirst().orElseThrow(() -> ProblemException.notFound("Tool not found", toolKey + " on " + connection
            + " is not a tool of " + s.slug() + "."));
  }

  private EndpointSnapshot snapshot(String slug) {
    EndpointSnapshot s = snapshots.bySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
    return unblocked(s);
  }

  /**
   * Inside the change's transaction: the endpoint as its rows say now, every one verified, so that no row is signed
   * again on the strength of a cached view built before an edit made outside the core.
   */
  private EndpointSnapshot fresh(EndpointEntity e) {
    return unblocked(snapshots.uncached(e.getId()).orElseThrow(() -> EndpointService.notFound(e.getSlug())));
  }

  private static EndpointSnapshot unblocked(EndpointSnapshot s) {
    if (s.blocked()) {
      throw ProblemException.conflict("Changed outside GateRook", s.slug() + " was changed outside GateRook. "
          + "Review it first.");
    }
    return s;
  }

  private EndpointEntity requireEndpoint(String slug) {
    return endpoints.findBySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
  }

  private static String label(Mode mode) {
    return ToolPairs.label(mode);
  }
}
