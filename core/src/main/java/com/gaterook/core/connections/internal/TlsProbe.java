package com.gaterook.core.connections.internal;

import com.gaterook.connector.api.Suggestions;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import tools.jackson.databind.JsonNode;

/**
 * The setup form's Auto for {@code http://} or {@code https://}: an address typed into a URL field without a scheme is
 * tried once with a TLS handshake (the default trust store and a host name check; a TLS ClientHello is all that is
 * sent, never a credential or a request), and the URL with the scheme that answered is offered for the field.
 * A server that speaks TLS gets {@code https://} even when its certificate isn't trusted (the person then adds its CA):
 * a failed certificate is never a reason to drop encryption.
 */
public final class TlsProbe {

  /** What the address answered. */
  public enum Outcome { VERIFIED, UNTRUSTED, PLAIN, UNREACHABLE }

  /** {@code host}, {@code host:port} or {@code [v6]:port}, then an optional path: what people type for a server. */
  private static final Pattern ADDRESS = Pattern.compile(
      "^(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)(?::(\\d{1,5}))?(/[^\\s?#]*)?$");

  private TlsProbe() {}

  /** A URL field of a schema: a string whose pattern allows both {@code http://} and {@code https://}. */
  public static boolean urlField(JsonNode schema) {
    String pattern = schema.path("pattern").asString("");
    return "string".equals(schema.path("type").asString("")) && pattern.contains("https?://");
  }

  /**
   * Suggestions for every URL field of {@code configSchema} whose value in {@code config} has no scheme: the URL with
   * the scheme the address answered, and a note on what was found.
   *
   * @param probe how one address is tried ({@link #probe}; tests pass their own)
   */
  public static Suggestions schemes(JsonNode configSchema, JsonNode config,
      BiFunction<String, Integer, Outcome> probe) {
    Suggestions.Builder out = Suggestions.builder();
    if (configSchema == null || config == null) {
      return out.build();
    }
    for (SchemaPointers.Located field : SchemaPointers.collect(configSchema, TlsProbe::urlField)) {
      if (field.template().contains("*")) {
        continue;
      }
      for (Map.Entry<String, JsonNode> value : SchemaPointers.values(config, field.template()).entrySet()) {
        String typed = value.getValue().isString() ? value.getValue().asString().strip() : "";
        Matcher m = ADDRESS.matcher(typed);
        if (typed.isEmpty() || typed.contains("://") || !m.matches()) {
          continue;
        }
        String host = m.group(1).startsWith("[") ? m.group(1).substring(1, m.group(1).length() - 1) : m.group(1);
        Integer port = m.group(2) == null ? null : Integer.valueOf(m.group(2));
        if (port != null && (port < 1 || port > 65535)) {
          continue;
        }
        String address = m.group(1) + (port == null ? "" : ":" + port);
        Outcome tls = probe.apply(host, port == null ? 443 : port);
        String scheme = null;
        String note;
        switch (tls) {
          case VERIFIED -> {
            scheme = "https";
            note = address + " speaks TLS with a trusted certificate: https:// is added when you leave the field.";
          }
          case UNTRUSTED -> {
            scheme = "https";
            note = address + " speaks TLS, but its certificate isn't trusted on this computer: https:// is added. "
                + "Paste the server's CA certificate under Advanced settings.";
          }
          case PLAIN -> {
            scheme = "http";
            note = address + " answers without TLS: http:// is added when you leave the field.";
          }
          default -> {
            if (port == null && probe.apply(host, 80) == Outcome.PLAIN) {
              scheme = "http";
              note = address + " answers on port 80 without TLS: http:// is added when you leave the field.";
            } else {
              note = "Nothing answers on " + address + (port == null ? " (port 443 or 80)" : "")
                  + ". Check the address, or type it with http:// or https://.";
            }
          }
        }
        if (scheme != null) {
          String url = scheme + "://" + typed;
          out.value(value.getKey(), "a TLS check of " + address, url, List.of(Suggestions.Option.of(url)));
        }
        out.note(note);
      }
    }
    return out.build();
  }

  /** One TLS handshake to {@code host:port} within {@code timeout}. */
  public static Outcome probe(String host, int port, Duration timeout) {
    int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    try (Socket transport = new Socket()) {
      try {
        transport.connect(new InetSocketAddress(host, port), millis);
      } catch (IOException | IllegalArgumentException e) {
        return Outcome.UNREACHABLE;
      }
      transport.setSoTimeout(millis);
      try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
          .createSocket(transport, host, port, true)) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return Outcome.VERIFIED;
      } catch (IOException e) {
        if (untrusted(e)) {
          return Outcome.UNTRUSTED;
        }
        return mayBePlaintext(e) ? Outcome.PLAIN : Outcome.UNREACHABLE;
      }
    } catch (IOException e) {
      return Outcome.UNREACHABLE;
    }
  }

  /** The handshake reached the certificate check and failed it: the server speaks TLS. */
  static boolean untrusted(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (cause instanceof CertificateException || message.contains("pkix") || message.contains("certificate")
          || message.contains("no subject alternative") || message.contains("no name matching")) {
        return true;
      }
    }
    return false;
  }

  /** A closed transport or a non-TLS answer, never a certificate, an alert or a timeout (as MongoDB's Auto). */
  static boolean mayBePlaintext(Throwable error) {
    boolean eligible = false;
    List<Throwable> chain = new ArrayList<>();
    for (Throwable cause = error; cause != null && chain.size() < 16;
        cause = cause.getCause() == cause ? null : cause.getCause()) {
      chain.add(cause);
    }
    for (Throwable cause : chain) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (cause instanceof CertificateException || cause instanceof SocketTimeoutException
          || message.contains("certificate") || message.contains("pkix") || message.contains("fatal alert")
          || message.contains("protocol_version") || message.contains("handshake_failure")) {
        return false;
      }
      eligible |= cause instanceof EOFException
          || cause instanceof SocketException && message.contains("connection reset")
          || cause instanceof SSLException && (message.contains("unrecognized ssl message")
              || message.contains("ssl peer shut down incorrectly") || message.contains("plaintext"));
    }
    return eligible;
  }
}
