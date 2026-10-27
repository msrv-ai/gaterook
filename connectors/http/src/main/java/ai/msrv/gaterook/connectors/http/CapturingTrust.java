package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connectors.support.TrustStores;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The connection's trust (platform CAs plus its trusted certificates), keeping the chain the server presented: Test
 * connection shows an unknown certificate and offers to trust it before any credential leaves (report 10 §5 step 2).
 */
final class CapturingTrust extends X509ExtendedTrustManager implements HttpExecutor.ChainCapture {

  private final X509TrustManager delegate;
  private volatile List<X509Certificate> chain = List.of();
  private volatile boolean refused;

  CapturingTrust(EgressSpec.Tls tls) {
    this.delegate = TrustStores.of(tls.extraCertificates(), tls.platformTrust());
  }

  @Override
  public SSLSocketFactory socketFactory() {
    return TrustStores.sslContext(this).getSocketFactory();
  }

  @Override
  public List<X509Certificate> chain() {
    return chain;
  }

  /** Whether the chain was refused by the trust itself (not the host name, not the pins). */
  boolean refused() {
    return refused;
  }

  @Override
  public void checkServerTrusted(X509Certificate[] certs, String authType, Socket socket) throws CertificateException {
    chain = List.of(certs);
    try {
      if (delegate instanceof X509ExtendedTrustManager extended) {
        extended.checkServerTrusted(certs, authType, socket);
      } else {
        delegate.checkServerTrusted(certs, authType);
      }
    } catch (CertificateException e) {
      refused = true;
      throw e;
    }
  }

  @Override
  public void checkServerTrusted(X509Certificate[] certs, String authType, SSLEngine engine)
      throws CertificateException {
    chain = List.of(certs);
    try {
      if (delegate instanceof X509ExtendedTrustManager extended) {
        extended.checkServerTrusted(certs, authType, engine);
      } else {
        delegate.checkServerTrusted(certs, authType);
      }
    } catch (CertificateException e) {
      refused = true;
      throw e;
    }
  }

  @Override
  public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
    chain = List.of(certs);
    try {
      delegate.checkServerTrusted(certs, authType);
    } catch (CertificateException e) {
      refused = true;
      throw e;
    }
  }

  @Override
  public void checkClientTrusted(X509Certificate[] certs, String authType, Socket socket) throws CertificateException {
    throw new CertificateException("MCP Gateway is never a TLS server here");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] certs, String authType, SSLEngine engine)
      throws CertificateException {
    throw new CertificateException("MCP Gateway is never a TLS server here");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
    throw new CertificateException("MCP Gateway is never a TLS server here");
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return delegate.getAcceptedIssuers();
  }
}
