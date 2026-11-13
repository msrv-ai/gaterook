package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The URL a person types: https anywhere, plain http only to this computer, never a metadata address. */
class ServiceHttpTest {

  @Test
  void aServerUrlKeepsItsPathPrefixAndLosesTrailingSlashes() throws Exception {
    assertThat(ServiceHttp.base("https://qdrant.example.com:6333/", "x")).isEqualTo(
        URI.create("https://qdrant.example.com:6333"));
    assertThat(ServiceHttp.base("https://proxy.example.com/qdrant//", "x")).isEqualTo(
        URI.create("https://proxy.example.com/qdrant"));
    assertThat(ServiceHttp.base("http://localhost:6333", "x")).isEqualTo(URI.create("http://localhost:6333"));
    assertThat(ServiceHttp.base("http://127.0.0.1:7700", "x")).isEqualTo(URI.create("http://127.0.0.1:7700"));
    assertThat(ServiceHttp.base("http://[::1]:8108", "x")).isEqualTo(URI.create("http://[::1]:8108"));
    ServiceHttp.Endpoint e = new ServiceHttp.Endpoint(URI.create("https://proxy.example.com/qdrant"), List.of());
    assertThat(e.uri("/collections/" + ServiceHttp.seg("a b/c"), "wait", "true", "skip", null).toString())
        .isEqualTo("https://proxy.example.com/qdrant/collections/a%20b%2Fc?wait=true");
    assertThat(e.target()).isEqualTo("proxy.example.com/qdrant");
  }

  @Test
  void whatCouldReachAnythingElseIsRefused() {
    for (String bad : new String[] {"http://qdrant.internal:6333", "http://10.0.0.5:6333", "https://169.254.169.254",
        "https://user:pw@qdrant.example.com", "https://qdrant.example.com?x=1", "https://qdrant.example.com/#a",
        "ftp://qdrant.example.com", "", "https://metadata.google.internal"}) {
      assertThatThrownBy(() -> ServiceHttp.base(bad, "https://x")).as(bad).isInstanceOf(ConnectorException.class);
    }
    assertThatThrownBy(() -> ServiceHttp.base("https://chroma.example.com/api/v2", "x", "/api/v2"))
        .hasMessageContaining("Leave /api/v2 out");
  }

  @Test
  void aSegmentIsEncodedWhole() {
    assertThat(ServiceHttp.seg("Movies_2024-v1.~")).isEqualTo("Movies_2024-v1.~");
    assertThat(ServiceHttp.seg("../x?y#z%")).isEqualTo("..%2Fx%3Fy%23z%25");
    assertThat(ServiceHttp.seg("é")).isEqualTo("%C3%A9");
  }
}
