package ai.msrv.gaterook.connectors.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PEM text from connection forms: the CA certificate of a private CA (SQL, MongoDB, Redis, Loki, OpenSearch, Vault,
 * Kubernetes, HTTP), a certificate trusted after Test connection, and private keys. Parsing only; trust decisions are
 * {@link TrustStores}'.
 *
 * <p>Errors say what was expected and never quote the input: a private key pasted into the wrong field must not end
 * up in a message.
 */
public final class Pem {

  private static final Pattern BLOCK = Pattern.compile(
      "-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]*?)\\s*-----END \\1-----");

  /** DER of {@code AlgorithmIdentifier { rsaEncryption, NULL }}, the prefix of a PKCS#8 RSA key. */
  private static final byte[] RSA_ALGORITHM = HexFormat.of().parseHex("300d06092a864886f70d0101010500");

  private Pem() {}

  /** One {@code -----BEGIN <type>-----} block and its decoded bytes. */
  public record Block(String type, byte[] der) {
    @Override
    public String toString() {
      return "Pem.Block[" + type + ", " + der.length + " bytes]";
    }
  }

  /** Every PEM block in {@code text}, in order; text outside the blocks (comments, "Bag Attributes") is ignored. */
  public static List<Block> blocks(String text) {
    List<Block> out = new ArrayList<>();
    for (String[] raw : rawBlocks(text)) {
      out.add(decode(raw));
    }
    return out;
  }

  /** {@code {type, base64}} of every block, decoded later: a broken block of another type must not matter. */
  private static List<String[]> rawBlocks(String text) {
    List<String[]> out = new ArrayList<>();
    if (text == null) {
      return out;
    }
    Matcher m = BLOCK.matcher(text);
    while (m.find()) {
      out.add(new String[] {m.group(1), m.group(2)});
    }
    return out;
  }

  private static Block decode(String[] raw) {
    try {
      return new Block(raw[0], Base64.getMimeDecoder().decode(raw[1]));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("A PEM block of type " + raw[0] + " isn't valid base64");
    }
  }

  /**
   * The X.509 certificates of {@code text} (one or a chain).
   *
   * @throws IllegalArgumentException if there is no {@code CERTIFICATE} block or one doesn't parse
   */
  public static List<X509Certificate> certificates(String text) {
    List<X509Certificate> out = new ArrayList<>();
    CertificateFactory factory;
    try {
      factory = CertificateFactory.getInstance("X.509");
    } catch (CertificateException e) {
      throw new IllegalStateException(e);
    }
    for (String[] raw : rawBlocks(text)) {
      if (!raw[0].equals("CERTIFICATE")) {
        continue;
      }
      Block block = decode(raw);
      try {
        out.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(block.der())));
      } catch (CertificateException e) {
        throw new IllegalArgumentException("A CERTIFICATE block isn't a valid X.509 certificate", e);
      }
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException("No certificate found: paste PEM text starting with "
          + "-----BEGIN CERTIFICATE-----");
    }
    return List.copyOf(out);
  }

  /** Writes certificates back as PEM, the form stored in {@code tls.caCertificates} after "Trust this certificate". */
  public static String encode(List<X509Certificate> certificates) {
    StringBuilder out = new StringBuilder();
    Base64.Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
    for (X509Certificate certificate : certificates) {
      try {
        out.append("-----BEGIN CERTIFICATE-----\n").append(encoder.encodeToString(certificate.getEncoded()))
            .append("\n-----END CERTIFICATE-----\n");
      } catch (CertificateException e) {
        throw new IllegalArgumentException("Can't encode a certificate", e);
      }
    }
    return out.toString();
  }

  /** {@code sha256/<base64 of the SubjectPublicKeyInfo digest>}, the pin format of OkHttp and of {@code tls.pins}. */
  public static String sha256Pin(X509Certificate certificate) {
    return "sha256/" + Base64.getEncoder().encodeToString(sha256(certificate.getPublicKey().getEncoded()));
  }

  /** SHA-256 of the whole certificate as colon-separated hex, the fingerprint people compare in a dialog. */
  public static String fingerprint(X509Certificate certificate) {
    try {
      byte[] digest = sha256(certificate.getEncoded());
      return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    } catch (CertificateException e) {
      throw new IllegalArgumentException("Can't encode a certificate", e);
    }
  }

  /**
   * A private key: PKCS#8 ({@code PRIVATE KEY}, RSA, EC, Ed25519) or PKCS#1 ({@code RSA PRIVATE KEY}, converted).
   * Encrypted keys and SEC1 EC keys are refused with the {@code openssl} command that converts them.
   */
  public static PrivateKey privateKey(String text) {
    for (String[] raw : rawBlocks(text)) {
      switch (raw[0]) {
        case "PRIVATE KEY" -> {
          return pkcs8(decode(raw).der());
        }
        case "RSA PRIVATE KEY" -> {
          byte[] pkcs1 = decode(raw).der();
          try {
            return pkcs8(wrapPkcs1(pkcs1));
          } finally {
            Arrays.fill(pkcs1, (byte) 0);
          }
        }
        case "ENCRYPTED PRIVATE KEY" -> throw new IllegalArgumentException(
            "The private key is encrypted. Decrypt it first: openssl pkcs8 -in key.pem -out plain.pem");
        case "EC PRIVATE KEY" -> throw new IllegalArgumentException(
            "Convert the EC key to PKCS#8 first: openssl pkcs8 -topk8 -nocrypt -in key.pem -out pkcs8.pem");
        default -> {
          // Not a key; look further.
        }
      }
    }
    throw new IllegalArgumentException("No private key found: paste PEM text starting with -----BEGIN PRIVATE KEY-----");
  }

  private static PrivateKey pkcs8(byte[] der) {
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    try {
      for (String algorithm : new String[] {"RSA", "EC", "Ed25519", "Ed448", "X25519"}) {
        try {
          return KeyFactory.getInstance(algorithm).generatePrivate(spec);
        } catch (GeneralSecurityException ignored) {
          // Try the next algorithm: PKCS#8 names it in the structure, the JDK wants it up front.
        }
      }
      throw new IllegalArgumentException("The private key isn't RSA, EC or Ed25519 in PKCS#8");
    } finally {
      Arrays.fill(der, (byte) 0);
    }
  }

  /** PKCS#1 {@code RSAPrivateKey} → PKCS#8 {@code PrivateKeyInfo { 0, rsaEncryption, OCTET STRING pkcs1 }}. */
  static byte[] wrapPkcs1(byte[] pkcs1) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(new byte[] {0x02, 0x01, 0x00}, 0, 3);
    body.write(RSA_ALGORITHM, 0, RSA_ALGORITHM.length);
    body.write(0x04);
    writeLength(body, pkcs1.length);
    body.write(pkcs1, 0, pkcs1.length);
    byte[] content = body.toByteArray();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x30);
    writeLength(out, content.length);
    out.write(content, 0, content.length);
    Arrays.fill(content, (byte) 0);
    return out.toByteArray();
  }

  private static void writeLength(ByteArrayOutputStream out, int length) {
    if (length < 0x80) {
      out.write(length);
    } else if (length < 0x100) {
      out.write(0x81);
      out.write(length);
    } else if (length < 0x10000) {
      out.write(0x82);
      out.write(length >>> 8);
      out.write(length & 0xFF);
    } else {
      out.write(0x83);
      out.write(length >>> 16);
      out.write((length >>> 8) & 0xFF);
      out.write(length & 0xFF);
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
