package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/**
 * Files the drivers read a private CA from (report 04 §6.5): pgjdbc takes a PEM path ({@code sslrootcert}),
 * Connector/J a key store URL. Written once per content, named by its hash, 0600 in a 0700 folder under the connector's
 * work folder. A CA is not a secret, but it decides whom the password is sent to.
 */
final class CertificateFiles {

  /** The password of the PKCS12 trust store: a key store needs one; it protects nothing. */
  static final String TRUST_STORE_PASSWORD = "mcp-gateway";

  private CertificateFiles() {}

  /** The PEM of {@code ca} as a file; refuses text without a certificate. */
  static Path pem(Path folder, String ca) throws IOException {
    List<X509Certificate> certificates = certificates(ca);
    byte[] bytes = Pem.encode(certificates).getBytes(StandardCharsets.UTF_8);
    return write(folder, "ca-" + hash(bytes) + ".pem", bytes);
  }

  /** {@code ca} as a PKCS12 trust store file. */
  static Path pkcs12(Path folder, String ca) throws IOException {
    List<X509Certificate> certificates = certificates(ca);
    byte[] pem = Pem.encode(certificates).getBytes(StandardCharsets.UTF_8);
    Path target = folder.resolve("ca-" + hash(pem) + ".p12");
    if (Files.isRegularFile(target)) {
      return target;
    }
    try {
      KeyStore source = TrustStores.keyStore(certificates);
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      for (String alias : java.util.Collections.list(source.aliases())) {
        store.setCertificateEntry(alias, source.getCertificate(alias));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      store.store(out, TRUST_STORE_PASSWORD.toCharArray());
      return write(folder, target.getFileName().toString(), out.toByteArray());
    } catch (GeneralSecurityException e) {
      throw new IOException("The CA certificate can't be stored as a trust store: " + e.getMessage(), e);
    }
  }

  /** A path that never exists: pgjdbc then doesn't fall back to the client certificate in ~/.postgresql. */
  static Path none(Path folder) {
    return folder.resolve("no-client-certificate");
  }

  private static List<X509Certificate> certificates(String ca) throws IOException {
    List<X509Certificate> certificates;
    try {
      certificates = Pem.certificates(ca);
    } catch (RuntimeException e) {
      throw new IOException("The CA certificate isn't a PEM certificate.", e);
    }
    if (certificates.isEmpty()) {
      throw new IOException("The CA certificate field holds no certificate.");
    }
    return certificates;
  }

  private static Path write(Path folder, String name, byte[] bytes) throws IOException {
    if (folder == null) {
      throw new IOException("The connector has no work folder for the CA certificate.");
    }
    Files.createDirectories(folder);
    try {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException e) {
      // Windows: the folder inherits the ACL of the user's profile.
    }
    Path target = folder.resolve(name);
    if (Files.isRegularFile(target)) {
      return target;
    }
    Path tmp = Files.createTempFile(folder, name, ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows, as above.
      }
      Files.write(tmp, bytes);
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileAlreadyExistsException e) {
        // Another call wrote the same content first.
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
    return target;
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
