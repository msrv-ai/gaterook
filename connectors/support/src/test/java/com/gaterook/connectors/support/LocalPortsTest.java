package com.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Suggestions;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/** localhost is offered for Host when a server listens on this computer, never filled in. */
class LocalPortsTest {

  @Test
  void aListeningServerIsOfferedAsLocalhost() throws IOException {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Suggestions.Builder out = Suggestions.builder();

      assertThat(LocalPorts.offer(out, "/connection/host", "A PostgreSQL server", server.getLocalPort()))
          .containsExactly(server.getLocalPort());

      Suggestions.Field host = out.build().fields().get("/connection/host");
      assertThat(host.value()).isNull();
      assertThat(host.options()).singleElement().satisfies(o -> {
        assertThat(o.value()).isEqualTo("localhost");
        assertThat(o.detail()).isEqualTo("A PostgreSQL server answers on port " + server.getLocalPort());
      });
    }
  }

  @Test
  void nothingIsOfferedWhenNothingListens() throws IOException {
    int closed;
    try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closed = free.getLocalPort();
    }
    Suggestions.Builder out = Suggestions.builder();
    assertThat(LocalPorts.offer(out, "/connection/host", "A server", closed, 0, 70000)).isEmpty();
    assertThat(out.build().isEmpty()).isTrue();
  }
}
