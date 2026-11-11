package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Properties;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.postgresql.jdbc.SslMode;
import org.postgresql.ssl.NonValidatingFactory;
import org.postgresql.ssl.WrappedFactory;
import org.postgresql.util.PSQLException;

/**
 * pgjdbc's TLS as GateRook uses it ({@code sslfactory}): the server is checked as pgjdbc's own factory would (against
 * {@code sslrootcert}, the connection's CA, or else the JVM's trust store; not at all for {@code require}), and pgjdbc
 * still checks the host name for {@code verify-full}. Unlike pgjdbc's factory, it never offers a client certificate.
 * That factory loads one whenever a server asks for one, as CockroachDB and a PostgreSQL with {@code ssl_ca_file}
 * always do: from {@code ~/.postgresql/postgresql.crt}, a file of the user's that is not the connection's, or, when
 * pointed elsewhere, failing the handshake for a file that isn't there.
 */
public final class PostgresSslFactory extends WrappedFactory {

  public PostgresSslFactory(Properties info) throws PSQLException, GeneralSecurityException, IOException {
    TrustManager[] trust;
    if (!SslMode.of(info).verifyCertificate()) {
      trust = new TrustManager[] {new NonValidatingFactory.NonValidatingTM()};
    } else {
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      String root = info.getProperty("sslrootcert");
      if (root == null || root.isBlank()) {
        tmf.init((KeyStore) null);
      } else {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        try (InputStream in = Files.newInputStream(Path.of(root))) {
          int i = 0;
          for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
            store.setCertificateEntry("ca-" + i++, c);
          }
        }
        tmf.init(store);
      }
      trust = tmf.getTrustManagers();
    }
    SSLContext context = SSLContext.getInstance("TLS");
    // No key managers: the handshake answers a server's request for a certificate with none.
    context.init(new KeyManager[0], trust, null);
    factory = context.getSocketFactory();
  }
}
