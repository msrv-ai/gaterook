package ai.msrv.gaterook.core.settings;

import ai.msrv.gaterook.core.platform.LegacyRows;
import ai.msrv.gaterook.core.platform.SettingStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The Settings sections whose rows are signed (V87, {@link SettingStore#SIGNED}): {@code audit}, whose retention
 * decides which history the audit keeps. Without a row, a section reads as its defaults, and the retention's default is
 * its shortest period; so that deleting the row counts as an edit like any other, the row exists from V87 on. The
 * start that creates the database writes the section's defaults, signed; the start that applies V87 to an older one
 * signs what that version stored, or writes the defaults it read in its place.
 */
@Component
class SignedSections implements LegacyRows {

  private final SettingStore store;

  SignedSections(SettingStore store) {
    this.store = store;
  }

  @Override
  public String migration() {
    return "87";
  }

  @Override
  public String signLegacyRows() {
    List<String> signed = new ArrayList<>();
    for (String name : sections()) {
      if (store.signLegacy(name) || writeDefaults(name)) {
        signed.add("Settings › " + SettingsService.title(name));
      }
    }
    return signed.isEmpty() ? null : String.join(", ", signed);
  }

  @Override
  public void signNewDatabase() {
    sections().forEach(this::writeDefaults);
  }

  private boolean writeDefaults(String name) {
    if (store.find(name).isPresent()) {
      return false;
    }
    store.put(name, SettingsService.defaults(name), 0L);
    return true;
  }

  private static List<String> sections() {
    return SettingsService.SECTIONS.stream().filter(SettingStore::signed).toList();
  }
}
