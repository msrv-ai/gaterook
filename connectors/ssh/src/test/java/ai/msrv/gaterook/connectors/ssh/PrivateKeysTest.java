package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import org.apache.sshd.certificate.OpenSshCertificateBuilder;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key formats of the config form: OpenSSH (plain, bcrypt-encrypted), PKCS#8 (plain, PBES2-encrypted). */
class PrivateKeysTest {

  @BeforeAll
  static void mina() {
    MinaSecurity.install();
  }

  @Test
  void readsOpenSshKeysPlainAndEncrypted() throws Exception {
    KeyPair ed = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);

    KeyPair plain = PrivateKeys.parse(openSsh(ed, null), null, "/credentials/privateKey");
    KeyPair encrypted = PrivateKeys.parse(openSsh(ed, "pass phrase"), "pass phrase".toCharArray(), "k");

    assertThat(KeyUtils.compareKeys(plain.getPublic(), ed.getPublic())).isTrue();
    assertThat(KeyUtils.compareKeys(encrypted.getPublic(), ed.getPublic())).isTrue();
    assertThatThrownBy(() -> PrivateKeys.parse(openSsh(ed, "pass phrase"), null, "k"))
        .hasMessage("The private key is encrypted. Add its passphrase.");
    assertThatThrownBy(() -> PrivateKeys.parse(openSsh(ed, "pass phrase"), "wrong".toCharArray(), "k"))
        .hasMessage("The passphrase doesn't open the private key.");
  }

  @Test
  void readsPkcs8PlainAndEncryptedWithTheJdk() throws Exception {
    // RSA: the JDK's EC PKCS#8 leaves out the optional public key that OpenSSL and ssh-keygen always write.
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    KeyPair rsa = g.generateKeyPair();

    KeyPair plain = PrivateKeys.parse(pem("PRIVATE KEY", rsa.getPrivate().getEncoded()), null, "k");
    KeyPair encrypted = PrivateKeys.parse(encryptedPkcs8(rsa.getPrivate().getEncoded(), "p8pass"),
        "p8pass".toCharArray(), "k");

    assertThat(KeyUtils.compareKeys(plain.getPublic(), rsa.getPublic())).isTrue();
    assertThat(KeyUtils.compareKeys(encrypted.getPublic(), rsa.getPublic())).isTrue();
    assertThatThrownBy(() -> PrivateKeys.parse(encryptedPkcs8(rsa.getPrivate().getEncoded(), "p8pass"),
        "nope".toCharArray(), "k")).hasMessage("The passphrase doesn't open the private key.");
    assertThatThrownBy(() -> PrivateKeys.parse(encryptedPkcs8(rsa.getPrivate().getEncoded(), "p8pass"), null, "k"))
        .hasMessage("The private key is encrypted. Add its passphrase.");
  }

  @Test
  void refusesShortRsaAndGarbage() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(1024);
    char[] rsa1024 = pem("PRIVATE KEY", g.generateKeyPair().getPrivate().getEncoded());

    assertThatThrownBy(() -> PrivateKeys.parse(rsa1024, null, "k"))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT))
        .hasMessageContaining("shorter than 2048 bits");
    assertThatThrownBy(() -> PrivateKeys.parse("not a key".toCharArray(), null, "k"))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void localKeyReadsAreBoundedAndRequireARegularFile(@TempDir Path directory) throws Exception {
    Path key = directory.resolve("identity");
    Files.writeString(key, "a".repeat(20000));
    assertThat(PrivateKeys.readFile(key)).hasSize(20000);
    Files.writeString(key, "a".repeat(20001));
    assertThatThrownBy(() -> PrivateKeys.readFile(key)).isInstanceOf(ConnectorException.class)
        .hasMessage("The local private key file is larger than 20,000 bytes.");
    for (Path invalid : List.of(directory, directory.resolve("missing"))) {
      assertThatThrownBy(() -> PrivateKeys.readFile(invalid)).isInstanceOf(ConnectorException.class)
          .hasMessage("The local private key path must point to a regular file.");
    }
  }

  @Test
  void aCertificateMustCertifyTheKey() throws Exception {
    KeyPair ca = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    KeyPair mine = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    KeyPair other = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    OpenSshCertificate cert = OpenSshCertificateBuilder.userCertificate().publicKey(mine.getPublic()).serial(7)
        .id("gw:test").principals(List.of("deploy")).validAfter(Instant.now().minusSeconds(60))
        .validBefore(Instant.now().plusSeconds(600)).sign(ca, "ssh-ed25519");
    String line = PublicKeyEntry.toString(cert);

    KeyPair withCert = PrivateKeys.withCertificate(mine, line);

    assertThat(withCert.getPublic()).isInstanceOf(OpenSshCertificate.class);
    assertThatThrownBy(() -> PrivateKeys.withCertificate(other, line))
        .hasMessage("The certificate is for another key than the private key.");
    assertThatThrownBy(() -> PrivateKeys.withCertificate(mine, HostKeys.line(mine.getPublic())))
        .hasMessageContaining("isn't an OpenSSH certificate");
  }

  private static char[] openSsh(KeyPair pair, String passphrase) throws Exception {
    OpenSSHKeyEncryptionContext ctx = null;
    if (passphrase != null) {
      ctx = new OpenSSHKeyEncryptionContext();
      ctx.setPassword(passphrase);
      ctx.setCipherName("AES");
      ctx.setCipherMode("CTR");
      ctx.setCipherType("256");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test", ctx, out);
    return out.toString(StandardCharsets.US_ASCII).toCharArray();
  }

  /** PBES2 with PBKDF2-HMAC-SHA256 and AES-256-CBC, as {@code openssl pkcs8 -topk8 -v2 aes-256-cbc} writes it. */
  private static char[] encryptedPkcs8(byte[] pkcs8, String passphrase) throws Exception {
    String alg = "PBEWithHmacSHA256AndAES_256";
    byte[] salt = new byte[16];
    byte[] iv = new byte[16];
    new SecureRandom().nextBytes(salt);
    new SecureRandom().nextBytes(iv);
    PBEParameterSpec spec = new PBEParameterSpec(salt, 10_000, new IvParameterSpec(iv));
    Cipher cipher = Cipher.getInstance(alg);
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeyFactory.getInstance(alg)
        .generateSecret(new PBEKeySpec(passphrase.toCharArray())), spec);
    AlgorithmParameters params = AlgorithmParameters.getInstance(alg);
    params.init(spec);
    // The JDK reads PBES2 but can't name it when writing: EncryptedPrivateKeyInfo by hand, OID 1.2.840.113549.1.5.13.
    byte[] pbes2 = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x05, 0x0d};
    byte[] der = tlv(0x30, concat(tlv(0x30, concat(pbes2, params.getEncoded())), tlv(0x04, cipher.doFinal(pkcs8))));
    new EncryptedPrivateKeyInfo(der); // well-formed
    return pem("ENCRYPTED PRIVATE KEY", der);
  }

  private static byte[] tlv(int tag, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int n = value.length;
    if (n < 0x80) {
      out.write(n);
    } else if (n < 0x100) {
      out.write(0x81);
      out.write(n);
    } else {
      out.write(0x82);
      out.write(n >> 8);
      out.write(n & 0xff);
    }
    out.writeBytes(value);
    return out.toByteArray();
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static char[] pem(String type, byte[] der) {
    return ("-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(der) + "\n-----END " + type + "-----\n").toCharArray();
  }
}
