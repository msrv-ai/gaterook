package com.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.plugin.protocol.Messages;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The guarded client the host hands a plugin: what its grant lets through, and what it never reaches. */
class EgressHttpClientTest {

  private static final List<Integer> CORE_PORTS = List.of(7465, 7466);

  private HttpServer server;
  private final Map<String, List<String>> seen = new ConcurrentHashMap<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @ParameterizedTest(name = "{0} grants {1}:{2}: {3}")
  @CsvSource({
      "acme.atlassian.net,      ACME.atlassian.net, 443,  true",
      "ch.acme.internal:8443,   ch.acme.internal,   8443, true",
      "ch.acme.internal:8443,   ch.acme.internal,   8123, false",
      "ch.acme.internal:*,      ch.acme.internal,   8123, true",
      "ch.acme.internal:8443,   acme.internal,      8443, false",
      "127.0.0.1:9000,          127.0.0.1,          9000, true",
      "[::1],                   ::1,                8443, true",
      "[::1]:8443,              ::1,                8443, true",
      "[::1]:8443,              ::1,                8123, false",
      "[::1]:*,                 ::1,                1,    true",
      "[2001:db8::7]:443,       2001:db8::7,        443,  true",
      "[::1,                    ::1,                443,  false",
      "[::1]x,                  ::1,                443,  false",
      "[::1]:,                  ::1,                443,  false",
      "[::1]:443,               [::1],              443,  false",
  })
  void grantsTheHostsAndPortsOfItsEntries(String entry, String host, int port, boolean granted) {
    assertThat(EgressHttpClient.granted(List.of(entry), host, port)).isEqualTo(granted);
  }

  @Test
  void aBracketedIpv6HostWithoutAPortIsGrantedOnEveryPort() throws Exception {
    InetAddress v6 = InetAddress.ofLiteral("::1");
    assumeTrue(canBind(v6), "IPv6 loopback is available");
    int port = serve(v6);
    EgressHttpClient egress = new EgressHttpClient(new Messages.EgressGrant(List.of("[::1]"), List.of("::1/128"),
        CORE_PORTS));

    ExecContext.EgressResponse r = egress.send(get("http://[::1]:" + port + "/"), 1024);

    assertThat(r.status()).isEqualTo(200);
  }

  @Test
  void speaksHttp11Only() throws Exception {
    // An HTTP/2 connection is reused by host name whatever the name resolves to now: a call could ride on a
    // connection another call's policy opened. HTTP/1.1 reuses only a connection to the address just checked.
    int port = serve(InetAddress.ofLiteral("127.0.0.1"));
    EgressHttpClient egress = new EgressHttpClient(new Messages.EgressGrant(List.of("127.0.0.1:" + port),
        List.of("127.0.0.0/8"), CORE_PORTS));

    assertThat(egress.send(get("http://127.0.0.1:" + port + "/"), 1024).status()).isEqualTo(200);

    assertThat(seen).doesNotContainKeys("Upgrade", "Http2-settings");
  }

  @Test
  void anIpv6AddressCarryingAPrivateIpv4AddressIsPrivate() {
    EgressHttpClient egress = new EgressHttpClient(new Messages.EgressGrant(List.of("[64:ff9b::a00:5]:8443"),
        List.of(), CORE_PORTS));

    assertThatThrownBy(() -> egress.send(get("https://[64:ff9b::a00:5]:8443/"), 1024))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("allowedPrivateRanges");
        });
  }

  @Test
  void anIpv6AddressCarryingTheMetadataAddressIsNeverReached() {
    List<String> everything = List.of("0.0.0.0/0", "::/0");
    for (String host : List.of("[::a9fe:a9fe]", "[64:ff9b::a9fe:a9fe]", "[2002:a9fe:a9fe::1]")) {
      EgressHttpClient egress = new EgressHttpClient(new Messages.EgressGrant(List.of(host + ":443"), everything,
          CORE_PORTS));
      assertThatThrownBy(() -> egress.send(get("https://" + host + "/"), 1024)).as(host)
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
  }

  @Test
  void theCorePortsStayClosedOnAnyFormOfLoopback() {
    for (String host : List.of("[::1]", "127.0.0.1", "[::127.0.0.1]", "[64:ff9b::7f00:1]")) {
      EgressHttpClient egress = new EgressHttpClient(new Messages.EgressGrant(List.of(host + ":7465"),
          List.of("127.0.0.0/8", "::1/128"), CORE_PORTS));
      assertThatThrownBy(() -> egress.send(get("http://" + host + ":7465/"), 1024)).as(host)
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).contains("own port 7465");
          });
    }
  }

  private static ExecContext.EgressRequest get(String uri) {
    return new ExecContext.EgressRequest("GET", URI.create(uri), Map.of(), null, Duration.ofSeconds(3));
  }

  private int serve(InetAddress address) throws IOException {
    server = HttpServer.create(new InetSocketAddress(address, 0), 0);
    server.createContext("/", exchange -> {
      seen.putAll(exchange.getRequestHeaders());
      byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream o = exchange.getResponseBody()) {
        o.write(body);
      }
    });
    server.start();
    return server.getAddress().getPort();
  }

  private static boolean canBind(InetAddress address) {
    try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, address)) {
      return s.getLocalPort() > 0;
    } catch (IOException e) {
      return false;
    }
  }
}
