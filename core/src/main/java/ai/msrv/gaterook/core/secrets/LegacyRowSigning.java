package ai.msrv.gaterook.core.secrets;

import ai.msrv.gaterook.core.platform.LegacyRows;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.platform.StartupFacts;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signs, once, the rows an older version wrote before their table had a row MAC ({@link LegacyRows}), so that an
 * upgrade keeps its tokens, sign-ins and secrets. It runs in the start that applies the table's migration, after the
 * singletons exist and before the listeners take requests, and only when that start opened the master key the
 * database was protected with ({@link MasterKeyService#keptAtStart}): a locked start, or one that had to make a new key,
 * signs nothing, and those rows stay refused like rows changed outside GateRook. The signing is recorded as a Setup
 * change, so a second one (the migration made to run again by editing the database) is visible in the history.
 *
 * <p>Rows written before the upgrade are trusted as they are: an edit made in {@code gateway.db} before it, or a
 * database rolled back to an older schema, is signed with them. That stays inside the OS-account boundary of
 * ARCHITECTURE.md §9.
 */
@Component
class LegacyRowSigning implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(LegacyRowSigning.class);

  private final List<LegacyRows> tables;
  private final StartupFacts facts;
  private final MasterKeyService masterKey;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final TransactionTemplate writeTx;

  LegacyRowSigning(List<LegacyRows> tables, StartupFacts facts, MasterKeyService masterKey,
      ObjectProvider<SetupChangeRecorder> setupChanges, PlatformTransactionManager transactions) {
    this.tables = List.copyOf(tables);
    this.facts = facts;
    this.masterKey = masterKey;
    this.setupChanges = setupChanges;
    this.writeTx = new TransactionTemplate(transactions);
  }

  @Override
  public void afterSingletonsInstantiated() {
    List<LegacyRows> due = tables.stream().filter(t -> facts.migratedNow(t.migration())).toList();
    if (due.isEmpty()) {
      return;
    }
    if (!masterKey.keptAtStart()) {
      log.warn("The database was upgraded while its master key wasn't open: rows written by the older version stay "
          + "unsigned and are refused until they are made again");
      return;
    }
    writeTx.executeWithoutResult(s -> {
      List<String> signed = new ArrayList<>();
      for (LegacyRows t : due) {
        String what = t.signLegacyRows();
        if (what != null) {
          signed.add(what);
        }
      }
      if (signed.isEmpty()) {
        return;
      }
      String summary = String.join(", ", signed);
      log.info("Signed rows an older version stored without a signature: {}", summary);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Rows from an older version", "unsigned", "signed: " + summary, "GateRook signs security rows that an "
              + "older version stored without a signature, once, when it upgrades the database.", null, null, null,
          null));
    });
  }
}
