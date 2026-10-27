package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ReadPreference;
import com.mongodb.spi.dns.DnsClient;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.connectors.support.TrustStores;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.bson.UuidRepresentation;

/**
 * The driver's settings of one client (report 05 §7): the connection string without user information, then what the
 * gateway decides on top of it — its application name, the read preference of the form, standard UUIDs, no
 * retryable writes, its own DNS client for {@code mongodb+srv://} (also when the string is parsed: the TXT lookup
 * happens there), bounded server selection, socket and pool waits, at most {@code poolSize} connections, and TLS
 * with the form's CA in memory (never a JVM-wide default).
 */
final class ClientSettings {

  static final String APPLICATION = "gaterook";
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration POOL_WAIT = Duration.ofSeconds(2);

  private ClientSettings() {}

  /**
   * @param serverSelection how long a call waits for a suitable server
   * @param readTimeout     the socket's read timeout: longer than any {@code maxTimeMS} the client runs
   */
  static MongoClientSettings build(MongoSettings s, MongoLogin login, DnsClient dns, Duration serverSelection,
      Duration readTimeout) {
    ConnectionString cs = new ConnectionString(s.uri(), dns);
    MongoClientSettings.Builder b = MongoClientSettings.builder()
        .applyConnectionString(cs)
        .applicationName(APPLICATION)
        .readPreference(ReadPreference.valueOf(s.readPreference()))
        .uuidRepresentation(UuidRepresentation.STANDARD)
        .retryWrites(false)
        .dnsClient(dns)
        .applyToClusterSettings(c -> c.serverSelectionTimeout(serverSelection.toMillis(), TimeUnit.MILLISECONDS))
        .applyToSocketSettings(c -> c.connectTimeout((int) CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout((int) readTimeout.toMillis(), TimeUnit.MILLISECONDS))
        .applyToConnectionPoolSettings(p -> p.maxSize(s.poolSize()).minSize(0)
            .maxConnectionIdleTime(60, TimeUnit.SECONDS).maxWaitTime(POOL_WAIT.toMillis(), TimeUnit.MILLISECONDS))
        .applyToSslSettings(t -> {
          t.enabled(s.tls());
          if (s.tls()) {
            t.context(sslContext(s)).invalidHostNameAllowed(s.allowInvalidHostnames());
          }
        });
    MongoCredential credential = login.credential(s);
    if (credential != null) {
      b.credential(credential);
    }
    return b.build();
  }

  static SSLContext sslContext(MongoSettings s) {
    return TrustStores.sslContext(s.caCertificate() == null ? TrustStores.platform()
        : TrustStores.of(Pem.certificates(s.caCertificate()), false));
  }
}
