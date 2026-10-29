package ai.msrv.gaterook.core.secrets.internal.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code ~/.gaterook/master.keyset}, the master keyset as Tink JSON (report 12 §7.3, ARCHITECTURE.md A14).
 * With OS protection the file is an {@code encryptedKeyset} under the 32-byte file key Electron keeps in
 * {@code safeStorage}; without it, a cleartext keyset readable by the owner only (0400) — the trust boundary is the OS
 * account either way (§9).
 *
 * <p>Writes are atomic: a temporary file (0600) is written and synced, made 0400, then renamed over the old one; a
 * crash leaves either the old or the new keyset, never half of one. The file is never deleted: a replaced key is
 * renamed aside ({@link #moveAside}).
 */
public final class KeysetFile {

  public static final String NAME = "master.keyset";
  static final byte[] FILE_AD = "mcp-gateway/master.keyset/v1".getBytes(StandardCharsets.UTF_8);

  private static final Set<PosixFilePermission> READ_ONLY = PosixFilePermissions.fromString("r--------");
  private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");

  /** What loading found. */
  public enum State { LOADED, MISSING, INVALID }

  /**
   * @param encrypted whether the file is an {@code encryptedKeyset} (OS protection)
   * @param problem   for {@link State#INVALID}: what is wrong, for the log and the status screen
   */
  public record Loaded(State state, KeysetHandle handle, boolean encrypted, String problem) {}

  private final Path path;

  public KeysetFile(Path home) {
    this.path = home.resolve(NAME);
  }

  public Path path() {
    return path;
  }

  public boolean exists() {
    return Files.exists(path);
  }

  /**
   * Reads the keyset; {@code fileKey} opens an encrypted file. Never throws for a bad file: an unreadable keyset is
   * {@link State#INVALID} and the caller decides (locked mode).
   */
  public Loaded load(Aead fileKey) {
    String json;
    try {
      json = Files.readString(path, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return new Loaded(State.MISSING, null, false, "No file at " + path);
    } catch (IOException e) {
      return new Loaded(State.INVALID, null, false, "Can't read " + path + ": " + e.getMessage());
    }
    boolean encrypted = json.contains("\"encryptedKeyset\"");
    try {
      if (encrypted) {
        if (fileKey == null) {
          return new Loaded(State.INVALID, null, true, path + " is protected by the OS, but GateRook didn't get "
              + "the key to open it");
        }
        return new Loaded(State.LOADED, TinkJsonProtoKeysetFormat.parseEncryptedKeyset(json, fileKey, FILE_AD,
            Envelope.CFG), true, null);
      }
      return new Loaded(State.LOADED, TinkJsonProtoKeysetFormat.parseKeyset(json, Envelope.CFG,
          InsecureSecretKeyAccess.get()), false, null);
    } catch (GeneralSecurityException | RuntimeException e) {
      return new Loaded(State.INVALID, null, encrypted, encrypted
          ? path + " can't be opened with this computer's OS-protected key"
          : path + " is not a valid key file");
    }
  }

  /** Writes {@code handle} atomically: encrypted under {@code fileKey}, or cleartext when it is {@code null}. */
  public void write(KeysetHandle handle, Aead fileKey) throws IOException, GeneralSecurityException {
    byte[] bytes = encode(handle, fileKey);
    try {
      writeAtomically(path, bytes, true);
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  /** The portable key rewrapped for a destination computer, without writing it to disk. */
  public static byte[] encode(KeysetHandle handle, Aead fileKey) throws GeneralSecurityException {
    String json = fileKey != null
        ? TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(handle, fileKey, FILE_AD, Envelope.CFG)
        : TinkJsonProtoKeysetFormat.serializeKeyset(handle, Envelope.CFG, InsecureSecretKeyAccess.get());
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /** Renames the current file to {@code master.keyset.replaced-<epoch ms>} (kept 0400) and returns the new path. */
  public Path moveAside(Instant now) throws IOException {
    Path aside = path.resolveSibling(NAME + ".replaced-" + now.toEpochMilli());
    Files.move(path, aside, StandardCopyOption.ATOMIC_MOVE);
    syncFolder(path.toAbsolutePath().getParent());
    return aside;
  }

  /** Undoes {@link #moveAside}: {@code aside} is the key file again, replacing anything written since. */
  public void moveBack(Path aside) throws IOException {
    try {
      Files.move(aside, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(aside, path, StandardCopyOption.REPLACE_EXISTING);
    }
    syncFolder(path.toAbsolutePath().getParent());
  }

  /** Removes the key file written by a change that didn't commit, when there was no file before it. */
  public void delete() throws IOException {
    if (Files.deleteIfExists(path)) {
      syncFolder(path.toAbsolutePath().getParent());
    }
  }

  /** {@code 0400}-style mode of the file, or {@code null} without POSIX permissions (Windows ACLs). */
  public String mode() throws IOException {
    if (!posix()) {
      return null;
    }
    return octal(Files.getPosixFilePermissions(path));
  }

  public String owner() throws IOException {
    return Files.getOwner(path).getName();
  }

  /** Problems of the file itself: permissions wider than owner read/write ({@code perms}), a synced folder. */
  public List<String> problems() {
    List<String> problems = new ArrayList<>();
    try {
      if (posix() && Files.exists(path)) {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        if (!OWNER_RW.containsAll(perms)) {
          problems.add("perms");
        }
      }
    } catch (IOException e) {
      problems.add("perms");
    }
    if (SyncedFolders.isSynced(path)) {
      problems.add("synced-folder");
    }
    return problems;
  }

  /** {@code Fix permissions}: the file 0400, its folder 0700. */
  public void fixPermissions() throws IOException {
    if (posix()) {
      Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("rwx------"));
      Files.setPosixFilePermissions(path, READ_ONLY);
    }
  }

  /**
   * Writes {@code bytes} to {@code target} through a synced temporary file in the same folder and a rename.
   *
   * @param readOnly 0400 when true (the key file), 0600 otherwise (backups)
   */
  public static void writeAtomically(Path target, byte[] bytes, boolean readOnly) throws IOException {
    Path folder = target.toAbsolutePath().getParent();
    Path temp = folder.resolve("." + target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
    Files.deleteIfExists(temp);
    if (posix()) {
      Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_RW));
    } else {
      Files.createFile(temp);
    }
    try {
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      if (posix()) {
        Files.setPosixFilePermissions(temp, readOnly ? READ_ONLY : OWNER_RW);
      }
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      syncFolder(folder);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /**
   * fsync of the folder, so the rename itself survives a power loss: the database commits that refer to the new key
   * ({@code synchronous=FULL}) must not come back with the old file. POSIX only; Windows can't open a folder as a
   * file, and NTFS journals the rename.
   */
  static void syncFolder(Path folder) {
    if (!posix()) {
      return;
    }
    try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      // A file system without directory fsync: the rename is as durable as it gets there.
    }
  }

  private static boolean posix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  private static String octal(Set<PosixFilePermission> perms) {
    int mode = 0;
    for (PosixFilePermission p : perms) {
      mode |= switch (p) {
        case OWNER_READ -> 0400;
        case OWNER_WRITE -> 0200;
        case OWNER_EXECUTE -> 0100;
        case GROUP_READ -> 040;
        case GROUP_WRITE -> 020;
        case GROUP_EXECUTE -> 010;
        case OTHERS_READ -> 04;
        case OTHERS_WRITE -> 02;
        case OTHERS_EXECUTE -> 01;
      };
    }
    return String.format("%04o", mode);
  }
}
