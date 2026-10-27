package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.security.auth.login.FailedLoginException;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.OpenSshCertificate;
import org.apache.sshd.common.util.security.SecurityUtils;

/**
 * Private keys of a connection (report 06 §1, §5): OpenSSH (bcrypt-encrypted or not), PEM PKCS#1 and PKCS#8,
 * encrypted or not; Ed25519, ECDSA, RSA ≥ 2048. MINA reads all but encrypted PKCS#8, whose decryptor is Bouncy
 * Castle's PKIX part: that one is decrypted here with the JDK (PBES2) and handed to MINA as plain PKCS#8.
 *
 * <p>The key text and passphrase live only for the parse; MINA's API takes the passphrase as a {@code String}.
 */
final class PrivateKeys {

  private static final String ENCRYPTED_PKCS8 = "-----BEGIN ENCRYPTED PRIVATE KEY-----";

  private PrivateKeys() {}

  static KeyPair parse(char[] keyText, char[] passphrase, String field) throws ConnectorException {
    byte[] pem = null;
    try {
      pem = toBytes(keyText);
      if (contains(keyText, ENCRYPTED_PKCS8)) {
        if (passphrase == null || passphrase.length == 0) {
          throw ConnectorException.badInput("The private key is encrypted. Add its passphrase.");
        }
        byte[] plain = decryptPkcs8(keyText, passphrase);
        Arrays.fill(pem, (byte) 0);
        pem = plain;
      }
      FilePasswordProvider password = passphrase == null || passphrase.length == 0 ? FilePasswordProvider.EMPTY
          : FilePasswordProvider.of(new String(passphrase));
      Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(field),
          new ByteArrayInputStream(pem), password);
      Iterator<KeyPair> it = pairs == null ? null : pairs.iterator();
      if (it == null || !it.hasNext()) {
        throw ConnectorException.badInput("The private key isn't one GateRook can read: use OpenSSH or PEM "
            + "(PKCS#1, PKCS#8) with Ed25519, ECDSA or RSA.");
      }
      KeyPair pair = it.next();
      if ("ssh-rsa".equals(KeyUtils.getKeyType(pair)) && KeyUtils.getKeySize(pair.getPublic()) < 2048) {
        throw ConnectorException.badInput("The RSA key is shorter than 2048 bits. Use a longer key or Ed25519.");
      }
      return pair;
    } catch (FailedLoginException e) {
      throw ConnectorException.badInput("The private key is encrypted. Add its passphrase.");
    } catch (StreamCorruptedException e) {
      // MINA's word for both a wrong passphrase (the check values differ) and a key it can't make sense of.
      throw ConnectorException.badInput(passphrase != null && passphrase.length > 0
          ? "The passphrase doesn't open the private key."
          : "The private key can't be read: " + e.getMessage() + ".");
    } catch (GeneralSecurityException e) {
      throw ConnectorException.badInput(e instanceof BadPaddingException
          ? "The passphrase doesn't open the private key." : "The private key can't be read: " + e.getMessage());
    } catch (IOException | RuntimeException e) {
      throw ConnectorException.badInput("The private key can't be read. Paste it whole, with its BEGIN and END lines.");
    } finally {
      if (pem != null) {
        Arrays.fill(pem, (byte) 0);
      }
    }
  }

  /**
   * The key pair with an OpenSSH user certificate as its public half, as MINA authenticates with one; the certificate
   * must certify this key.
   */
  static KeyPair withCertificate(KeyPair pair, String certificateLine) throws ConnectorException {
    PublicKey cert;
    try {
      cert = HostKeys.parse(certificateLine);
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      throw ConnectorException.badInput("The certificate isn't an OpenSSH certificate (…-cert-v01@openssh.com).");
    }
    if (!(cert instanceof OpenSshCertificate c)) {
      throw ConnectorException.badInput("The certificate isn't an OpenSSH certificate (…-cert-v01@openssh.com).");
    }
    if (!KeyUtils.compareKeys(c.getCertPubKey(), pair.getPublic())) {
      throw ConnectorException.badInput("The certificate is for another key than the private key.");
    }
    return new KeyPair(cert, pair.getPrivate());
  }

  /** PBES2 (PBKDF2 + AES), as OpenSSL and {@code ssh-keygen -m PKCS8} write it, back to plain PKCS#8 PEM. */
  private static byte[] decryptPkcs8(char[] pem, char[] passphrase) throws GeneralSecurityException, IOException {
    String text = new String(pem);
    int begin = text.indexOf(ENCRYPTED_PKCS8);
    int end = text.indexOf("-----END ENCRYPTED PRIVATE KEY-----", begin);
    if (end < 0) {
      throw new IOException("unterminated");
    }
    String body = text.substring(begin + ENCRYPTED_PKCS8.length(), end).replaceAll("\\s", "");
    EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(Base64.getDecoder().decode(body));
    AlgorithmParameters params = info.getAlgParameters();
    String algorithm = params == null ? info.getAlgName() : params.toString();
    PBEKeySpec spec = new PBEKeySpec(passphrase);
    try {
      Cipher cipher = Cipher.getInstance(algorithm);
      cipher.init(Cipher.DECRYPT_MODE, SecretKeyFactory.getInstance(algorithm).generateSecret(spec), params);
      byte[] der;
      try {
        der = info.getKeySpec(cipher).getEncoded();
      } catch (InvalidKeySpecException e) {
        // The JDK hides the bad padding of a wrong passphrase behind "Cannot retrieve the PKCS8EncodedKeySpec".
        throw new BadPaddingException("the passphrase doesn't decrypt the key");
      }
      String plain = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(der)
          + "\n-----END PRIVATE KEY-----\n";
      Arrays.fill(der, (byte) 0);
      return plain.getBytes(StandardCharsets.US_ASCII);
    } finally {
      spec.clearPassword();
    }
  }

  private static byte[] toBytes(char[] chars) {
    byte[] out = new byte[chars.length];
    for (int i = 0; i < chars.length; i++) {
      out[i] = chars[i] < 0x80 ? (byte) chars[i] : (byte) '?'; // PEM and OpenSSH keys are ASCII
    }
    return out;
  }

  private static boolean contains(char[] text, String marker) {
    return new String(text).contains(marker);
  }
}
