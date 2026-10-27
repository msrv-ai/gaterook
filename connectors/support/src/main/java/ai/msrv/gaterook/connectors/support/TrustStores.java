package ai.msrv.gaterook.connectors.support;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Trust for TLS connections of connectors: the platform's CAs, a private CA from the connection form, or both. Never
 * an "accept everything" manager: a certificate nobody trusts yet goes through Test connection's "Trust this
 * certificate" (TOFU), which stores it as a CA of the connection (report 10 §5).
 *
 * <p>The stores are in memory: nothing is written to disk, nothing global ({@code javax.net.ssl.*} properties,
 * default {@code SSLContext}) changes — connectors must not set JVM-wide defaults (ARCHITECTURE.md §3).
 */
public final class TrustStores {

  private TrustStores() {}

  /** The JDK's default trust manager: the platform's CAs. */
  public static X509TrustManager platform() {
    return trustManager((KeyStore) null);
  }

  /**
   * A trust manager for {@code certificates}, plus the platform's CAs when {@code includePlatform}. An empty list
   * with {@code includePlatform} is {@link #platform()}.
   */
  public static X509TrustManager of(List<X509Certificate> certificates, boolean includePlatform) {
    if (certificates.isEmpty() && includePlatform) {
      return platform();
    }
    List<X509Certificate> all = new ArrayList<>(certificates);
    if (includePlatform) {
      all.addAll(List.of(platform().getAcceptedIssuers()));
    }
    return trustManager(keyStore(all));
  }

  /** An in-memory PKCS#12 store with each certificate as a trusted entry (Jedis, MySQL and pgjdbc want a store). */
  public static KeyStore keyStore(List<X509Certificate> certificates) {
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      int i = 0;
      for (X509Certificate certificate : certificates) {
        store.setCertificateEntry("ca-" + i++, certificate);
      }
      return store;
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("Can't build an in-memory trust store", e);
    }
  }

  /** A TLS context that trusts what {@code trustManager} trusts, with the JDK's default key managers and random. */
  public static SSLContext sslContext(X509TrustManager trustManager) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {trustManager}, null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Can't create a TLS context", e);
    }
  }

  private static X509TrustManager trustManager(KeyStore store) {
    try {
      TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      for (TrustManager manager : factory.getTrustManagers()) {
        if (manager instanceof X509TrustManager x509) {
          return x509;
        }
      }
      throw new IllegalStateException("No X509TrustManager in " + factory.getAlgorithm());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Can't build a trust manager", e);
    }
  }
}
