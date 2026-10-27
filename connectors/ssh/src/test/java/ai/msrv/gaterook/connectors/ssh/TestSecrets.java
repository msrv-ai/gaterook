package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.certificate.OpenSshCertificateBuilder;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.config.keys.PublicKeyEntry;

/**
 * The secrets of one test connection: values by field, and a Vault SSH CA played by a local CA key. Counts what was
 * resolved, so a test can prove that nothing left before the host key was trusted.
 */
final class TestSecrets implements SecretResolver {

  private final Map<String, String> values = new HashMap<>();
  final AtomicInteger resolved = new AtomicInteger();
  final List<SshCertRequest> signRequests = new CopyOnWriteArrayList<>();
  private KeyPair ca;

  TestSecrets with(String field, String value) {
    values.put(field, value);
    return this;
  }

  /** Signs certificates with {@code ca} as Vault's {@code ssh-client-signer} would. */
  TestSecrets ca(KeyPair ca) {
    this.ca = ca;
    return this;
  }

  @Override
  public Secret secret(String field) throws SecretUnavailableException {
    String value = values.get(field);
    if (value == null) {
      throw new SecretUnavailableException(field + " is not set", null);
    }
    resolved.incrementAndGet();
    return new Secret() {
      private final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      private final char[] chars = value.toCharArray();

      @Override
      public char[] chars() {
        return chars;
      }

      @Override
      public byte[] bytes() {
        return bytes;
      }

      @Override
      public String unsafeString() {
        return value;
      }

      @Override
      public String version() {
        return "1";
      }

      @Override
      public Optional<Instant> notAfter() {
        return Optional.empty();
      }

      @Override
      public void close() {
        Arrays.fill(bytes, (byte) 0);
        Arrays.fill(chars, '\0');
      }
    };
  }

  @Override
  public Login login(String field) throws SecretUnavailableException {
    throw new SecretUnavailableException("no logins here", null);
  }

  @Override
  public boolean isSet(String field) {
    return values.containsKey(field);
  }

  @Override
  public Meta meta(String field) {
    return new Meta("local", "1", null);
  }

  @Override
  public SshCertificate sshCertificate(String field, PublicKey key, SshCertRequest request)
      throws SecretUnavailableException {
    if (ca == null) {
      throw new SecretUnavailableException("no Vault for " + field, null);
    }
    resolved.incrementAndGet();
    signRequests.add(request);
    try {
      Instant now = Instant.now();
      List<String> principals = new ArrayList<>(List.of(request.principal()));
      OpenSshCertificate cert = OpenSshCertificateBuilder.userCertificate()
          .publicKey(key)
          .serial(now.toEpochMilli())
          .id(request.keyId())
          .principals(principals)
          .extensions(request.extensions())
          .validAfter(now.minusSeconds(30))
          .validBefore(now.plus(request.ttl()))
          .sign(ca, "ssh-ed25519");
      return new SshCertificate(PublicKeyEntry.toString(cert), Long.toUnsignedString(cert.getSerial(), 16),
          now.plus(request.ttl()));
    } catch (Exception e) {
      throw new SecretUnavailableException("signing failed: " + e.getMessage(), e);
    }
  }
}
