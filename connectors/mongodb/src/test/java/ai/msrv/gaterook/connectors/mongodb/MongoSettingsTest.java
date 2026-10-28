package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import ai.msrv.gaterook.connector.api.Env;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The connection string checks of report 05 §4 and the prod defaults the connector applies itself. */
class MongoSettingsTest {

  @Test
  void hostAndPortBuildTheDriverAddressWithoutCredentials() {
    MongoSettings s = MongoSettings.of(Fixtures.config(Env.DEV, """
        {"connection":{"mode":"host","host":"mongo.dev.internal","port":27018,"tls":true,
          "databases":["orders"]},"credentials":{"user":"reader","password":"private",
          "authSource":"accounts","mechanism":"SCRAM-SHA-256"}}
        """));

    assertThat(s.usable()).isTrue();
    assertThat(s.uri()).isEqualTo("mongodb://mongo.dev.internal:27018/");
    assertThat(new ConnectionString(s.uri()).getUsername()).isNull();
    assertThat(s.parsed().hosts()).containsExactly("mongo.dev.internal:27018");
    assertThat(s.parsed().options()).isEmpty();
    assertThat(s.tls()).isTrue();
    assertThat(s.user()).isEqualTo("reader");
    assertThat(s.authSource()).isEqualTo("accounts");
    assertThat(s.mechanism()).isEqualTo("SCRAM-SHA-256");
    assertThat(s.defaultDatabase()).isEqualTo("orders");
  }

  @Test
  void hostModeUsesDefaultPortAndEnvironmentTlsUnlessExplicitlySet() {
    MongoSettings dev = MongoSettings.of(Fixtures.config(Env.DEV, """
        {"connection":{"host":"localhost"}}
        """));
    MongoSettings prod = MongoSettings.of(Fixtures.config(Env.PROD, """
        {"connection":{"host":"localhost"}}
        """));
    MongoSettings explicit = MongoSettings.of(Fixtures.config(Env.PROD, """
        {"connection":{"host":"localhost","tls":false}}
        """));

    assertThat(dev.usable()).isTrue();
    assertThat(dev.uri()).isEqualTo("mongodb://localhost:27017/");
    assertThat(dev.tls()).isFalse();
    assertThat(prod.usable()).isTrue();
    assertThat(prod.tls()).isTrue();
    assertThat(explicit.usable()).isTrue();
    assertThat(explicit.tls()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"::1", "[::1]", "2001:db8::4", "::ffff:127.0.0.1"})
  void ipv6HostsAreBracketedForTheDriver(String host) {
    MongoSettings s = MongoSettings.of(Fixtures.config(Env.DEV,
        "{\"connection\":{\"host\":\"" + host + "\",\"port\":27018}}"));
    String literal = host.replace("[", "").replace("]", "");

    assertThat(s.usable()).isTrue();
    assertThat(s.uri()).isEqualTo("mongodb://[" + literal + "]:27018/");
    assertThat(new ConnectionString(s.uri()).getHosts()).containsExactly("[" + literal + "]:27018");
    assertThat(s.parsed().firstHost()).isEqualTo(literal);
    assertThat(s.parsed().firstPort()).isEqualTo(27018);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "mongodb://mongo.example", "user:password@mongo.example", "mongo.example:27017",
      "mongo.example/orders", "mongo.example?tls=false", "mongo.example,other.example", "mongo.example#fragment",
      "mongo.example%2Forders", "mongo example", "mongo.example\nother", "[::1]:27017", "[[::1]]", "::xyz",
      "-mongo.example", "mongo..example", "mongo.example;tls=false", "mongo.example&tls=false", "mongo\\example"})
  void hostCannotInjectUriComponents(String host) {
    var config = Fixtures.json("{\"connection\":{}}");
    ((tools.jackson.databind.node.ObjectNode) config.get("connection")).put("host", host);
    MongoSettings s = MongoSettings.of(Fixtures.config(Env.DEV, config.toString()));

    assertThat(s.usable()).isFalse();
    assertThat(s.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/host");
    assertThat(s.parsed()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "65536", "27017.5", "\"27017\"", "null", "true", "2147483648",
      "4294994313", "9999999999999999999999999999999"})
  void invalidPortsNameThePortField(String portJson) {
    MongoSettings s = MongoSettings.of(Fixtures.config(Env.DEV,
        "{\"connection\":{\"host\":\"localhost\",\"port\":" + portJson + "}}"));

    assertThat(s.usable()).isFalse();
    assertThat(s.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/port");
  }

  @Test
  void uriModeAndLegacyConfigsPreserveTheFullAddressAndOptions() {
    String uri = "mongodb://h1:27018,h2:27019/orders?replicaSet=rs0&appName=reader";
    MongoSettings legacy = MongoSettings.of(Fixtures.uri(Env.DEV, uri));
    MongoSettings explicit = MongoSettings.of(Fixtures.config(Env.DEV,
        "{\"connection\":{\"mode\":\"uri\",\"uri\":\"" + uri + "\",\"port\":27017}}"));

    assertThat(legacy.usable()).isTrue();
    assertThat(explicit.usable()).isTrue();
    assertThat(explicit.uri()).isEqualTo(uri).isEqualTo(legacy.uri());
    assertThat(explicit.parsed().hosts()).containsExactly("h1:27018", "h2:27019");
    assertThat(explicit.parsed().option("replicaSet")).isEqualTo("rs0");
    assertThat(explicit.defaultDatabase()).isEqualTo("orders");
  }

  @Test
  void selectedModeCannotSilentlyDiscardAnotherAddress() {
    MongoSettings uri = MongoSettings.of(Fixtures.config(Env.DEV, """
        {"connection":{"mode":"uri","uri":"mongodb://h/orders","host":"other"}}
        """));
    MongoSettings host = MongoSettings.of(Fixtures.config(Env.DEV, """
        {"connection":{"mode":"host","uri":"mongodb://h/orders","host":"other"}}
        """));
    MongoSettings missing = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{}}"));
    MongoSettings unknown = MongoSettings.of(Fixtures.config(Env.DEV, """
        {"connection":{"mode":"other","host":"localhost"}}
        """));

    assertThat(uri.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/host");
    assertThat(host.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/uri");
    assertThat(missing.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/host");
    assertThat(unknown.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/mode");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "mongodb://gw:secret@mongo.dev.internal:27017/orders",
      "mongodb://gw@mongo.dev.internal/orders",
      "mongodb://a%40b:c%3Ad@h1,h2/orders?replicaSet=rs0",
      "mongodb://u:p@h/db?authSource=admin",
      "mongodb+srv://u:p@cluster0.abcde.mongodb.net/orders",
      "mongodb://h1,u:p@h2/orders",
  })
  void userInformationIsRefusedWhereTheDriverWouldUseIt(String uri) {
    MongoSettings s = MongoSettings.of(Fixtures.uri(Env.DEV, uri));

    assertThat(s.usable()).isFalse();
    assertThat(s.problems()).extracting(MongoSettings.Problem::text)
        .anyMatch(t -> t.startsWith("Remove the user name and password from the connection string"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "mongodb://mongo.dev.internal:27017/orders",
      "mongodb://h1:27017,h2:27018/orders?replicaSet=rs0&readPreference=primary",
      "mongodb://[::1]:27017/orders",
      "mongodb://h/orders?appName=x;maxPoolSize=50",
      "mongodb://h/orders.coll",
  })
  void whatTheDriverReadsAsUserInformationIsWhatTheGatewayChecks(String uri) {
    // The same split as ConnectionString: no user information for the driver means none here, and the hosts and
    // the database agree.
    ConnectionString driver = new ConnectionString(uri);
    MongoUri ours = MongoUri.parse(uri);

    assertThat(ours.userInfo()).isEqualTo(driver.getUsername() != null);
    assertThat(ours.hosts()).isEqualTo(driver.getHosts());
    assertThat(ours.database()).isEqualTo(driver.getDatabase());
  }

  @Test
  void optionsThatBelongToTheFormOrWeakenTheGatewayAreRefused() {
    for (String option : List.of("tlsInsecure=true", "tlsAllowInvalidCertificates=true",
        "tlsAllowInvalidHostnames=true", "authMechanism=MONGODB-AWS", "authMechanismProperties=ENVIRONMENT:gcp",
        "proxyUsername=a", "proxyPassword=b", "timeoutMS=0", "tlsCAFile=/etc/ca.pem", "TLSINSECURE=true")) {
      MongoSettings s = MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb://h/orders?" + option));
      assertThat(s.usable()).as(option).isFalse();
      assertThat(s.problemText()).as(option).startsWith("The connection string can't set ");
    }
    assertThat(MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb://h/orders?replicaSet=rs0&maxPoolSize=50"))
        .usable()).isTrue();
  }

  @Test
  void theStringAndTheFormMustAgree() {
    MongoSettings auth = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":"
        + "\"mongodb://h/orders?authSource=orders\"},\"credentials\":{\"user\":\"gw\",\"authSource\":\"admin\"}}"));
    MongoSettings read = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":"
        + "\"mongodb://h/orders?readPreference=secondary\",\"readPreference\":\"primary\"}}"));
    MongoSettings tls = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":"
        + "\"mongodb://h/orders?tls=false\",\"tls\":true}}"));
    MongoSettings same = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":"
        + "\"mongodb://h/orders?authSource=orders&readPreference=secondary\",\"readPreference\":\"secondary\"},"
        + "\"credentials\":{\"user\":\"gw\",\"authSource\":\"orders\"}}"));

    assertThat(auth.problems()).extracting(MongoSettings.Problem::field).containsExactly("/credentials/authSource");
    assertThat(read.problems()).extracting(MongoSettings.Problem::field)
        .containsExactly("/connection/readPreference");
    assertThat(tls.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/tls");
    assertThat(same.usable()).isTrue();
  }

  @Test
  void prodDefaultsToTlsAndSecondaryReadsAndRefusesTheHostNameSkip() {
    MongoSettings prod = MongoSettings.of(Fixtures.uri(Env.PROD, "mongodb://h/orders"));
    MongoSettings dev = MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb://h/orders"));
    MongoSettings skip = MongoSettings.of(Fixtures.config(Env.PROD, "{\"connection\":{\"uri\":\"mongodb://h/o\","
        + "\"allowInvalidHostnames\":true}}"));
    MongoSettings explicit = MongoSettings.of(Fixtures.config(Env.PROD, "{\"connection\":{\"uri\":\"mongodb://h/o\","
        + "\"tls\":false,\"readPreference\":\"primary\"}}"));

    assertThat(prod.tls()).isTrue();
    assertThat(prod.readPreference()).isEqualTo("secondaryPreferred");
    assertThat(dev.tls()).isFalse();
    assertThat(dev.readPreference()).isEqualTo("primary");
    assertThat(skip.problems()).extracting(MongoSettings.Problem::field)
        .containsExactly("/connection/allowInvalidHostnames");
    assertThat(explicit.tls()).isFalse();
    assertThat(explicit.readPreference()).isEqualTo("primary");
  }

  @Test
  void srvTurnsTlsOnUnlessTheStringTurnsItOff() {
    assertThat(MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb+srv://cluster0.example.net/orders")).tls()).isTrue();
    assertThat(MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb+srv://cluster0.example.net/orders?tls=false"))
        .tls()).isFalse();
  }

  @Test
  void otherFormProblemsNameTheirField() {
    MongoSettings ca = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://h/o\","
        + "\"caCertificate\":\"not a certificate\"}}"));
    MongoSettings list = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://h/o\"},"
        + "\"advanced\":{\"namespaceAllowlist\":[\"orders\"]}}"));
    MongoSettings none = MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://h/o\"},"
        + "\"credentials\":{\"user\":\"gw\",\"mechanism\":\"none\"}}"));
    MongoSettings scheme = MongoSettings.of(Fixtures.uri(Env.DEV, "postgres://h/o"));
    // A '/' in an unescaped password ends the hosts for the driver too: a host "user:pa" with a port that isn't one.
    MongoSettings slash = MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb://user:pa/ss@h/orders"));

    assertThat(ca.problems()).extracting(MongoSettings.Problem::field).containsExactly("/connection/caCertificate");
    assertThat(list.problems()).extracting(MongoSettings.Problem::field)
        .containsExactly("/advanced/namespaceAllowlist");
    assertThat(none.problems()).extracting(MongoSettings.Problem::field).containsExactly("/credentials/mechanism");
    assertThat(scheme.problemText()).isEqualTo("The connection string must start with mongodb:// or "
        + "mongodb+srv://.");
    assertThat(slash.problemText()).isEqualTo("The port of user:pa isn't a number.");
  }

  @Test
  void theTargetNeverShowsOptionsOrUserInformation() {
    MongoSettings s = MongoSettings.of(Fixtures.uri(Env.DEV,
        "mongodb://mongo.dev.internal:27017/orders?replicaSet=rs0&appName=x"));

    assertThat(s.target(s.defaultDatabase())).isEqualTo("mongo.dev.internal:27017/orders · primary");
    assertThat(s.parsed().firstHost()).isEqualTo("mongo.dev.internal");
    assertThat(s.parsed().firstPort()).isEqualTo(27017);
    assertThat(MongoUri.parse("mongodb://[::1]:27018/o").firstHost()).isEqualTo("::1");
    assertThat(MongoUri.parse("mongodb://[::1]:27018/o").firstPort()).isEqualTo(27018);
    assertThatThrownBy(() -> MongoUri.parse("mongodb+srv://a.example.net:27017/o"))
        .hasMessageContaining("without a port");
  }
}
