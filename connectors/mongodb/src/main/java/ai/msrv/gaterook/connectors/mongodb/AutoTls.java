package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterConnectionMode;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.bson.BsonDocument;
import org.bson.BsonInt32;

/** Resolves Auto before a credential-bearing client is created; explicit TLS and URI choices never enter here. */
final class AutoTls {

  private AutoTls() {}

  static MongoSettings resolve(MongoSettings settings, Duration budget) {
    if (!settings.autoTls()) {
      return settings;
    }
    long deadline = System.nanoTime() + budget.toNanos();
    List<String> hosts = settings.parsed().hosts();
    MongoException failure = null;
    for (int i = 0; i < hosts.size(); i++) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        break;
      }
      ServerAddress address = new ServerAddress(hosts.get(i));
      // A dead first seed must not consume the time reserved for every other seed.
      long hostDeadline = System.nanoTime() + remaining / (hosts.size() - i);
      try {
        boolean tls = bounded(() -> detect(settings, address, hostDeadline), hostDeadline);
        return settings.withTls(tls);
      } catch (IOException e) {
        failure = new MongoSocketException("Could not determine the MongoDB connection type", address, e);
        // A server that offers TLS but cannot be verified is never replaced with a plaintext connection.
        if ((settings.caCertificate() != null && mayBePlaintext(e))
            || (e instanceof SSLException && !mayBePlaintext(e))) {
          throw failure;
        }
      } catch (MongoInterruptedException e) {
        throw e;
      } catch (MongoException e) {
        failure = e;
      }
    }
    if (failure != null) {
      throw failure;
    }
    throw new MongoTimeoutException("No time remained to determine the MongoDB connection type.");
  }

  private static boolean detect(MongoSettings settings, ServerAddress address, long deadline) throws IOException {
    try {
      handshake(settings, address, deadline);
      return true;
    } catch (IOException e) {
      if (settings.caCertificate() != null || !mayBePlaintext(e)) {
        throw e;
      }
      // Plain mongod closes a TLS ClientHello. Confirm MongoDB on a separate connection with no credentials.
      hello(address, deadline);
      return false;
    }
  }

  private static void handshake(MongoSettings settings, ServerAddress address, long deadline) throws IOException {
    try (Socket transport = new Socket()) {
      transport.connect(new InetSocketAddress(address.getHost(), address.getPort()), remainingMillis(deadline));
      try (SSLSocket socket = (SSLSocket) ClientSettings.sslContext(settings).getSocketFactory()
          .createSocket(transport, address.getHost(), address.getPort(), true)) {
        socket.setSoTimeout(remainingMillis(deadline));
        SSLParameters parameters = socket.getSSLParameters();
        if (!settings.allowInvalidHostnames()) {
          parameters.setEndpointIdentificationAlgorithm("HTTPS");
        }
        socket.setSSLParameters(parameters);
        socket.startHandshake();
      }
    }
  }

  private static void hello(ServerAddress address, long deadline) throws IOException {
    int timeout = remainingMillis(deadline);
    MongoClientSettings candidate = MongoClientSettings.builder()
        .applicationName(ClientSettings.APPLICATION)
        .readPreference(ReadPreference.primaryPreferred())
        .retryReads(false).retryWrites(false)
        .timeout(timeout, TimeUnit.MILLISECONDS)
        .applyToClusterSettings(c -> c.hosts(List.of(address)).mode(ClusterConnectionMode.SINGLE)
            .serverSelectionTimeout(timeout, TimeUnit.MILLISECONDS))
        .applyToSocketSettings(s -> s.connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS))
        .applyToConnectionPoolSettings(p -> p.maxSize(1).minSize(0))
        .applyToSslSettings(s -> s.enabled(false))
        .build();
    try (var client = MongoClients.create(candidate)) {
      var admin = client.getDatabase("admin");
      try {
        admin.runCommand(new BsonDocument("hello", new BsonInt32(1)), BsonDocument.class);
      } catch (MongoCommandException e) {
        if (e.getErrorCode() != 59) {
          throw e;
        }
        // Supported MongoDB 4.2 servers predate hello; neither command authenticates or changes data.
        admin.runCommand(new BsonDocument("isMaster", new BsonInt32(1)), BsonDocument.class);
      }
    }
  }

  /** Restrict fallback to transport closure or an actual non-TLS response, never certificates, alerts or timeouts. */
  static boolean mayBePlaintext(Throwable error) {
    boolean eligible = false;
    for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (cause instanceof CertificateException || cause instanceof SocketTimeoutException
          || message.contains("certificate") || message.contains("pkix") || message.contains("fatal alert")
          || message.contains("protocol_version") || message.contains("handshake_failure")) {
        return false;
      }
      eligible |= cause instanceof EOFException
          || cause instanceof SocketException && message.contains("connection reset")
          || cause instanceof SSLException && (message.contains("unrecognized ssl message")
              || message.contains("ssl peer shut down incorrectly"));
    }
    return eligible;
  }

  private static int remainingMillis(long deadline) throws SocketTimeoutException {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new SocketTimeoutException("MongoDB connection type detection timed out");
    }
    return Math.clamp(TimeUnit.NANOSECONDS.toMillis(nanos), 1, Integer.MAX_VALUE);
  }

  /** Bounds DNS as well as sockets; interrupting a virtual thread closes its blocking socket. */
  private static <T> T bounded(Callable<T> action, long deadline) throws IOException {
    FutureTask<T> task = new FutureTask<>(action);
    Thread.ofVirtual().name("gw-mongodb-transport").start(task);
    try {
      return task.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new SocketTimeoutException("MongoDB connection type detection timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MongoInterruptedException("MongoDB connection type detection was interrupted", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException io) {
        throw io;
      }
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("MongoDB connection type detection failed", e.getCause());
    } finally {
      task.cancel(true);
    }
  }
}
