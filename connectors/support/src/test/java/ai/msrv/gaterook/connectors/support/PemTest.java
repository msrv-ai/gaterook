package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.X509TrustManager;
import okhttp3.CertificatePinner;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;

class PemTest {

  @Test
  void readsCertificatesAndIgnoresTextAroundThem() {
    HeldCertificate root = new HeldCertificate.Builder().commonName("Acme Root").certificateAuthority(0).build();
    HeldCertificate other = new HeldCertificate.Builder().commonName("Other").build();
    String pem = "Bag Attributes\n  friendlyName: root\n" + root.certificatePem() + "\n# comment\n"
        + other.certificatePem();

    List<X509Certificate> certificates = Pem.certificates(pem);

    assertThat(certificates).extracting(c -> c.getSubjectX500Principal().getName())
        .containsExactly("CN=Acme Root", "CN=Other");
    assertThat(Pem.certificates(Pem.encode(certificates))).isEqualTo(certificates);
    assertThat(Pem.sha256Pin(certificates.getFirst())).isEqualTo(CertificatePinner.pin(root.certificate()));
    assertThat(Pem.fingerprint(certificates.getFirst())).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
  }

  @Test
  void errorsNeverQuoteTheInput() {
    String secretLooking = "-----BEGIN PRIVATE KEY-----\nMIIsecretbits\n-----END PRIVATE KEY-----";
    assertThatThrownBy(() -> Pem.certificates(secretLooking)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("MIIsecretbits").hasMessageContaining("BEGIN CERTIFICATE");
    assertThatThrownBy(() -> Pem.certificates("not pem at all")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsPkcs8AndPkcs1PrivateKeys() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    byte[] pkcs8 = pair.getPrivate().getEncoded();
    String pkcs8Pem = pem("PRIVATE KEY", pkcs8);
    // PKCS#1 is the OCTET STRING at the end of the PKCS#8 structure: unwrap it to build the fixture.
    byte[] pkcs1 = unwrapPkcs8(pkcs8);
    String pkcs1Pem = pem("RSA PRIVATE KEY", pkcs1);

    PrivateKey fromPkcs8 = Pem.privateKey(pkcs8Pem);
    PrivateKey fromPkcs1 = Pem.privateKey(pkcs1Pem);

    assertThat(((RSAPrivateCrtKey) fromPkcs8).getModulus()).isEqualTo(((RSAPrivateCrtKey) pair.getPrivate()).getModulus());
    assertThat(fromPkcs1.getEncoded()).isEqualTo(pkcs8);

    var ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    assertThat(Pem.privateKey(pem("PRIVATE KEY", ed.getPrivate().getEncoded())).getAlgorithm()).isIn("EdDSA", "Ed25519");
    assertThatThrownBy(() -> Pem.privateKey(pem("EC PRIVATE KEY", new byte[] {1, 2})))
        .hasMessageContaining("openssl pkcs8 -topk8");
    assertThatThrownBy(() -> Pem.privateKey(pem("ENCRYPTED PRIVATE KEY", new byte[] {1, 2})))
        .hasMessageContaining("encrypted");
  }

  @Test
  void trustStoresTrustExactlyWhatTheyAreGiven() throws Exception {
    HeldCertificate root = new HeldCertificate.Builder().commonName("Acme Root").certificateAuthority(0).build();
    HeldCertificate leaf = new HeldCertificate.Builder().commonName("api.test").signedBy(root).build();
    X509TrustManager onlyRoot = TrustStores.of(List.of(root.certificate()), false);
    onlyRoot.checkServerTrusted(new X509Certificate[] {leaf.certificate(), root.certificate()}, "RSA");
    assertThat(onlyRoot.getAcceptedIssuers()).containsExactly(root.certificate());

    X509TrustManager withPlatform = TrustStores.of(List.of(root.certificate()), true);
    assertThat(withPlatform.getAcceptedIssuers().length).isGreaterThan(1);
    assertThatThrownBy(() -> TrustStores.platform().checkServerTrusted(
        new X509Certificate[] {leaf.certificate(), root.certificate()}, "RSA"))
        .isInstanceOf(java.security.cert.CertificateException.class);
    assertThat(TrustStores.sslContext(onlyRoot).getProtocol()).isEqualTo("TLS");
  }

  private static String pem(String type, byte[] der) {
    return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
        + "-----\n";
  }

  /** The last element of PrivateKeyInfo is OCTET STRING privateKey: skip the header and return its content. */
  private static byte[] unwrapPkcs8(byte[] der) {
    int i = 0;
    i++; // SEQUENCE
    i += lengthBytes(der, i);
    i += 3; // INTEGER 0
    i++; // AlgorithmIdentifier SEQUENCE
    int algLength = der[i] & 0xFF;
    i += 1 + algLength;
    i++; // OCTET STRING
    int start = i + lengthBytes(der, i);
    return Arrays.copyOfRange(der, start, der.length);
  }

  private static int lengthBytes(byte[] der, int at) {
    int first = der[at] & 0xFF;
    return first < 0x80 ? 1 : 1 + (first & 0x7F);
  }
}
