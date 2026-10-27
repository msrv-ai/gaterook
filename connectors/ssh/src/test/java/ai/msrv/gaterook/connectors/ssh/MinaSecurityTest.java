package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.Test;

/**
 * A23: MINA must not use Bouncy Castle as its JCE provider (a native image refuses unverified JCE providers), yet keep
 * Ed25519 — from our registrar, whose provider never reaches {@code java.security}.
 */
class MinaSecurityTest {

  @Test
  void bouncyCastleIsNotMinasProviderAndEd25519StillWorks() throws Exception {
    MinaSecurity.install();
    MinaSecurity.install(); // idempotent

    assertThat(SecurityUtils.isBouncyCastleRegistered()).isFalse();
    assertThat(SecurityUtils.isAPrioriDisabledProvider(SecurityUtils.BOUNCY_CASTLE)).isTrue();
    assertThat(SecurityUtils.isProviderRegistered(MinaSecurity.NAME)).isTrue();
    assertThat(SecurityUtils.isEDDSACurveSupported()).isTrue();
    assertThat(Security.getProvider("BC")).isNull();
    assertThat(Security.getProvider(MinaSecurity.NAME)).isNull();

    KeyPair pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    String line = HostKeys.line(pair.getPublic());
    PublicKey back = HostKeys.parse(line);

    assertThat(line).startsWith("ssh-ed25519 AAAA");
    assertThat(KeyUtils.compareKeys(back, pair.getPublic())).isTrue();
    Signature sign = Signature.getInstance("Ed25519", MinaSecurity.provider());
    sign.initSign(pair.getPrivate());
    sign.update(new byte[] {1, 2, 3});
    byte[] signature = sign.sign();
    Signature verify = Signature.getInstance("Ed25519", MinaSecurity.provider());
    verify.initVerify(back);
    verify.update(new byte[] {1, 2, 3});
    assertThat(verify.verify(signature)).isTrue();
  }

  @Test
  void theJdkServesTheRest() throws Exception {
    MinaSecurity.install();

    // Key exchange, ciphers and MACs come from the JDK's own providers.
    assertThat(SecurityUtils.getKeyAgreement("X25519").getProvider().getName()).isEqualTo("SunEC");
    assertThat(SecurityUtils.getCipher("AES/CTR/NoPadding").getProvider().getName()).isEqualTo("SunJCE");
    assertThat(SecurityUtils.getMac("HmacSHA256").getProvider().getName()).isEqualTo("SunJCE");
  }
}
