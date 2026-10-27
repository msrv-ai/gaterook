package ai.msrv.gaterook.core.secrets.store;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECPoint;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * A public key in the OpenSSH form Vault's SSH CA signs ({@code ssh-ed25519 AAAA…}, report 12 §7.5, report 06): the
 * connector makes a fresh key pair per call and only the public half reaches the core. Ed25519 is read from the
 * X.509 encoding every provider gives (the JDK's EdEC and the EdDSA of MINA alike); RSA and the NIST curves from their
 * key parameters.
 */
public final class SshPublicKeys {

  /** The X.509 SubjectPublicKeyInfo prefix of an Ed25519 key; the 32 raw key bytes follow. */
  private static final byte[] ED25519_SPKI = HexFormat.of().parseHex("302a300506032b6570032100");

  private SshPublicKeys() {}

  /**
   * {@code <type> <base64>}.
   *
   * @throws IllegalArgumentException for a key type Vault can't sign
   */
  public static String openSsh(PublicKey key) {
    if (key instanceof RSAPublicKey rsa) {
      if (rsa.getModulus().bitLength() < 2048) {
        throw new IllegalArgumentException("RSA keys below 2048 bits aren't signed");
      }
      return line("ssh-rsa", blob(string("ssh-rsa"), mpint(rsa.getPublicExponent()), mpint(rsa.getModulus())));
    }
    if (key instanceof ECPublicKey ec) {
      int bits = ec.getParams().getCurve().getField().getFieldSize();
      String curve = switch (bits) {
        case 256 -> "nistp256";
        case 384 -> "nistp384";
        case 521 -> "nistp521";
        default -> throw new IllegalArgumentException("Only the NIST curves P-256, P-384 and P-521 are signed");
      };
      String type = "ecdsa-sha2-" + curve;
      return line(type, blob(string(type), string(curve), string(point(ec.getW(), (bits + 7) / 8))));
    }
    byte[] encoded = key.getEncoded();
    if (encoded != null && "X.509".equals(key.getFormat()) && encoded.length == ED25519_SPKI.length + 32
        && Arrays.equals(Arrays.copyOf(encoded, ED25519_SPKI.length), ED25519_SPKI)) {
      byte[] raw = Arrays.copyOfRange(encoded, ED25519_SPKI.length, encoded.length);
      return line("ssh-ed25519", blob(string("ssh-ed25519"), string(raw)));
    }
    throw new IllegalArgumentException("Vault signs Ed25519, ECDSA and RSA keys; this is " + key.getAlgorithm());
  }

  private static String line(String type, byte[] blob) {
    return type + " " + Base64.getEncoder().encodeToString(blob);
  }

  private static byte[] blob(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static byte[] string(String s) {
    return string(s.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] string(byte[] b) {
    byte[] out = new byte[4 + b.length];
    out[0] = (byte) (b.length >>> 24);
    out[1] = (byte) (b.length >>> 16);
    out[2] = (byte) (b.length >>> 8);
    out[3] = (byte) b.length;
    System.arraycopy(b, 0, out, 4, b.length);
    return out;
  }

  /** RFC 4251 mpint: two's complement, big-endian, minimal (BigInteger's own encoding). */
  private static byte[] mpint(BigInteger n) {
    return string(n.toByteArray());
  }

  private static byte[] point(ECPoint w, int size) {
    byte[] out = new byte[1 + 2 * size];
    out[0] = 4;
    fixed(w.getAffineX(), out, 1, size);
    fixed(w.getAffineY(), out, 1 + size, size);
    return out;
  }

  private static void fixed(BigInteger n, byte[] out, int at, int size) {
    byte[] b = n.toByteArray();
    int skip = b.length > size ? b.length - size : 0;
    System.arraycopy(b, skip, out, at + size - (b.length - skip), b.length - skip);
  }
}
