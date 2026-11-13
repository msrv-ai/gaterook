package com.gaterook.connectors.neo4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** What a Neo4j connection's settings allow: TLS except to this computer, never an unverified certificate. */
class Neo4jSettingsTest {

  /** A self-signed test CA (CN=gw-unit-test-ca) whose key was thrown away: for parsing only. */
  static final String CA = """
      -----BEGIN CERTIFICATE-----
      MIIBaDCCAQ6gAwIBAgIUeZvj8W4FJRE0wePdVvilnO86uiAwCgYIKoZIzj0EAwIw
      GjEYMBYGA1UEAwwPZ3ctdW5pdC10ZXN0LWNhMB4XDTI2MTExMTE0MDMyMVoXDTM2
      MTEwODE0MDMyMVowGjEYMBYGA1UEAwwPZ3ctdW5pdC10ZXN0LWNhMFkwEwYHKoZI
      zj0CAQYIKoZIzj0DAQcDQgAEO1FMYQHRjZWqkqnt8f0zc8NgVkpR6ye4GcE/d8wl
      qqNYgwPJ0m5juCEJxqz7b7XXgejRIsF7vMSXo+0SC6GvPKMyMDAwHQYDVR0OBBYE
      FPCvOnWtDV3mY8/8T/hln+VnZblHMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0E
      AwIDSAAwRQIhAMFOOHIuvTG6LmeXR/mOj1vUuMxpOh9YWFDt393Z4RyDAiBzBEmd
      qpyA/Xh4gZvvsQ1BRMlmgoP8tiEe9vYcv98TEw==
      -----END CERTIFICATE-----
      """;

  static ConnectionConfig config(String uri, ObjectNode extra) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("uri", uri);
    c.putObject("authentication").put("method", "password").put("user", "gw_reader");
    if (extra != null) {
      c.setAll(extra);
    }
    return new ConnectionConfig("conn-neo4j", "dev-graph", "neo4j", Env.DEV, 1, c, Set.of(Neo4jSettings.PASSWORD),
        null);
  }

  static Neo4jSettings settings(String uri) throws ConnectorException {
    return Neo4jSettings.of(config(uri, null));
  }

  @Test
  void schemesHostsAndPorts() throws Exception {
    Neo4jSettings aura = settings("neo4j+s://a1b2c3d4.databases.neo4j.io");
    assertThat(aura.routing()).isTrue();
    assertThat(aura.tls()).isTrue();
    assertThat(aura.port()).isEqualTo(7687);
    assertThat(aura.database()).isEqualTo("neo4j");
    assertThat(aura.scheme()).isEqualTo("neo4j+s");
    Neo4jSettings one = settings("bolt+s://graph.example.com:7688/");
    assertThat(one.routing()).isFalse();
    assertThat(one.address()).isEqualTo("graph.example.com:7688");
    assertThat(settings("neo4j://localhost").tls()).isFalse();
    assertThat(settings("bolt://127.0.0.1:55687").port()).isEqualTo(55687);
    assertThat(settings("bolt://[::1]:7687").host()).isEqualTo("[::1]");
    assertThat(settings("BOLT+S://graph.example.com").scheme()).isEqualTo("bolt+s");
  }

  @Test
  void tlsIsRequiredExceptToThisComputerAndNeverUnverified() {
    for (String uri : new String[] {"bolt://graph.example.com", "neo4j://10.0.0.5:7687", "bolt://127.0.0.1.nip.io"}) {
      assertThatThrownBy(() -> settings(uri)).as(uri).isInstanceOfSatisfying(ConnectorException.class,
          e -> assertThat(e.getMessage()).contains("Without TLS GateRook connects only to this computer"));
    }
    assertThatThrownBy(() -> settings("neo4j+ssc://graph.example.com")).isInstanceOfSatisfying(
        ConnectorException.class, e -> assertThat(e.getMessage()).contains("accepts any certificate")
            .contains("neo4j+s://"));
    assertThatThrownBy(() -> settings("bolt+ssc://localhost")).hasMessageContaining("bolt+s://");
    for (String uri : new String[] {"http://graph.example.com", "neo4j+s://gw_reader@graph.example.com",
        "neo4j+s://graph.example.com/neo4j", "neo4j+s://graph.example.com?policy=eu", "neo4j+s://", "graph.example.com",
        "neo4j+s://graph.example.com:0", "neo4j+s://graph_example.com", "neo4j+s://graph.example.com#x"}) {
      assertThatThrownBy(() -> settings(uri)).as(uri).isInstanceOf(ConnectorException.class);
    }
    assertThat(Neo4jSettings.loopback("localhost")).isTrue();
    assertThat(Neo4jSettings.loopback("127.255.0.1")).isTrue();
    assertThat(Neo4jSettings.loopback("[::1]")).isTrue();
    assertThat(Neo4jSettings.loopback("127.0.0.256")).isFalse();
    assertThat(Neo4jSettings.loopback("localhost.example.com")).isFalse();
  }

  @Test
  void theDatabaseIsOneOfTheGraphNeverSystem() throws Exception {
    ObjectNode db = JsonNodeFactory.instance.objectNode().put("database", "movies-2026");
    assertThat(Neo4jSettings.of(config("neo4j+s://graph.example.com", db)).database()).isEqualTo("movies-2026");
    for (String name : new String[] {"system", "SYSTEM", "ab", "1graph", "graph db", "a_b"}) {
      ObjectNode bad = JsonNodeFactory.instance.objectNode().put("database", name);
      assertThatThrownBy(() -> Neo4jSettings.of(config("neo4j+s://graph.example.com", bad))).as(name)
          .isInstanceOf(ConnectorException.class);
    }
  }

  @Test
  void signInAndLimits() throws Exception {
    ObjectNode token = JsonNodeFactory.instance.objectNode();
    token.putObject("authentication").put("method", "token");
    Neo4jSettings t = Neo4jSettings.of(config("neo4j+s://graph.example.com", token));
    assertThat(t.method()).isEqualTo(Neo4jSettings.Method.TOKEN);
    assertThat(t.user()).isNull();
    assertThat(t.secretField()).isEqualTo("/authentication/token");
    ObjectNode noUser = JsonNodeFactory.instance.objectNode();
    noUser.putObject("authentication").put("method", "password");
    assertThatThrownBy(() -> Neo4jSettings.of(config("neo4j+s://graph.example.com", noUser)))
        .hasMessageContaining("user");
    ObjectNode advanced = JsonNodeFactory.instance.objectNode();
    advanced.putObject("advanced").put("maxRows", 50).put("transactionTimeoutMs", 2_500).put("connectTimeoutMs", 99);
    Neo4jSettings a = Neo4jSettings.of(config("neo4j+s://graph.example.com", advanced));
    assertThat(a.maxRows()).isEqualTo(50);
    assertThat(a.transactionTimeout()).isEqualTo(Duration.ofMillis(2_500));
    assertThat(a.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
    assertThat(settings("neo4j+s://graph.example.com").maxRows()).isNull();
  }

  @Test
  void aPrivateCaNeedsTlsAndAValidCertificate() throws Exception {
    ObjectNode bad = JsonNodeFactory.instance.objectNode().put("caCertificate", "-----BEGIN PRIVATE KEY-----\n"
        + "c2VjcmV0\n-----END PRIVATE KEY-----");
    assertThatThrownBy(() -> Neo4jSettings.of(config("neo4j+s://graph.example.com", bad)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("CA certificate can't be used").doesNotContain("c2VjcmV0"));
    ObjectNode plain = JsonNodeFactory.instance.objectNode().put("caCertificate", "x");
    assertThatThrownBy(() -> Neo4jSettings.of(config("bolt://localhost", plain))).hasMessageContaining("needs TLS");
    ObjectNode blank = JsonNodeFactory.instance.objectNode().put("caCertificate", "  ");
    assertThat(Neo4jSettings.of(config("neo4j+s://graph.example.com", blank)).caPem()).isNull();
    ObjectNode ca = JsonNodeFactory.instance.objectNode().put("caCertificate", "Bag Attributes: x\n" + CA);
    Neo4jSettings s = Neo4jSettings.of(config("bolt+s://graph.example.com", ca));
    assertThat(s.caCertificates()).hasSize(1);
    assertThat(s.caCertificates().getFirst().getSubjectX500Principal().getName()).isEqualTo("CN=gw-unit-test-ca");
    assertThat(s.caPem()).startsWith("-----BEGIN CERTIFICATE-----\n").doesNotContain("Bag Attributes");
  }
}
