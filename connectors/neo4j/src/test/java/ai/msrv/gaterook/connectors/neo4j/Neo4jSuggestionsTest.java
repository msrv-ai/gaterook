package ai.msrv.gaterook.connectors.neo4j;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.net.ssl.SSLContext;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Neo4j form: the URI scheme a server needs (another computer: TLS; this computer: what its port
 * speaks), never a host filled in, and, signed in behind the Bolt seam, the databases with the home one filled in.
 */
class Neo4jSuggestionsTest {

  private final List<String> probed = new ArrayList<>();

  private static ConnectionConfig cfg(String uri) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    if (uri != null) {
      c.put("uri", uri);
    }
    c.putObject("authentication").put("method", "password").put("user", "gw_reader");
    return new ConnectionConfig("draft-1", "draft", "neo4j", Env.DEV, 0, c, Set.of(Neo4jSettings.PASSWORD), null);
  }

  private BiFunction<String, Integer, LoopbackBolt.Transport> answering(LoopbackBolt.Transport t) {
    return (host, port) -> {
      probed.add(host + ":" + port);
      return t;
    };
  }

  private Suggestions local(String uri, boolean listening, LoopbackBolt.Transport t) {
    return Neo4jSuggestions.suggest(cfg(uri), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL,
        new FakeBolt(), port -> listening && port == 7687, answering(t));
  }

  @Test
  void anotherComputerGetsNeo4jPlusSWithoutBeingTried() {
    Suggestions s = local("graph.example.com:7687", true, LoopbackBolt.Transport.PLAIN);

    Suggestions.Field uri = s.fields().get("/uri");
    assertThat(uri.value()).isEqualTo("neo4j+s://graph.example.com:7687");
    assertThat(uri.options()).extracting(Option::value).containsExactly("neo4j+s://graph.example.com:7687",
        "bolt+s://graph.example.com:7687");
    assertThat(s.notes()).containsExactly("GateRook reaches graph.example.com only with TLS: neo4j+s:// is added when "
        + "you leave the field.");
    assertThat(probed).as("no network but loopback").isEmpty();
  }

  @Test
  void thisComputerGetsTheSchemeItsPortSpeaks() {
    assertThat(local("localhost", true, LoopbackBolt.Transport.PLAIN).fields().get("/uri").value())
        .isEqualTo("neo4j://localhost");
    assertThat(probed).containsExactly("localhost:7687");
    Suggestions tls = local("127.0.0.1:7688", true, LoopbackBolt.Transport.TLS);
    assertThat(tls.fields().get("/uri").value()).isEqualTo("neo4j+s://127.0.0.1:7688");
    assertThat(tls.notes()).containsExactly("127.0.0.1:7688 speaks TLS: neo4j+s:// is added when you leave the field.");
    Suggestions untrusted = local("localhost:7687", true, LoopbackBolt.Transport.UNTRUSTED);
    assertThat(untrusted.fields().get("/uri").value()).isEqualTo("neo4j+s://localhost:7687");
    assertThat(untrusted.notes().getFirst()).contains("isn't trusted").contains("CA certificate");
    Suggestions none = local("localhost:7699", true, LoopbackBolt.Transport.NONE);
    assertThat(none.fields()).isEmpty();
    assertThat(none.notes()).containsExactly("Nothing answers as Neo4j on localhost:7699. Check the port, or type the "
        + "URI with neo4j:// or neo4j+s://.");
  }

  @Test
  void anEmptyUriIsOfferedTheLocalServerNeverFilledIn() {
    Suggestions s = local(null, true, LoopbackBolt.Transport.PLAIN);

    Suggestions.Field uri = s.fields().get("/uri");
    assertThat(uri.value()).isNull();
    assertThat(uri.source()).isEqualTo("this computer");
    assertThat(uri.options()).extracting(Option::value).containsExactly("neo4j://localhost:7687",
        "bolt://localhost:7687");
    assertThat(uri.options().getFirst().detail()).isEqualTo("A Neo4j server answers on port 7687 without TLS");
    assertThat(local(null, false, LoopbackBolt.Transport.PLAIN).isEmpty()).isTrue();
  }

  @Test
  void aSchemeThatDoesntFitTheServerGetsANote() {
    Suggestions remote = local("bolt://graph.example.com", true, LoopbackBolt.Transport.PLAIN);
    assertThat(remote.fields().get("/uri").options()).extracting(Option::value)
        .containsExactly("bolt+s://graph.example.com");
    assertThat(remote.notes()).containsExactly("Without TLS GateRook connects only to this computer: use "
        + "bolt+s://graph.example.com.");
    assertThat(local("neo4j://localhost:7687", true, LoopbackBolt.Transport.TLS).notes())
        .containsExactly("localhost:7687 speaks TLS: use neo4j+s://localhost:7687.");
    assertThat(local("neo4j+s://localhost", true, LoopbackBolt.Transport.PLAIN).notes())
        .containsExactly("localhost:7687 doesn't speak TLS: use neo4j://localhost.");
    assertThat(local("neo4j+s://graph.example.com", true, LoopbackBolt.Transport.PLAIN).isEmpty()).isTrue();
  }

  @Test
  void theLoopbackProbeTellsTlsFromBoltAndSendsNothingThatSignsIn() throws Exception {
    try (FakePort bolt = new FakePort(false); FakePort tls = new FakePort(true)) {
      assertThat(LoopbackBolt.probe("127.0.0.1", bolt.port, Duration.ofSeconds(2)))
          .isEqualTo(LoopbackBolt.Transport.PLAIN);
      // The hello, then Bolt's magic and versions on a connection of its own: nothing else.
      assertThat(bolt.firstBytes).hasSize(2);
      assertThat(bolt.firstBytes.get(1)).startsWith("6060b017");
      // A certificate this computer doesn't trust still says the port speaks TLS.
      assertThat(LoopbackBolt.probe("localhost", tls.port, Duration.ofSeconds(2)))
          .isEqualTo(LoopbackBolt.Transport.UNTRUSTED);
      assertThat(LoopbackBolt.probe("graph.example.com", 7687, Duration.ofSeconds(2)))
          .as("never another computer").isEqualTo(LoopbackBolt.Transport.NONE);
    }
  }

  @Test
  void signedInItListsTheDatabasesAndFillsInTheHomeOne() {
    FakeBolt server = new FakeBolt().on("SHOW DATABASES", FakeBolt.Answer.rows(List.of("name", "currentStatus",
        "access", "home", "default"), List.of(
            row("neo4j", "online", "read-write", false, true),
            row("movies", "online", "read-only", true, false),
            row("movies", "online", "read-only", true, false),
            row("system", "online", "read-write", false, false))));

    Suggestions s = Neo4jSuggestions.suggest(cfg("neo4j+s://graph.example.com"), new TestSecrets()
        .with(Neo4jSettings.PASSWORD, "graph-pw-1"), TestCtx.of(), Suggestions.Scope.SERVICE, server, port -> false,
        answering(LoopbackBolt.Transport.NONE));

    Suggestions.Field db = s.fields().get("/database");
    assertThat(db.value()).isEqualTo("movies");
    assertThat(db.options()).extracting(Option::value).containsExactly("neo4j", "movies");
    assertThat(db.options().get(1).detail()).isEqualTo("online · read-only · your home database");
    assertThat(s.notes()).containsExactly("Signed in as gw_reader to Neo4j 5.26.31: 2 databases. Choose the one every "
        + "call runs in.");
    assertThat(server.transactions).singleElement().satisfies(tx -> {
      assertThat(tx.mode).isEqualTo(AccessMode.READ);
      assertThat(tx.database).isEqualTo("system");
    });
  }

  @Test
  void boltToAClusterMemberSaysNeo4jRoutes() {
    Value servers = Values.value(List.of(Map.of("addresses", List.of("core1:7687"), "role", "WRITE"),
        Map.of("addresses", List.of("core2:7687", "core3:7687"), "role", "READ")));
    FakeBolt server = new FakeBolt()
        .on("SHOW DATABASES", FakeBolt.Answer.rows(List.of("name", "default"), List.of(List.of(Values.value("neo4j"),
            Values.value(true)))))
        .on("CALL dbms.routing.getRoutingTable", FakeBolt.Answer.rows(List.of("servers"), List.of(List.of(servers))));

    Suggestions s = Neo4jSuggestions.suggest(cfg("bolt+s://core1.example.com"), new TestSecrets()
        .with(Neo4jSettings.PASSWORD, "graph-pw-1"), TestCtx.of(), Suggestions.Scope.SERVICE, server, port -> false,
        answering(LoopbackBolt.Transport.NONE));

    assertThat(s.fields().get("/database").value()).isEqualTo("neo4j");
    assertThat(s.fields().get("/uri").options()).extracting(Option::value)
        .containsExactly("neo4j+s://core1.example.com:7687");
    assertThat(s.notes()).contains("core1.example.com:7687 is one of 3 servers of a cluster: "
        + "neo4j+s://core1.example.com:7687 lets the cluster route each call, reads to its secondaries.");
  }

  @Test
  void settingsTheConnectorRefusesAreANoteBeforeAnySignIn() {
    FakeBolt server = new FakeBolt();
    Suggestions s = Neo4jSuggestions.suggest(cfg("neo4j://graph.example.com"), new TestSecrets()
        .with(Neo4jSettings.PASSWORD, "graph-pw-1"), TestCtx.of(), Suggestions.Scope.SERVICE, server, port -> false,
        answering(LoopbackBolt.Transport.NONE));

    assertThat(s.notes()).contains("Without TLS GateRook connects only to this computer (localhost): use "
        + "neo4j+s:// for graph.example.com.");
    assertThat(server.fresh).isZero();
  }

  private static List<Value> row(String name, String status, String access, boolean home, boolean isDefault) {
    return List.of(Values.value(name), Values.value(status), Values.value(access), Values.value(home),
        Values.value(isDefault));
  }

  /** A Bolt port of this computer that closes a TLS hello, or a TLS port with a certificate nobody trusts. */
  private static final class FakePort implements AutoCloseable {
    final int port;
    final List<String> firstBytes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ServerSocket server;

    FakePort(boolean tls) throws IOException {
      if (tls) {
        HeldCertificate cert = new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();
        SSLContext context = new HandshakeCertificates.Builder().heldCertificate(cert).build().sslContext();
        server = context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
      } else {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      }
      port = server.getLocalPort();
      Thread.ofVirtual().start(() -> {
        while (!server.isClosed()) {
          try (Socket s = server.accept()) {
            InputStream in = s.getInputStream();
            byte[] head = in.readNBytes(4);
            firstBytes.add(java.util.HexFormat.of().formatHex(head));
            if (!tls && head.length == 4 && (head[0] & 0xFF) == 0x60) {
              in.readNBytes(16);
              s.getOutputStream().write(new byte[] {0, 0, 8, 5});
              s.getOutputStream().flush();
            }
          } catch (IOException e) {
            // Next connection.
          }
        }
      });
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }
}
