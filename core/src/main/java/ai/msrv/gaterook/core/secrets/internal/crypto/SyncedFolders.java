package ai.msrv.gaterook.core.secrets.internal.crypto;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Folders a cloud client copies elsewhere (report 12 §6): a key or a key backup there leaves this computer. iCloud
 * Drive ({@code ~/Library/Mobile Documents}), the File Provider folders of Dropbox, OneDrive and Google Drive on macOS
 * ({@code ~/Library/CloudStorage}), OneDrive on Windows ({@code %OneDrive%}) and the classic {@code Dropbox} and
 * {@code Google Drive} folders.
 */
public final class SyncedFolders {

  private SyncedFolders() {}

  public static boolean isSynced(Path path) {
    String p = path.toAbsolutePath().normalize().toString().replace('\\', '/');
    String lower = p.toLowerCase(Locale.ROOT);
    if (lower.contains("/library/mobile documents/") || lower.contains("/library/cloudstorage/")) {
      return true;
    }
    String oneDrive = System.getenv("OneDrive");
    if (oneDrive != null && !oneDrive.isBlank()
        && lower.startsWith(oneDrive.replace('\\', '/').toLowerCase(Locale.ROOT))) {
      return true;
    }
    for (Path element : path.toAbsolutePath().normalize()) {
      String name = element.toString().toLowerCase(Locale.ROOT);
      if (name.equals("dropbox") || name.equals("google drive") || name.equals("icloud drive")
          || name.startsWith("onedrive")) {
        return true;
      }
    }
    return false;
  }
}
