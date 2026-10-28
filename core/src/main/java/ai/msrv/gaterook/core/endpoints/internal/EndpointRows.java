package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.RowMac;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MACs of the endpoint's rows ({@code endpoint}, {@code endpoint_connection}, {@code endpoint_tool}): what an agent can
 * do through an endpoint is exactly these rows, so an edit of any of them outside the core turns the whole endpoint
 * off until the person reviews it. Each row is signed after the flush that settled its version.
 *
 * <p>A row read from the database is signed again only if it verified in the same transaction (or this transaction
 * wrote it): signing a row nobody checked would accept whatever was edited into it (ARCHITECTURE.md §4, §9 "database edits are
 * detected rather than accepted"). {@link #save} enforces that; callers check {@link #verify} first and refuse or skip with a
 * reason, so the exception only ever means a bug. {@link #resign} is the person's "I reviewed it" and needs no check.
 */
@Component
public class EndpointRows {

  private final RowMac rowMac;
  private final EndpointRepository endpoints;
  private final EndpointConnectionRepository wiring;
  private final EndpointToolRepository tools;
  private final EntityManagerFactory entityManagers;
  private final JsonMapper json;
  private final Map<EntityManager, Set<String>> trustedByContext = new WeakHashMap<>();

  public EndpointRows(RowMac rowMac, EndpointRepository endpoints, EndpointConnectionRepository wiring,
      EndpointToolRepository tools, EntityManagerFactory entityManagers, JsonMapper json) {
    this.rowMac = rowMac;
    this.endpoints = endpoints;
    this.wiring = wiring;
    this.tools = tools;
    this.entityManagers = entityManagers;
    this.json = json;
  }

  // ── endpoint ────────────────────────────────────────────────────────────────────────────────────────────────

  public EndpointEntity save(EndpointEntity row) {
    requireTrusted("endpoint", row.getId(), row);
    EndpointEntity saved = endpoints.saveAndFlush(row);
    saved.setMac(rowMac.sign("endpoint", saved.getId(), saved.getVersion(), fields(saved)));
    saved = endpoints.saveAndFlush(saved);
    trust("endpoint", saved.getId());
    return saved;
  }

  public boolean verify(EndpointEntity row) {
    return trustIf(rowMac.verify("endpoint", row.getId(), row.getVersion(), fields(row), row.getMac()), "endpoint",
        row.getId());
  }

  /** Re-signs a row as it is (the person reviewed it). */
  public void resign(EndpointEntity row) {
    row.setMac(rowMac.sign("endpoint", row.getId(), row.getVersion(), fields(row)));
    endpoints.saveAndFlush(row);
    trust("endpoint", row.getId());
  }

  private JsonNode fields(EndpointEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("slug", row.getSlug());
    f.put("env", row.getEnv().id());
    f.put("access", KebabCaseEnumConverter.idOf(row.getAccess() == null ? EndpointAccess.STANDARD : row.getAccess()));
    f.put("enabled", row.isEnabled());
    f.put("instructions", row.getInstructions());
    f.set("settings", rowMac.tree(row.getSettings()));
    f.put("published", row.isPublished());
    f.put("oauthEnabled", row.isOauthEnabled());
    f.set("riskDismissed", rowMac.tree(row.getRiskDismissed()));
    return f;
  }

  // ── endpoint_connection ─────────────────────────────────────────────────────────────────────────────────────

  public EndpointConnectionEntity save(EndpointConnectionEntity row) {
    requireTrusted("endpoint_connection", row.getId(), row);
    // Never updated, no version: signed before the insert.
    row.setMac(rowMac.sign("endpoint_connection", row.getId(), 0, fields(row)));
    EndpointConnectionEntity saved = wiring.saveAndFlush(row);
    trust("endpoint_connection", saved.getId());
    return saved;
  }

  public boolean verify(EndpointConnectionEntity row) {
    return trustIf(rowMac.verify("endpoint_connection", row.getId(), 0, fields(row), row.getMac()),
        "endpoint_connection", row.getId());
  }

  public void resign(EndpointConnectionEntity row) {
    row.setMac(rowMac.sign("endpoint_connection", row.getId(), 0, fields(row)));
    wiring.saveAndFlush(row);
    trust("endpoint_connection", row.getId());
  }

  private JsonNode fields(EndpointConnectionEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("endpointId", row.getEndpoint().getId());
    f.put("connectionId", row.getConnectionId());
    return f;
  }

  // ── endpoint_tool ───────────────────────────────────────────────────────────────────────────────────────────

  public EndpointToolEntity save(EndpointToolEntity row) {
    requireTrusted("endpoint_tool", row.getId(), row);
    EndpointToolEntity saved = tools.saveAndFlush(row);
    saved.setMac(rowMac.sign("endpoint_tool", saved.getId(), saved.getVersion(), fields(saved)));
    saved = tools.saveAndFlush(saved);
    trust("endpoint_tool", saved.getId());
    return saved;
  }

  public boolean verify(EndpointToolEntity row) {
    return trustIf(rowMac.verify("endpoint_tool", row.getId(), row.getVersion(), fields(row), row.getMac()),
        "endpoint_tool", row.getId());
  }

  public void resign(EndpointToolEntity row) {
    row.setMac(rowMac.sign("endpoint_tool", row.getId(), row.getVersion(), fields(row)));
    tools.saveAndFlush(row);
    trust("endpoint_tool", row.getId());
  }

  private JsonNode fields(EndpointToolEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("endpointId", row.getEndpoint().getId());
    f.put("connectionId", row.getConnectionId());
    f.put("toolKey", row.getToolKey());
    f.put("mode", row.getMode().name());
    f.set("overrides", rowMac.tree(row.getOverrides()));
    f.set("roConfirmation", rowMac.tree(row.getRoConfirmation()));
    // Omit absent grants so existing rows keep their pre-migration MAC contract.
    if (row.getTemporaryBypass() != null) {
      f.set("temporaryBypass", rowMac.tree(row.getTemporaryBypass()));
    }
    return f;
  }

  public JsonNode tree(Object columnValue) {
    return rowMac.tree(columnValue);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> map(JsonNode tree) {
    return tree == null || tree.isNull() || tree.isMissingNode() ? null : json.treeToValue(tree, Map.class);
  }

  // ── Which rows this transaction may sign ────────────────────────────────────────────────────────────────────

  /**
   * A row that came from the database (the persistence context manages it) may be signed only once it verified in
   * this transaction or this transaction signed it; a new row (not managed yet) always may.
   */
  private void requireTrusted(String table, String id, Object row) {
    EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
    if (em == null) {
      throw new IllegalStateException("Endpoint rows are signed inside a transaction");
    }
    if (em.contains(row) && !trusted(em).contains(table + ":" + id)) {
      throw new IllegalStateException(table + " row " + id + " is signed only after it verified: a row changed "
          + "outside GateRook is never signed again without the person's review");
    }
  }

  private boolean trustIf(boolean verified, String table, String id) {
    if (verified) {
      trust(table, id);
    }
    return verified;
  }

  private void trust(String table, String id) {
    EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers);
    if (em != null) {
      trusted(em).add(table + ":" + id);
    }
  }

  /**
   * The rows that verified or were signed in the persistence context of the current transaction. Keyed by that
   * context (one per transaction; a joined transaction shares it) and dropped with it, so no trust outlives the
   * transaction that earned it, whatever thread or listener runs next.
   */
  private Set<String> trusted(EntityManager em) {
    synchronized (trustedByContext) {
      return trustedByContext.computeIfAbsent(em, k -> new HashSet<>());
    }
  }
}
