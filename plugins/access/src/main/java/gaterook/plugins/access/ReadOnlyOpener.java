package gaterook.plugins.access;

import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DatabaseBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import net.ucanaccess.jdbc.IJackcessOpenerInterface;

/**
 * How UCanAccess opens the file for a read ({@code jackcessOpener}): Jackcess read-only, so nothing UCanAccess does to
 * its copy can reach the file. A linked table's database opens read-only too, and only from the database's own folder.
 * UCanAccess instantiates this class by name, so it is public with a public constructor.
 */
public final class ReadOnlyOpener implements IJackcessOpenerInterface {

  public ReadOnlyOpener() {}

  @Override
  public Database open(File file, String password) throws IOException {
    return open(file, password, null);
  }

  @Override
  public Database open(File file, String password, Charset charset) throws IOException {
    DatabaseBuilder b = new DatabaseBuilder().withFile(file).withReadOnly(true).withAutoSync(false);
    if (charset != null) {
      b = b.withCharset(charset);
    }
    Database db = b.open();
    File folder = file.getCanonicalFile().getParentFile();
    db.setLinkResolver((linker, name) -> {
      File target = new File(name).isAbsolute() ? new File(name) : new File(folder, name);
      File parent = target.getCanonicalFile().getParentFile();
      if (folder == null || !folder.equals(parent)) {
        throw new IOException("GateRook opens a linked table only from a database in the same folder: " + name);
      }
      return new DatabaseBuilder().withFile(target).withReadOnly(true).withAutoSync(false).open();
    });
    return db;
  }
}
