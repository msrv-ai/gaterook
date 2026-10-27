package ai.msrv.gaterook.core.platform;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * What the desktop app hands the core when it starts it: one JSON line on stdin (ARCHITECTURE.md §7.1),
 * {@code {"v":1,"launchSecret","instanceNonce","fileKey","protection","port","publicPort"}}. Ports live in Electron's
 * {@code desktop.json}, never in the database; the launch secret signs the admin API; the file key opens the master
 * keyset when the OS protects it.
 *
 * <p>In development (JVM build only, {@code gateway.dev=true}, never in a release: {@link BuildFlags#RELEASE}) the core
 * makes its own bootstrap from {@code GW_DEV_INTERNAL_SECRET} and {@code server.port} instead; {@link #dev()} says so.
 */
public final class Bootstrap {

  /** The bootstrap line's format version this core reads. */
  public static final int VERSION = 1;

  /** Length of the OS-protection file key from {@code safeStorage}. */
  public static final int FILE_KEY_BYTES = 32;

  private final LaunchSecret launchSecret;
  private final String instanceNonce;
  private byte[] fileKey;
  private final String protection;
  private final int port;
  private final int publicPort;
  private final boolean dev;

  /**
   * @param fileKey    32-byte key of the encrypted master keyset from Electron {@code safeStorage}, or {@code null}
   * @param protection {@code os}, {@code off} or the Linux {@code safeStorage} backend ({@code basic_text}, …)
   * @param port       local API port; {@code 0} picks a free one (tests)
   * @param publicPort the tunnel listener's port, never the local one
   */
  public Bootstrap(LaunchSecret launchSecret, String instanceNonce, byte[] fileKey, String protection, int port,
      int publicPort, boolean dev) {
    this.launchSecret = Objects.requireNonNull(launchSecret, "launchSecret");
    this.instanceNonce = Objects.requireNonNull(instanceNonce, "instanceNonce");
    this.fileKey = fileKey == null ? null : fileKey.clone();
    this.protection = Objects.requireNonNull(protection, "protection");
    if (port < 0 || port > 65_535 || publicPort <= 0 || publicPort > 65_535) {
      throw new IllegalArgumentException("Ports must be 1-65535 (local 0 = any free port)");
    }
    if (port == publicPort) {
      throw new IllegalArgumentException("The public port must differ from the local port " + port);
    }
    this.port = port;
    this.publicPort = publicPort;
    this.dev = dev;
  }

  public LaunchSecret launchSecret() {
    return launchSecret;
  }

  /** Random id of this launch; responses to authenticated callers carry it as {@code X-Gateway-Instance}. */
  public String instanceNonce() {
    return instanceNonce;
  }

  /**
   * The file key, handed out once: the master key service takes it at start and the bootstrap forgets it, so the key
   * doesn't stay in memory longer than needed.
   */
  public synchronized Optional<byte[]> takeFileKey() {
    if (fileKey == null) {
      return Optional.empty();
    }
    byte[] key = fileKey.clone();
    Arrays.fill(fileKey, (byte) 0);
    fileKey = null;
    return Optional.of(key);
  }

  public String protection() {
    return protection;
  }

  /**
   * Decodes the OS-protection file key. Electron sends the same key to the bootstrap line and to
   * {@code POST /internal/keyset/protect}, and both must read it alike, or a key switched on at runtime would not open
   * the file at the next start (ARCHITECTURE.md §6, §7.1). The documented form is base64url without padding, like
   * {@code launchSecret}; standard Base64 with or without padding ({@code Buffer.toString('base64')}) is accepted as
   * well, since about three keys in four contain a character that differs between the two alphabets.
   *
   * @return the 32-byte key; the caller zeroes it
   * @throws IllegalArgumentException when it isn't 32 bytes of either alphabet; the message never quotes the value
   */
  public static byte[] decodeFileKey(char[] encoded) {
    byte[] ascii = new byte[encoded.length];
    try {
      for (int i = 0; i < encoded.length; i++) {
        char c = encoded[i];
        if (c > 0x7f) {
          throw new IllegalArgumentException("The file key isn't base64url");
        }
        // The two alphabets differ only in these characters, so one decoder reads both.
        ascii[i] = (byte) (c == '-' ? '+' : c == '_' ? '/' : c);
      }
      byte[] key;
      try {
        key = Base64.getDecoder().decode(ascii);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("The file key isn't base64url");
      }
      if (key.length != FILE_KEY_BYTES) {
        Arrays.fill(key, (byte) 0);
        throw new IllegalArgumentException("The file key must have " + FILE_KEY_BYTES + " bytes");
      }
      return key;
    } finally {
      Arrays.fill(ascii, (byte) 0);
    }
  }

  public int port() {
    return port;
  }

  public int publicPort() {
    return publicPort;
  }

  /** Whether this is a development bootstrap (no Electron): dev origins allowed, stdin not watched. */
  public boolean dev() {
    return dev;
  }

  @Override
  public String toString() {
    return "Bootstrap[port=" + port + ", publicPort=" + publicPort + ", protection=" + protection + ", dev=" + dev
        + ", launchSecret=‹redacted›, fileKey=" + (fileKey == null ? "none" : "‹redacted›") + "]";
  }
}
