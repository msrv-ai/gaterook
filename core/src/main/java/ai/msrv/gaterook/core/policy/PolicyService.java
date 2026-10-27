package ai.msrv.gaterook.core.policy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorTypeChanged;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.policy.internal.LimitsOf;
import ai.msrv.gaterook.core.policy.internal.persistence.PolicyEntity;
import ai.msrv.gaterook.core.policy.internal.persistence.PolicyRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Environment policies (ARCHITECTURE.md §2.2 {@code policy}, kit Policies, BLUEPRINT §9): one row per environment that
 * stores only what the person set; everything else comes from the section schemas ({@code x-env-default}, then
 * {@code default}) and, for {@code stage}, from {@code dev}, which it inherits (keys with {@code x-inherit: false}
 * excepted).
 *
 * <p>Locks: a key with {@code const} ("Run queries in a read-only transaction", "Mutations always require approval"
 * are not settings at all) can't change; {@code x-forbidden-env-values} can't be chosen on that environment; a
 * {@code x-confirm: danger} key on prod ({@code allowExec}) changes only with {@code confirm: "danger"}. Every change
 * is a Setup change and a new signed row; a row whose MAC doesn't verify makes its environment deny every call
 * (fail closed) until the person reviews it.
 */
@Service
public class PolicyService implements SmartInitializingSingleton {

  private static final String TABLE = "policy";

  /**
   * The environment as the person sees it on Policies.
   *
   * @param verified whether its own row verified
   * @param serving  whether calls on it are decided at all: its row and every row it inherits from verified
   */
  public record EnvPolicy(Env env, Env inherits, ObjectNode sections, ObjectNode explicit,
      Map<String, Boolean> inherited, long version, boolean verified, boolean serving) {}

  private record Loaded(Env env, Env inherits, ObjectNode explicit, long version, boolean verified) {}

  private final PolicyRepository repository;
  private final ConnectorRegistry registry;
  private final SchemaValidator validator;
  private final RowMac rowMac;
  private final MasterKeyLock lock;
  private final IntegrityAlerts alerts;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final Map<Env, Loaded> cache = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
  private volatile PolicySchemas schemas;

  public PolicyService(PolicyRepository repository, ConnectorRegistry registry, SchemaValidator validator,
      RowMac rowMac, MasterKeyLock lock, IntegrityAlerts alerts, ObjectProvider<SetupChangeRecorder> setupChanges,
      ApplicationEventPublisher publisher, JsonMapper json, Clock clock, PlatformTransactionManager transactions) {
    this.repository = repository;
    this.registry = registry;
    this.validator = validator;
    this.rowMac = rowMac;
    this.lock = lock;
    this.alerts = alerts;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.json = json;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  // ── Start ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public void afterSingletonsInstantiated() {
    if (!lock.locked()) {
      seed();
    }
  }

  @Async
  @TransactionalEventListener
  public void onMasterKeyChanged(MasterKeyChanged event) {
    clearCache();
    if (!lock.locked()) {
      seed();
    }
  }

  @TransactionalEventListener
  public void onConnectorTypeChanged(ConnectorTypeChanged event) {
    // A plugin may bring a policy section of its own.
    schemas = null;
  }

  /**
   * On an empty table, the three rows of a new gateway: dev, stage inheriting dev, prod; nothing set, so every value is
   * the schemas' default for its environment. Rows missing from a table that isn't empty are not re-created: that
   * environment stays fail closed until the person resets it.
   */
  private void seed() {
    writeTx.executeWithoutResult(s -> {
      if (repository.rows() > 0) {
        return;
      }
      for (Env env : Env.values()) {
        PolicyEntity row = repository.saveAndFlush(new PolicyEntity(env, env == Env.STAGE ? Env.DEV : null,
            new LinkedHashMap<>(), clock.instant()));
        sign(row);
      }
    });
    clearCache();
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  public PolicySchemas schemas() {
    PolicySchemas current = schemas;
    if (current == null) {
      current = PolicySchemas.load(registry, json);
      schemas = current;
    }
    return current;
  }

  /**
   * Checks values that narrow the policy for one endpoint ({@code settings.limits}) or one pair
   * ({@code endpoint_tool.overrides}), which {@link PolicyEngine} applies to every section that has the key: each key
   * must merge "only stricter" ({@code x-stricter}) in a section, and its value must be valid for the key in each such
   * section (type and range: a row limit of -5 or a tool timeout of 0 never gets in).
   *
   * @param pointer where the values sit in the request ({@code /limits}, {@code /overrides})
   * @param scope   how they narrow, for the message ({@code per endpoint}, {@code per tool})
   * @throws ProblemException 422 at {@code <pointer>/<key>}
   */
  public void checkNarrowing(JsonNode values, String pointer, String scope) {
    if (values == null || !values.isObject()) {
      throw ProblemException.unprocessable("Invalid limits", pointer.substring(1) + " must be an object.", pointer);
    }
    PolicySchemas s = schemas();
    for (Map.Entry<String, JsonNode> e : values.properties()) {
      String key = e.getKey();
      String field = pointer + "/" + key.replace("~", "~0").replace("/", "~1");
      boolean known = false;
      boolean narrows = false;
      for (String section : s.names()) {
        JsonNode keySchema = s.key(section, key);
        if (keySchema.isMissingNode()) {
          continue;
        }
        known = true;
        if (s.stricter(section, key) == null) {
          continue;
        }
        narrows = true;
        for (SchemaValidator.Violation v : validator.validate(keySchema, e.getValue())) {
          throw ProblemException.unprocessable("Invalid limit", keySchema.path("title").asString(key) + ": "
              + v.message(), field);
        }
      }
      if (!known) {
        throw ProblemException.unprocessable("Unknown limit", "There is no limit " + key + ".", field);
      }
      if (!narrows) {
        throw ProblemException.unprocessable("Not a limit", key + " can't be narrowed " + scope + ".", field);
      }
    }
  }

  /** {@code GET /api/policies/schema}: every section's JSON Schema with its {@code x-stricter} keys. */
  public ObjectNode schema() {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    ObjectNode sections = out.putObject("sections");
    PolicySchemas s = schemas();
    for (String name : s.names()) {
      sections.set(name, s.schema(name).orElseThrow().deepCopy());
    }
    return out;
  }

  public EnvPolicy get(Env env) {
    Loaded row = load(env);
    ObjectNode effective = effective(env);
    Map<String, Boolean> inherited = new LinkedHashMap<>();
    for (String section : schemas().names()) {
      inherited.put(section, row.inherits() != null && row.explicit().path(section).isEmpty());
    }
    return new EnvPolicy(env, row.inherits(), effective, row.explicit().deepCopy(), Map.copyOf(inherited),
        row.version(), row.verified(), verified(env));
  }

  /**
   * Every section of {@code env} with every key resolved: defaults of the environment, then what its ancestors set,
   * then what it sets itself. Never {@code null}; a missing or unverified row resolves to the defaults, for display
   * only: calls on that environment and on every environment that inherits from it are denied (see
   * {@link #verified}).
   */
  public ObjectNode effective(Env env) {
    return effective(env, null, null);
  }

  public JsonNode section(Env env, String section) {
    return effective(env).path(section);
  }

  /**
   * Whether calls on {@code env} may be decided: its row and the row of every environment it inherits from verified.
   * An edited parent fails its children closed too; serving them on defaults would quietly drop what the person
   * tightened on the parent (a row limit, PII masking, denied functions) while the edit waits for review.
   */
  public boolean verified(Env env) {
    for (Env e : chain(env)) {
      if (!load(e).verified()) {
        if (e != env) {
          alerts.report("policy", env.id(), env.id() + " policy", "inherits the " + e.id() + " policy, which was "
              + "changed outside MCP Gateway");
        }
        return false;
      }
    }
    return true;
  }

  public Limits limits(Env env) {
    return LimitsOf.from(effective(env), env);
  }

  public MaskPolicy maskPolicy(Env env) {
    return MaskPolicy.fromSection(section(env, "masking"), MaskPolicy.defaults(env));
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code PUT /api/policies/{env}}: sets the keys given in {@code sections} ({@code null} unsets one, so it inherits
   * or takes its default again); keys not given stay as they are. A value equal to what the environment has without
   * it (its default, or what it inherits) is not stored, so that saving a whole form pins nothing.
   *
   * <p>The change reaches every environment that inherits from {@code env}: each of them is validated, confirmed and
   * recorded as well, one Setup change per environment and key ("stage policy · sql.denyDdl", inherited from dev).
   *
   * @param version the version the person edited
   * @param confirm {@code "danger"} for a change that the kit confirms in a danger dialog
   * @throws ProblemException 422 for a lock, an unknown key or an invalid value; 409 for a missing confirmation or an
   *     environment changed outside the core
   */
  public EnvPolicy update(Env env, JsonNode sections, long version, String reason, String confirm) {
    Objects.requireNonNull(sections, "sections");
    if (!sections.isObject()) {
      throw ProblemException.unprocessable("Invalid policy", "sections must be an object", "/sections");
    }
    writeTx.executeWithoutResult(s -> {
      PolicyEntity row = repository.findById(env.id()).orElseThrow(() -> ProblemException.conflict(
          "Policy missing", "The " + env.id() + " policy is missing. Reset it to its defaults."));
      requireVerified(row);
      if (row.getVersion() != version) {
        throw new OptimisticLockingFailureException("Policy " + env.id() + " is at version " + row.getVersion());
      }
      List<Env> affected = affected(env);
      Map<Env, ObjectNode> before = new EnumMap<>(Env.class);
      affected.forEach(e -> before.put(e, effective(e)));
      ObjectNode explicit = (ObjectNode) rowMac.tree(row.getSections());
      // What env would have with nothing set on it: a value equal to this is the same as not setting it.
      ObjectNode baseline = effective(env, env, JsonNodeFactory.instance.objectNode());
      PolicySchemas s2 = schemas();
      for (Map.Entry<String, JsonNode> section : sections.properties()) {
        String name = section.getKey();
        if (!s2.has(name)) {
          throw ProblemException.unprocessable("Unknown policy section", "There is no section " + name + ".",
              "/sections/" + name);
        }
        if (!section.getValue().isObject()) {
          throw ProblemException.unprocessable("Invalid policy", "A section is an object of settings",
              "/sections/" + name);
        }
        ObjectNode target = explicit.has(name) && explicit.get(name).isObject() ? (ObjectNode) explicit.get(name)
            : explicit.putObject(name);
        for (Map.Entry<String, JsonNode> key : section.getValue().properties()) {
          applyKey(s2, env, name, key.getKey(), key.getValue(), target);
          if (target.has(key.getKey()) && same(target.get(key.getKey()), baseline.path(name).path(key.getKey()))) {
            target.remove(key.getKey());
          }
        }
        if (target.isEmpty()) {
          explicit.remove(name);
        }
      }
      Map<Env, List<String[]>> changes = new EnumMap<>(Env.class);
      for (Env e : affected) {
        ObjectNode after = effective(e, env, explicit);
        checkSections(s2, e, after);
        List<String[]> own = changes(s2, before.get(e), after);
        if (e != env) {
          checkInherited(s2, e, env, after, own);
        }
        checkConfirmations(s2, e, own, confirm);
        changes.put(e, own);
      }
      if (changes.values().stream().allMatch(List::isEmpty)) {
        // Nothing any environment does changes: nothing to sign, nothing to record.
        return;
      }
      row.update(row.getInherits(), map(explicit), clock.instant());
      row = repository.saveAndFlush(row);
      sign(row);
      for (Env e : affected) {
        JsonNode details = e == env ? null : JsonNodeFactory.instance.objectNode().put("inheritedFrom", env.id());
        for (String[] change : changes.get(e)) {
          setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.POLICY,
              e.id() + " policy · " + change[0], change[1], change[2], reason, e, null, null, details));
        }
      }
      announce(env);
    });
    return get(env);
  }

  /**
   * {@code POST /api/policies/{env}/reset}: forgets what the person set; stage inherits dev again, dev and prod take
   * their defaults. Also how a missing row comes back.
   */
  public EnvPolicy reset(Env env, String reason) {
    writeTx.executeWithoutResult(s -> {
      ObjectNode before = effective(env);
      List<Env> affected = affected(env);
      List<Env> inheritors = affected.subList(1, affected.size());
      Map<Env, ObjectNode> inheritedBefore = new EnumMap<>(Env.class);
      inheritors.forEach(e -> inheritedBefore.put(e, effective(e)));
      PolicyEntity row = repository.findById(env.id()).orElse(null);
      Env inherits = env == Env.STAGE ? Env.DEV : null;
      if (row == null) {
        row = repository.saveAndFlush(new PolicyEntity(env, inherits, new LinkedHashMap<>(), clock.instant()));
      } else {
        row.update(inherits, new LinkedHashMap<>(), clock.instant());
        row = repository.saveAndFlush(row);
      }
      sign(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.POLICY,
          env.id() + " policy reset", null, env == Env.STAGE ? "Inherits dev" : "Defaults", reason, env, null, null,
          JsonNodeFactory.instance.objectNode().set("before", before)));
      // What the environments that inherit from env lose or gain with it, each a Setup change of its own.
      PolicySchemas schemas = schemas();
      for (Env e : inheritors) {
        ObjectNode after = effective(e, env, JsonNodeFactory.instance.objectNode());
        for (String[] change : changes(schemas, inheritedBefore.get(e), after)) {
          setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.POLICY,
              e.id() + " policy · " + change[0], change[1], change[2], reason, e, null, null,
              JsonNodeFactory.instance.objectNode().put("inheritedFrom", env.id())));
        }
      }
      resolveAlerts(env);
      announce(env);
    });
    return get(env);
  }

  /**
   * The person reviewed a policy changed outside the core and keeps it as stored. Only what {@link #update} could have
   * stored is kept: the usual parent, known keys, valid values, no lock broken on this environment or on any that
   * inherits from it; anything else is a 422 at its pointer, and the person resets or edits it instead.
   */
  public EnvPolicy confirmIntegrity(Env env, String reason) {
    return confirmIntegrity(env, reason, null);
  }

  /**
   * As {@link #confirmIntegrity(Env, String)}; keeping a value that a change confirms in a danger dialog on prod
   * ({@code x-confirm: danger}, such as {@code ssh.allowExec}) needs that confirmation here too.
   *
   * @throws ProblemException 422 for what an update would refuse, 409 {@code confirm:"danger"} without it
   */
  public EnvPolicy confirmIntegrity(Env env, String reason, String confirm) {
    writeTx.executeWithoutResult(s -> {
      PolicyEntity row = repository.findById(env.id()).orElseThrow(() -> ProblemException.conflict("Policy missing",
          "The " + env.id() + " policy is missing. Reset it to its defaults."));
      if (verify(row)) {
        return;
      }
      checkStored(env, row, confirm);
      sign(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.POLICY,
          env.id() + " policy changed outside MCP Gateway", "Unverified", "Kept as stored", reason, env, null, null,
          null));
      resolveAlerts(env);
      announce(env);
    });
    return get(env);
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Checks a stored row as {@link #update} checks a change, before the person's review signs it again. */
  private void checkStored(Env env, PolicyEntity row, String confirm) {
    Env parent = env == Env.STAGE ? Env.DEV : null;
    if (row.getInherits() != parent) {
      throw ProblemException.unprocessable("Can't keep it as stored", "The " + env.id() + " policy "
          + (parent == null ? "inherits from no other policy" : "inherits from " + parent.id()) + ", but the stored "
          + "row says " + (row.getInherits() == null ? "none" : row.getInherits().id()) + ". Reset it instead.",
          "/inherits");
    }
    JsonNode stored = rowMac.tree(row.getSections());
    if (!stored.isObject()) {
      throw ProblemException.unprocessable("Can't keep it as stored", "The stored sections aren't an object. Reset "
          + "the " + env.id() + " policy instead.", "/sections");
    }
    ObjectNode explicit = (ObjectNode) stored;
    PolicySchemas s = schemas();
    List<String[]> keys = new ArrayList<>();
    for (Map.Entry<String, JsonNode> section : explicit.properties()) {
      String name = section.getKey();
      if (!s.has(name) || !section.getValue().isObject()) {
        throw ProblemException.unprocessable("Can't keep it as stored", "There is no section " + name + ".",
            "/sections/" + name);
      }
      ObjectNode scratch = JsonNodeFactory.instance.objectNode();
      for (Map.Entry<String, JsonNode> key : section.getValue().properties()) {
        // Unknown keys, const locks and x-forbidden-env-values of this environment: 422 at the key.
        applyKey(s, env, name, key.getKey(), key.getValue(), scratch);
        keys.add(new String[] {name + "." + key.getKey(), null, text(key.getValue())});
      }
    }
    for (Env e : affected(env)) {
      // What the environment serves until the review (the unverified row counts as empty) against what it would.
      ObjectNode before = effective(e);
      ObjectNode after = effective(e, env, explicit);
      checkSections(s, e, after);
      if (e != env) {
        checkInherited(s, e, env, after, keys);
      }
      checkConfirmations(s, e, changes(s, before, after), confirm);
    }
  }

  private void applyKey(PolicySchemas s, Env env, String section, String key, JsonNode value, ObjectNode target) {
    String field = "/sections/" + section + "/" + key;
    JsonNode keySchema = s.key(section, key);
    if (keySchema.isMissingNode()) {
      throw ProblemException.unprocessable("Unknown policy setting", section + " has no setting " + key + ".", field);
    }
    if (value == null || value.isNull()) {
      target.remove(key);
      return;
    }
    if (keySchema.has("const") && !keySchema.get("const").equals(value)) {
      throw ProblemException.unprocessable("This setting is locked",
          keySchema.path("title").asString(key) + " is always on: it keeps every environment safe and can't be "
              + "turned off.", field);
    }
    JsonNode forbidden = keySchema.path("x-forbidden-env-values").path(env.id());
    for (JsonNode f : forbidden) {
      if (f.equals(value)) {
        throw ProblemException.unprocessable("Not allowed on " + env.id(),
            keySchema.path("title").asString(key) + " can't be " + value.asString() + " on " + env.id() + ".", field);
      }
    }
    target.set(key, value.deepCopy());
  }

  private void checkSections(PolicySchemas s, Env env, ObjectNode effective) {
    for (String name : s.names()) {
      for (SchemaValidator.Violation v : validator.validate(s.schema(name).orElseThrow(), effective.path(name))) {
        throw ProblemException.unprocessable("Invalid policy", v.message(), "/sections/" + name + v.pointer());
      }
    }
  }

  private static void checkConfirmations(PolicySchemas s, Env env, List<String[]> changes, String confirm) {
    if (env != Env.PROD || "danger".equals(confirm)) {
      return;
    }
    for (String[] change : changes) {
      String[] parts = change[0].split("\\.", 2);
      if ("danger".equals(s.key(parts[0], parts[1]).path("x-confirm").asString(null))) {
        throw ProblemException.needsConfirmation("Confirm this change",
            change[0] + " on prod needs your explicit confirmation.", "danger");
      }
    }
  }

  /** {@code [section.key, from, to]} of every effective value that differs. */
  private static List<String[]> changes(PolicySchemas s, ObjectNode before, ObjectNode after) {
    List<String[]> out = new ArrayList<>();
    for (String section : s.names()) {
      JsonNode b = before.path(section);
      JsonNode a = after.path(section);
      java.util.Set<String> keys = new java.util.TreeSet<>();
      b.propertyNames().forEach(keys::add);
      a.propertyNames().forEach(keys::add);
      for (String key : keys) {
        if (!same(b.path(key), a.path(key))) {
          out.add(new String[] {section + "." + key, text(b.path(key)), text(a.path(key))});
        }
      }
    }
    return out;
  }

  /** The same JSON value however it was typed ({@code 5} and {@code 5.0}); a missing key equals {@code null}. */
  private static boolean same(JsonNode a, JsonNode b) {
    return CanonicalJson.text(a).equals(CanonicalJson.text(b));
  }

  /**
   * An environment that inherits a change may still refuse its value: {@code x-forbidden-env-values} hold for what
   * it ends up with, not only for what is set on it.
   */
  private static void checkInherited(PolicySchemas s, Env env, Env from, ObjectNode after, List<String[]> changes) {
    for (String[] change : changes) {
      String[] parts = change[0].split("\\.", 2);
      JsonNode keySchema = s.key(parts[0], parts[1]);
      JsonNode value = after.path(parts[0]).path(parts[1]);
      for (JsonNode forbidden : keySchema.path("x-forbidden-env-values").path(env.id())) {
        if (same(forbidden, value)) {
          throw ProblemException.unprocessable("Not allowed on " + env.id(), keySchema.path("title")
              .asString(parts[1]) + " can't be " + change[2] + " on " + env.id() + ", which inherits it from "
              + from.id() + ". Set it on " + env.id() + " first.", "/sections/" + parts[0] + "/" + parts[1]);
        }
      }
    }
  }

  /** {@code env} first, then every environment whose policy inherits from it. */
  private List<Env> affected(Env env) {
    List<Env> out = new ArrayList<>();
    out.add(env);
    for (Env e : Env.values()) {
      if (e != env && chain(e).contains(env)) {
        out.add(e);
      }
    }
    return out;
  }

  /**
   * After {@code env} was signed again: its alert and those of the environments that only failed because they
   * inherit from it go away, unless something else of theirs still doesn't verify.
   */
  private void resolveAlerts(Env env) {
    clearCache();
    for (Env e : affected(env)) {
      if (verified(e)) {
        alerts.resolve("policy", e.id());
      }
    }
  }

  private static String text(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    return node.isString() ? node.asString() : node.toString();
  }

  /**
   * Effective sections of {@code env}; with {@code overrideEnv}/{@code overrideExplicit}, as if that environment's
   * stored values were {@code overrideExplicit} (a pending update).
   */
  private ObjectNode effective(Env env, Env overrideEnv, ObjectNode overrideExplicit) {
    PolicySchemas s = schemas();
    List<Env> chain = chain(env);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (String section : s.names()) {
      ObjectNode values = s.defaults(section, env);
      for (int i = chain.size() - 1; i >= 1; i--) {
        JsonNode explicit = explicitOf(chain.get(i), overrideEnv, overrideExplicit).path(section);
        for (Map.Entry<String, JsonNode> e : explicit.properties()) {
          if (s.inheritable(section, e.getKey())) {
            values.set(e.getKey(), e.getValue().deepCopy());
          }
        }
      }
      for (Map.Entry<String, JsonNode> e : explicitOf(env, overrideEnv, overrideExplicit).path(section).properties()) {
        values.set(e.getKey(), e.getValue().deepCopy());
      }
      out.set(section, values);
    }
    return out;
  }

  private JsonNode explicitOf(Env env, Env overrideEnv, ObjectNode overrideExplicit) {
    if (env == overrideEnv) {
      return overrideExplicit;
    }
    Loaded row = load(env);
    return row.verified() ? row.explicit() : JsonNodeFactory.instance.objectNode();
  }

  /** {@code env} first, then the environments it inherits from (at most all three, cycles cut). */
  private List<Env> chain(Env env) {
    List<Env> out = new ArrayList<>();
    Env current = env;
    while (current != null && !out.contains(current)) {
      out.add(current);
      current = load(current).inherits();
    }
    return out;
  }

  private Loaded load(Env env) {
    Loaded cached = cache.get(env);
    if (cached != null) {
      return cached;
    }
    // Only a read in a transaction of its own is cached. A caller's transaction may hold rows this one hasn't
    // committed (and may roll back), or a database snapshot older than a change whose eviction already ran: SQLite
    // fixes a read transaction's view at its first statement, which can be long before this call.
    boolean joined = org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive();
    long generationAtStart = generation.get();
    Loaded loaded = read(env);
    synchronized (cache) {
      // Nor a row read before a change committed and evicted.
      if (!joined && generation.get() == generationAtStart) {
        cache.put(env, loaded);
      }
    }
    return loaded;
  }

  private Loaded read(Env env) {
    return readTx.execute(s -> repository.findById(env.id())
        .map(row -> {
          boolean ok = verify(row);
          if (!ok) {
            alerts.report("policy", env.id(), env.id() + " policy", "policy row");
          }
          return new Loaded(env, row.getInherits(), (ObjectNode) rowMac.tree(row.getSections()), row.getVersion(),
              ok);
        })
        .orElseGet(() -> {
          if (!lock.locked()) {
            alerts.report("policy", env.id(), env.id() + " policy", "missing");
          }
          return new Loaded(env, env == Env.STAGE ? Env.DEV : null, JsonNodeFactory.instance.objectNode(), 0, false);
        }));
  }

  /** Forgets every row read so far: the next call reads and verifies them again. */
  public void invalidateAll() {
    clearCache();
  }

  private void clearCache() {
    synchronized (cache) {
      generation.incrementAndGet();
      cache.clear();
    }
  }

  private void requireVerified(PolicyEntity row) {
    if (!verify(row)) {
      alerts.report("policy", row.getEnv().id(), row.getEnv().id() + " policy", "policy row");
      throw ProblemException.conflict("Changed outside MCP Gateway",
          "The " + row.getEnv().id() + " policy was changed outside MCP Gateway. Review it before you edit it.");
    }
  }

  /** Announces the change for {@code env} and every environment that inherits from it, once committed. */
  private void announce(Env env) {
    clearCache();
    EnumMap<Env, Boolean> affected = new EnumMap<>(Env.class);
    for (Env e : Env.values()) {
      if (chain(e).contains(env)) {
        affected.put(e, true);
      }
    }
    // Readers that commit first must not see the old cache filled again before this commits.
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            clearCache();
          }
        });
    affected.keySet().forEach(e -> publisher.publishEvent(new PolicyChanged(e)));
  }

  private JsonNode macFields(PolicyEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("inherits", row.getInherits() == null ? null : row.getInherits().id());
    f.set("sections", rowMac.tree(row.getSections()));
    return f;
  }

  private void sign(PolicyEntity row) {
    row.setMac(rowMac.sign(TABLE, row.getEnv().id(), row.getVersion(), macFields(row)));
    repository.saveAndFlush(row);
  }

  private boolean verify(PolicyEntity row) {
    return rowMac.verify(TABLE, row.getEnv().id(), row.getVersion(), macFields(row), row.getMac());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(ObjectNode tree) {
    return json.treeToValue(tree, Map.class);
  }
}
