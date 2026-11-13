package com.gaterook.core.connections.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Suggestions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The setup form's Auto for http:// or https://: which scheme an address typed without one gets. */
class TlsProbeTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode SCHEMA = JSON.readTree("""
      {"type":"object","properties":{
        "url":{"type":"string","format":"uri","pattern":"^https?://[^\\\\s?#]+$"},
        "name":{"type":"string"},
        "proxy":{"type":"object","properties":{"url":{"type":"string","pattern":"^https?://.+$"}}}}}""");

  @Test
  void anAddressWithoutASchemeGetsTheOneItAnswered() {
    List<String> asked = new ArrayList<>();
    Suggestions s = TlsProbe.schemes(SCHEMA, JSON.readTree("""
        {"url":"qdrant.dev.internal:6333","name":"localhost:1","proxy":{"url":"proxy.internal:3128"}}"""),
        (host, port) -> {
          asked.add(host + ":" + port);
          return port == 6333 ? TlsProbe.Outcome.UNTRUSTED : TlsProbe.Outcome.PLAIN;
        });

    assertThat(asked).containsExactly("qdrant.dev.internal:6333", "proxy.internal:3128");
    assertThat(s.fields().get("/url").value()).isEqualTo("https://qdrant.dev.internal:6333");
    assertThat(s.fields().get("/proxy/url").value()).isEqualTo("http://proxy.internal:3128");
    assertThat(s.notes().get(0)).contains("certificate isn't trusted").contains("CA certificate");
    assertThat(s.notes().get(1)).contains("answers without TLS");
  }

  @Test
  void aUrlWithASchemeOrNoAddressIsLeftAlone() {
    Suggestions s = TlsProbe.schemes(SCHEMA, JSON.readTree("""
        {"url":"https://qdrant.dev.internal:6333","proxy":{"url":"not an address"}}"""),
        (host, port) -> { throw new AssertionError("no probe for " + host); });

    assertThat(s.isEmpty()).isTrue();
  }

  @Test
  void withoutAPortItTriesHttpsThenPlainHttp() {
    List<Integer> ports = new ArrayList<>();
    Suggestions s = TlsProbe.schemes(SCHEMA, JSON.readTree("{\"url\":\"intranet.example.test/api\"}"),
        (host, port) -> {
          ports.add(port);
          return port == 80 ? TlsProbe.Outcome.PLAIN : TlsProbe.Outcome.UNREACHABLE;
        });

    assertThat(ports).containsExactly(443, 80);
    assertThat(s.fields().get("/url").value()).isEqualTo("http://intranet.example.test/api");

    Suggestions nothing = TlsProbe.schemes(SCHEMA, JSON.readTree("{\"url\":\"gone.example.test:9\"}"),
        (host, port) -> TlsProbe.Outcome.UNREACHABLE);
    assertThat(nothing.fields()).isEmpty();
    assertThat(nothing.notes()).singleElement().asString().startsWith("Nothing answers on gone.example.test:9");
  }

  @Test
  void aPlainServerAnswersPlainAndAClosedPortNothing() throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread.ofVirtual().start(() -> {
        try (Socket client = server.accept(); InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream()) {
          in.read(new byte[512]);
          out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          out.flush();
        } catch (IOException ignored) {
          // The probe hung up.
        }
      });
      assertThat(TlsProbe.probe("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(3)))
          .isEqualTo(TlsProbe.Outcome.PLAIN);
    }
    int closed;
    try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = free.getLocalPort();
    }
    assertThat(TlsProbe.probe("127.0.0.1", closed, Duration.ofSeconds(1))).isEqualTo(TlsProbe.Outcome.UNREACHABLE);
  }
}
