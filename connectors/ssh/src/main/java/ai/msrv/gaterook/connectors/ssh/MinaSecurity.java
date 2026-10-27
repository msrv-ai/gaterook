package ai.msrv.gaterook.connectors.ssh;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Signature;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.sshd.common.util.security.AbstractSecurityProviderRegistrar;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.common.util.security.eddsa.bouncycastle.BouncyCastleEdDSASupport;
import org.apache.sshd.common.util.security.eddsa.generic.EdDSASupport;
import org.bouncycastle.jcajce.provider.asymmetric.edec.KeyFactorySpi;
import org.bouncycastle.jcajce.provider.asymmetric.edec.KeyPairGeneratorSpi;
import org.bouncycastle.jcajce.provider.asymmetric.edec.SignatureSpi;

/**
 * Where MINA SSHD gets its cryptography: the same on the JVM and in the native image (ARCHITECTURE.md A23).
 *
 * <p>MINA 2.x registers Bouncy Castle as a JCE provider of its own making and then asks it for nearly everything,
 * {@code javax.crypto} engines included. In a native image the JCE refuses a provider it didn't verify at build time
 * ("Attempted to verify a provider that was not registered at build time"), so every key exchange failed there. The
 * default BC registrar is therefore switched off before MINA registers anything — the one JVM-wide setting of this
 * connector — and what the JDK has comes from the JDK: key exchange (curve25519, ECDH, DH), ciphers, MACs, RSA and
 * ECDSA keys and signatures.
 *
 * <p>What MINA 2.x can't do with the JDK alone is Ed25519: its key model is net.i2p's or BC's (keys, host keys, the CA
 * key inside every OpenSSH certificate). A registrar of ours hands MINA BC's Ed25519 key factory, key pair generator
 * and signature, through a provider that is never added to {@code java.security} and that creates them without
 * reflection: {@code java.security} engines, which the JCE doesn't verify. The post-quantum key exchange hybrids
 * (mlkem768x25519, sntrup761x25519) use BC's lightweight classes directly. Bouncy Castle's PKIX part is not needed.
 *
 * <p>{@link #install()} must run before MINA's first use in the process: MINA registers its providers once, and
 * classes such as {@code KeyUtils} pick their key decoders when they are initialized. The connector's constructor
 * calls it; so does every test fixture that starts a MINA server.
 */
public final class MinaSecurity {

  /** Name of the registrar and of its provider; not a {@code java.security} provider name anyone else uses. */
  static final String NAME = "GateRookEdDSA";

  private static final Provider PROVIDER = new Ed25519Provider();
  private static volatile boolean installed;

  private MinaSecurity() {}

  /** Idempotent; see the class comment for when it must run. */
  public static void install() {
    if (installed) {
      return;
    }
    synchronized (MinaSecurity.class) {
      if (installed) {
        return;
      }
      if (SecurityUtils.isRegistrationCompleted() && SecurityUtils.getRegisteredProvider(NAME) == null) {
        // Someone used MINA before the connector: its choices are made and can't be taken back.
        throw new IllegalStateException("MINA SSHD registered its security providers before MinaSecurity.install()");
      }
      SecurityUtils.setAPrioriDisabledProvider(SecurityUtils.BOUNCY_CASTLE, true);
      SecurityUtils.registerSecurityProvider(new EdDSARegistrar());
      installed = true;
    }
  }

  /** The provider MINA gets Ed25519 from; for tests that check where a key came from. */
  static Provider provider() {
    return PROVIDER;
  }

  /** BC's Ed25519 engines for MINA, nothing else. */
  static final class EdDSARegistrar extends AbstractSecurityProviderRegistrar {

    private static final List<String> NAMES = List.of(SecurityUtils.EDDSA, SecurityUtils.ED25519);

    EdDSARegistrar() {
      super(NAME);
    }

    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public boolean isSupported() {
      return true;
    }

    /** A provider instance, not a name: it stays out of {@code java.security}. */
    @Override
    public boolean isNamedProviderUsed() {
      return false;
    }

    @Override
    public String getProviderName() {
      return NAME;
    }

    @Override
    public Provider getSecurityProvider() {
      return PROVIDER;
    }

    @Override
    public boolean isSecurityEntitySupported(Class<?> entityType, String name) {
      boolean engine = entityType == KeyFactory.class || entityType == KeyPairGenerator.class
          || entityType == Signature.class;
      return engine && NAMES.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    @Override
    public Optional<EdDSASupport<?, ?>> getEdDSASupport() {
      return Optional.of(new BouncyCastleEdDSASupport());
    }
  }

  /**
   * BC's Ed25519 service classes, constructed directly: {@link Provider.Service#newInstance} would look them up by
   * name, which a native image only allows with reflection metadata for each.
   */
  private static final class Ed25519Provider extends Provider {

    private static final long serialVersionUID = 1L;

    Ed25519Provider() {
      super(NAME, "1.0", "Bouncy Castle Ed25519 for MINA SSHD");
      String eddsa = SecurityUtils.EDDSA;
      String ed25519 = SecurityUtils.ED25519;
      add("KeyFactory", eddsa, KeyFactorySpi.EdDSA.class, KeyFactorySpi.EdDSA::new);
      add("KeyFactory", ed25519, KeyFactorySpi.Ed25519.class, KeyFactorySpi.Ed25519::new);
      add("KeyPairGenerator", eddsa, KeyPairGeneratorSpi.EdDSA.class, KeyPairGeneratorSpi.EdDSA::new);
      add("KeyPairGenerator", ed25519, KeyPairGeneratorSpi.Ed25519.class, KeyPairGeneratorSpi.Ed25519::new);
      add("Signature", eddsa, SignatureSpi.EdDSA.class, SignatureSpi.EdDSA::new);
      add("Signature", ed25519, SignatureSpi.Ed25519.class, SignatureSpi.Ed25519::new);
    }

    private void add(String type, String algorithm, Class<?> implementation, Supplier<Object> factory) {
      putService(new Service(this, type, algorithm, implementation.getName(), null, null) {
        @Override
        public Object newInstance(Object constructorParameter) {
          return factory.get();
        }
      });
    }
  }
}
