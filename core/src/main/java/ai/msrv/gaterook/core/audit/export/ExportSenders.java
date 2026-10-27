package ai.msrv.gaterook.core.audit.export;

import com.google.auth.oauth2.GoogleCredentials;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.Pem;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.secrets.ScopedSecretResolver;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Opens the sender of a destination for one run: its settings typed, its secret read through the {@code secrets}
 * module, its cloud credentials from {@link CloudCredentials} (the same short-lived Google and AWS credentials the
 * connectors use: ADC, a key or a profile, impersonation or AssumeRole). Everything opened goes into the run's taint;
 * {@link Opened#close()} zeroes the secrets.
 */
@Component
public class ExportSenders implements DisposableBean {

  /** A sender ready for one run, and what to mask its errors with. */
  public static final class Opened implements AutoCloseable {

    private final ExportSender sender;
    private final Taint taint;
    private final ScopedSecretResolver resolver;

    Opened(ExportSender sender, Taint taint, ScopedSecretResolver resolver) {
      this.sender = sender;
      this.taint = taint;
      this.resolver = resolver;
    }

    public ExportSender sender() {
      return sender;
    }

    public Taint taint() {
      return taint;
    }

    @Override
    public void close() {
      if (resolver != null) {
        resolver.close();
      }
    }
  }

  private final ExportSecrets secrets;
  private final CloudCredentials cloud;
  private final Bootstrap bootstrap;
  private final LocalListener listener;
  private final Clock clock;
  private final Duration timeout;
  private final HttpClient http;
  private volatile String hostname;

  ExportSenders(ExportSecrets secrets, CloudCredentials cloud, Bootstrap bootstrap, LocalListener listener,
      Clock clock, @Value("${gateway.audit.export.timeout:30s}") Duration timeout) {
    this.secrets = secrets;
    this.cloud = cloud;
    this.bootstrap = bootstrap;
    this.listener = listener;
    this.clock = clock;
    this.timeout = timeout;
    // No redirects (a signed request never follows one elsewhere), no proxy from the environment.
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();
  }

  /**
   * The sender of {@code d}, its secret open.
   *
   * @throws ExportException when the secret can't be read (the core is locked, a store isn't connected) or the cloud
   *                         credentials can't be had
   */
  public Opened open(Destinations.Destination d) throws ExportException {
    Taint taint = new Taint();
    String field = ExportSettings.secretField(d.kind(), d.config());
    ScopedSecretResolver resolver = secrets.open(d.id(), field, taint);
    try {
      ExportSettings.Target target = ExportSettings.parse(d.kind(), d.config());
      CloudCredentials.Owner owner = new CloudCredentials.Owner("audit-export-" + d.id(), d.version(), d.name());
      ExportSender sender = switch (target) {
        case ExportSettings.Siem siem -> new SiemSender(siem, clientKey(resolver, field), hostname(), corePorts(),
            timeout);
        case ExportSettings.S3 s3 -> {
          AwsCredentialsProvider aws = cloud.aws(owner, s3.credentials(), null, resolver);
          yield new S3Sender(s3, CloudCredentials.tainting(aws, taint::add), http, clock, timeout.multipliedBy(4));
        }
        case ExportSettings.BigQuery bq -> {
          GoogleCredentials google = cloud.gcp(owner, bq.credentials(), List.of(BigQuerySender.SCOPE), resolver);
          URI base = cloud.endpoints().service("gcp.bigquery");
          yield new BigQuerySender(bq, google, base, http, taint::add, timeout.multipliedBy(2));
        }
      };
      return new Opened(sender, taint, resolver);
    } catch (ExportException | ConnectorException | RuntimeException e) {
      // Whatever failed, the secret is closed here and the text masked with what was opened for it.
      resolver.close();
      String text = e instanceof RuntimeException ? "The destination's settings can't be used: " + e.getMessage()
          : e.getMessage();
      throw new ExportException(ExportRunner.mask(text, taint), e);
    }
  }

  /** The core's own ports: a destination on this computer may not be the core itself. */
  public Set<Integer> corePorts() {
    Set<Integer> ports = new HashSet<>();
    for (int p : new int[] {bootstrap.port(), bootstrap.publicPort(), listener.port()}) {
      if (p > 0) {
        ports.add(p);
      }
    }
    return ports;
  }

  private static PrivateKey clientKey(SecretResolver resolver, String field) throws ExportException {
    if (field == null) {
      return null;
    }
    try (SecretResolver.Secret key = resolver.secret(field)) {
      return Pem.privateKey(key.unsafeString());
    } catch (SecretUnavailableException e) {
      throw new ExportException("The client key can't be read: " + e.getMessage(), e);
    } catch (IllegalArgumentException e) {
      throw new ExportException("The client key isn't usable: " + e.getMessage(), e);
    }
  }

  /** This computer's name for syslog's {@code HOSTNAME}; looked up once. */
  private String hostname() {
    String name = hostname;
    if (name == null) {
      try {
        name = InetAddress.getLocalHost().getHostName();
      } catch (java.io.IOException | RuntimeException e) {
        name = "-";
      }
      hostname = name;
    }
    return name;
  }

  @Override
  public void destroy() {
    http.close();
  }
}
