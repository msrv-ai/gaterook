package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;

/**
 * The pinned host key verifier (report 06 §6, ARCHITECTURE.md §9): a host's key must be one the person trusted —
 * pinned by Test connection after they compared the fingerprint, or in {@code known_hosts} lines they pasted. Anything
 * else fails closed before any credential leaves: MINA authenticates only after the verifier said yes.
 *
 * <p>The policy of one connect travels in the session's connection context ({@code connect(…, context)}), which the
 * verifier reads during key exchange; a session without it is refused. What the host presented is kept for Test
 * connection, which offers it for "Trust" when nothing was pinned yet, and refuses outright when a pinned key changed.
 */
final class HostKeys {

  static final AttributeRepository.AttributeKey<Check> CHECK = new AttributeRepository.AttributeKey<>();

  /** The one verifier of every client: what it accepts is decided per connect by its {@link Check}. */
  static final ServerKeyVerifier VERIFIER = HostKeys::verify;

  enum Verdict { PENDING, PINNED, KNOWN_HOSTS, UNKNOWN, CHANGED, REVOKED }

  /** The trust of one host for one connect, and what the host presented. */
  static final class Check {
    final String host;
    final int port;
    final String pointer;
    final List<String> pinnedLines;
    final List<PublicKey> pinned;
    final List<KnownHostEntry> known;
    private volatile PublicKey seen;
    private volatile Verdict verdict = Verdict.PENDING;
    private volatile Throwable failure;

    private Check(String host, int port, String pointer, List<String> pinnedLines, List<PublicKey> pinned,
        List<KnownHostEntry> known) {
      this.host = host;
      this.port = port;
      this.pointer = pointer;
      this.pinnedLines = pinnedLines;
      this.pinned = pinned;
      this.known = known;
    }

    PublicKey seen() {
      return seen;
    }

    Verdict verdict() {
      return verdict;
    }

    /** Why the session failed before sign-in (no common algorithms, for one), as MINA reported it. */
    Throwable failure() {
      return failure;
    }

    void failed(Throwable t) {
      if (failure == null) {
        failure = t;
      }
    }

    /** Key types we trust for this host, to propose first ({@code HostKeyAlgorithms} as OpenSSH orders it). */
    Set<String> trustedKeyTypes() {
      Set<String> types = new LinkedHashSet<>();
      pinned.forEach(k -> types.add(KeyUtils.getKeyType(k)));
      for (KnownHostEntry e : known) {
        if (e.getMarker() == null && e.isHostMatch(host, port)) {
          types.add(e.getKeyEntry().getKeyType());
        }
      }
      return types;
    }
  }

  private HostKeys() {}

  /** Parses the pinned keys and {@code known_hosts} lines of a host; a line that isn't one is the person's to fix. */
  static Check check(SshSettings.Target target, SshSettings.HostKeyConfig config) throws ConnectorException {
    List<PublicKey> pinned = new ArrayList<>();
    for (int i = 0; i < config.pinned().size(); i++) {
      try {
        pinned.add(parse(config.pinned().get(i)));
      } catch (IOException | GeneralSecurityException | RuntimeException e) {
        throw ConnectorException.badInput(config.pointer().substring(1).replace('/', '.') + ".pinned[" + i
            + "] isn't an OpenSSH public key (\"ssh-ed25519 AAAA…\").");
      }
    }
    List<KnownHostEntry> known = new ArrayList<>();
    String[] lines = config.knownHosts().split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      try {
        KnownHostEntry e = KnownHostEntry.parseKnownHostEntry(line);
        if (e == null || e.getKeyEntry() == null) {
          throw new IllegalArgumentException("no key");
        }
        known.add(e);
      } catch (RuntimeException e) {
        throw ConnectorException.badInput("Line " + (i + 1) + " of known_hosts isn't a known_hosts line.");
      }
    }
    return new Check(target.host(), target.port(), config.pointer(), config.pinned(), List.copyOf(pinned),
        List.copyOf(known));
  }

  static AttributeRepository context(Check check) {
    return AttributeRepository.ofKeyValuePair(CHECK, check);
  }

  static PublicKey parse(String line) throws IOException, GeneralSecurityException {
    return PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, Map.of(), PublicKeyEntryResolver.FAILING);
  }

  /** {@code ssh-ed25519 AAAA…}: the form pinned in the config. */
  static String line(PublicKey key) {
    return PublicKeyEntry.toString(key);
  }

  static String fingerprint(PublicKey key) {
    return KeyUtils.getFingerPrint(key);
  }

  private static boolean verify(ClientSession session, SocketAddress remote, PublicKey key) {
    AttributeRepository ctx = session.getConnectionContext();
    Check check = ctx == null ? null : ctx.getAttribute(CHECK);
    if (check == null) {
      return false; // a connect that didn't say whom it trusts trusts nobody
    }
    check.seen = key;
    for (KnownHostEntry e : check.known) {
      // MINA keeps the marker without its "@".
      if ("revoked".equalsIgnoreCase(e.getMarker()) && e.isHostMatch(check.host, check.port) && matches(e, key)) {
        check.verdict = Verdict.REVOKED;
        return false;
      }
    }
    for (PublicKey p : check.pinned) {
      if (KeyUtils.compareKeys(p, key)) {
        check.verdict = Verdict.PINNED;
        return true;
      }
    }
    boolean anyForHost = !check.pinned.isEmpty();
    for (KnownHostEntry e : check.known) {
      if (e.getMarker() != null || !e.isHostMatch(check.host, check.port)) {
        continue; // @cert-authority host certificates aren't supported (report 06 §9.11)
      }
      anyForHost = true;
      if (matches(e, key)) {
        check.verdict = Verdict.KNOWN_HOSTS;
        return true;
      }
    }
    check.verdict = anyForHost ? Verdict.CHANGED : Verdict.UNKNOWN;
    return false;
  }

  private static boolean matches(KnownHostEntry e, PublicKey key) {
    try {
      PublicKey k = e.getKeyEntry().resolvePublicKey(null, Map.of(), PublicKeyEntryResolver.FAILING);
      return KeyUtils.compareKeys(k, key);
    } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
      return false;
    }
  }
}
