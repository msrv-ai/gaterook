package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.SetupChangeRepository;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activity › Setup changes (ARCHITECTURE.md §4, §9 "Fail-closed append-only audit"): one row per change, written in
 * the transaction of the change itself ({@link Propagation#MANDATORY}: a change outside a transaction is a bug, and a
 * record that can't be written rolls the change back). Rows form a hash chain ({@link ChainHash}), so a row removed or
 * edited behind the gateway's back breaks it; {@link AuditHead} MACs the head and checks the chain at start.
 *
 * <p>The writer pool has one connection with {@code BEGIN IMMEDIATE}: reading the head and inserting the next row are
 * serialized with every other write.
 */
@Component
class SetupChangeJournal implements SetupChangeRecorder {

  /** Desktop: the only actor is the local user (decision 1). */
  static final String ACTOR = "you";

  private final SetupChangeRepository changes;
  private final JsonMapper json;
  private final Clock clock;

  SetupChangeJournal(SetupChangeRepository changes, JsonMapper json, Clock clock) {
    this.changes = changes;
    this.json = json;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  @SuppressWarnings("unchecked")
  public void record(SetupChange change) {
    // The column keeps milliseconds: hash exactly what a re-read gives back.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    String prev = changes.findTopByOrderByIdDesc().map(SetupChangeEntity::getRowSha256).orElse(ChainHash.GENESIS);
    Map<String, Object> details = change.details() == null ? null : json.convertValue(change.details(), Map.class);
    String row = ChainHash.of(prev, new SetupChangeEntity(change, details, ACTOR, now, prev, prev));
    changes.save(new SetupChangeEntity(change, details, ACTOR, now, prev, row));
  }
}
