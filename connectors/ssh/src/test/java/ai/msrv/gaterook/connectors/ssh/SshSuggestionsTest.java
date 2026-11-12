package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.apache.sshd.client.config.hosts.KnownHostHashValue;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Help for the SSH and SSH log files forms from this computer: the hosts of {@code ~/.ssh/config} (offered with the
 * address an alias stands for) and {@code known_hosts}, what an alias sets for user, port, key file and bastion, the
 * key files of {@code ~/.ssh} (never read), the sign-in method while it holds its default, and a fingerprint note that
 * trusts nothing. No server: everything here is local.
 */
class SshSuggestionsTest {

  @TempDir
  Path dir;

  private SshHome home;
  private final Set<Integer> loopback = new HashSet<>();
  private SshConnector ssh;
  private SshLogsConnector logs;

  @BeforeEach
  void start() throws IOException {
    home = new SshHome(dir.toRealPath(), "localuser");
    Files.createDirectories(home.ssh());
    ssh = new SshConnector(home, loopback::contains);
    logs = new SshLogsConnector(home, loopback::contains);
  }

  @AfterEach
  void stop() {
    ssh.close();
    logs.close();
  }

  private void config(String text) throws IOException {
    Files.writeString(home.ssh().resolve("config"), text);
  }

  /** A file where a key would be. Its contents are no key at all: offering it proves nothing read it. */
  private Path key(String name, boolean withPub) throws IOException {
    Path p = home.ssh().resolve(name);
    Files.writeString(p, "not a key: never read for a suggestion");
    if (withPub) {
      Files.writeString(home.ssh().resolve(name + ".pub"), "ssh-ed25519 AAAA… public");
    }
    return p;
  }

  private static ConnectionConfig draft(String type, String json) {
    return new ConnectionConfig("draft-1", "draft", type, Env.DEV, 1, TestConfigs.json(json),
        Set.of("/credentials/password", "/credentials/privateKey", "/credentials/passphrase"),
        JsonNodeFactory.instance.objectNode());
  }

  private Suggestions local(String json) {
    return ssh.suggest(draft(SshConnector.TYPE, json), new TestSecrets(), TestCtx.read(), Suggestions.Scope.LOCAL);
  }

  private static final String PROD_WEB = """
      Host prod-web
        HostName 10.0.0.5
        User deploy
        Port 2222
        IdentityFile ~/.ssh/id_work
        ProxyJump bastion
      Host bastion
        HostName bastion.example.test
        User jump
        IdentityFile ~/.ssh/id_jump
      Host staging
        HostName staging.example.test
      Host *
        IdentityFile ~/.ssh/id_missing
      """;

  @Test
  void aliasesAreOfferedWithTheAddressTheyStandFor() throws IOException {
    config(PROD_WEB);

    Suggestions s = local("{}");

    Suggestions.Field host = s.fields().get("/connection/host");
    assertThat(host.source()).isEqualTo("~/.ssh/config");
    assertThat(host.value()).isNull();
    assertThat(host.options()).containsExactly(
        new Option("10.0.0.5", "prod-web", "deploy@10.0.0.5:2222 via bastion"),
        new Option("bastion.example.test", "bastion", "jump@bastion.example.test"),
        new Option("staging.example.test", "staging", "staging.example.test"));
    // Nothing typed yet: no host is filled in, and nothing else is either.
    assertThat(s.fields().get("/connection/username").value()).isNull();
    assertThat(s.notes()).isEmpty();
  }

  @Test
  void aPickedAliasFillsInWhatSshWouldUseIncludingItsBastion() throws IOException {
    config(PROD_WEB);
    key("id_work", true);
    key("id_jump", true);
    key("id_ed25519", true);

    Suggestions s = local("""
        {"connection":{"host":"10.0.0.5","port":22},"credentials":{"method":"vaultSshCa"}}""");

    assertThat(s.fields().get("/connection/username").value()).isEqualTo("deploy");
    assertThat(s.fields().get("/connection/username").source()).isEqualTo("~/.ssh/config");
    assertThat(s.fields().get("/connection/port").value()).isEqualTo("2222");
    assertThat(s.fields().get("/credentials/privateKeyPath").value()).isEqualTo("~/.ssh/id_work");
    assertThat(s.fields().get("/credentials/privateKeyPath").options()).extracting(Option::value)
        .containsExactly("~/.ssh/id_work", "~/.ssh/id_ed25519", "~/.ssh/id_jump");
    // A key file exists and no Vault store is named: the default method gives way to the key file.
    assertThat(s.fields().get("/credentials/method").value()).isEqualTo("privateKeyFile");
    // ssh -J signs in to the bastion as its own user, with its own key.
    assertThat(s.fields().get("/jump/host").value()).isEqualTo("bastion.example.test");
    assertThat(s.fields().get("/jump/host").options().getFirst().label()).isEqualTo("bastion");
    assertThat(s.fields().get("/jump/username").value()).isEqualTo("jump");
    assertThat(s.fields().get("/jump/credentials/method").value()).isEqualTo("privateKeyFile");
    assertThat(s.fields().get("/jump/credentials/privateKeyPath").value()).isEqualTo("~/.ssh/id_jump");
    assertThat(s.fields()).doesNotContainKey("/jump/port");
    assertThat(s.notes()).contains("From ~/.ssh/config for prod-web: user deploy, port 2222, key ~/.ssh/id_work, "
        + "through bastion.example.test.");
  }

  @Test
  void typingTheAliasItselfSaysToUseItsAddress() throws IOException {
    config(PROD_WEB);

    Suggestions s = local("{\"connection\":{\"host\":\"prod-web\"}}");

    assertThat(s.fields().get("/connection/username").value()).isEqualTo("deploy");
    assertThat(s.notes()).contains("prod-web is an alias in ~/.ssh/config, which GateRook doesn't read when it "
        + "connects: choose prod-web under Host to use its address 10.0.0.5.");
    // The key file ~/.ssh/config names doesn't exist here, and the Host * one neither.
    assertThat(s.notes()).contains("~/.ssh/config names no private key file on this computer for prod-web: choose "
        + "one under Private key file.");
    assertThat(s.fields().get("/credentials/privateKeyPath")).isNull();
  }

  @Test
  void aHostTheConfigDoesntNameGetsWhatPatternsGiveAndNoUserOfThisComputer() throws IOException {
    config("""
        Host *.internal.test
          User ops
          Port 2200
        """);

    Suggestions s = local("{\"connection\":{\"host\":\"db.internal.test\"}}");

    assertThat(s.fields().get("/connection/username").value()).isEqualTo("ops");
    assertThat(s.fields().get("/connection/port").value()).isEqualTo("2200");

    Suggestions plain = local("{\"connection\":{\"host\":\"10.20.30.40\"}}");
    // Not a host this computer's ssh knows: its user is offered, never filled in.
    assertThat(plain.fields().get("/connection/username").value()).isNull();
    assertThat(plain.fields().get("/connection/username").options()).extracting(Option::value)
        .containsExactly("localuser");
  }

  @Test
  void aTypedUserOrPortIsSplitOffTheHost() throws IOException {
    Suggestions s = local("{\"connection\":{\"host\":\"admin@10.0.0.7\"}}");

    assertThat(s.fields().get("/connection/username").value()).isEqualTo("admin");
    assertThat(s.fields().get("/connection/username").source()).isEqualTo("what you typed");
    assertThat(s.notes()).contains("Host takes only the address: admin goes under User, 10.0.0.7 under Host.");

    Suggestions p = local("{\"connection\":{\"host\":\"10.0.0.7:2222\"}}");
    assertThat(p.fields().get("/connection/port").value()).isEqualTo("2222");
    assertThat(p.notes()).contains("Host takes only the address: 10.0.0.7 goes under Host, 2222 under Port.");
  }

  @Test
  void knownHostsOffersPlainHostsNewestFirstAndSkipsHashedLinesAndCodeHosts() throws Exception {
    String key = HostKeys.line(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256).getPublic());
    Files.writeString(home.ssh().resolve("known_hosts"), String.join("\n",
        "old.example.test " + key,
        "github.com,140.82.121.4 " + key,
        hashed("secret.example.test", 22) + " " + key,
        "@revoked revoked.example.test " + key,
        "*.wild.example.test " + key,
        "[10.0.0.9]:2222 " + key) + "\n");

    Suggestions s = local("{}");

    Suggestions.Field host = s.fields().get("/connection/host");
    assertThat(host.source()).isEqualTo("~/.ssh/known_hosts");
    assertThat(host.options()).containsExactly(new Option("10.0.0.9", null, "port 2222"),
        new Option("old.example.test", null, null));
  }

  @Test
  void aFingerprintNoteNamesTheKnownKeyAndTrustsNothing() throws Exception {
    KeyPair pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    String line = HostKeys.line(pair.getPublic());
    Files.writeString(home.ssh().resolve("known_hosts"), hashed("10.0.0.5", 22) + " " + line + "\n");

    Suggestions s = local("{\"connection\":{\"host\":\"10.0.0.5\",\"port\":22}}");

    assertThat(s.notes()).contains("This computer's ~/.ssh/known_hosts has ssh-ed25519 "
        + HostKeys.fingerprint(pair.getPublic()) + " for 10.0.0.5. Test connection shows the key the host presents: "
        + "trust it only if it matches.");
    assertThat(s.fields().keySet()).noneMatch(p -> p.startsWith("/hostKeys"));

    // A connection that trusts a key already needs no note.
    Suggestions pinned = local("{\"connection\":{\"host\":\"10.0.0.5\"},\"hostKeys\":{\"pinned\":[\"" + line
        + "\"]}}");
    assertThat(pinned.notes()).noneMatch(n -> n.contains("known_hosts has"));
  }

  @Test
  void theOnlyDefaultKeyIsFilledInAndKeysAreNeverRead() throws IOException {
    Path ed = key("id_ed25519", true);
    key("deploy_key", true);
    key("id_ed25519_sk", true);
    key("notes.txt", false);
    if (Files.getFileStore(ed).supportsFileAttributeView("posix")) {
      // Unreadable: offering it shows that a suggestion only checks that the file is there.
      Files.setPosixFilePermissions(ed, PosixFilePermissions.fromString("---------"));
    }

    Suggestions s = local("{\"connection\":{\"host\":\"10.0.0.7\"}}");

    Suggestions.Field keys = s.fields().get("/credentials/privateKeyPath");
    assertThat(keys.value()).isEqualTo("~/.ssh/id_ed25519");
    assertThat(keys.source()).isEqualTo("~/.ssh");
    // A FIDO key can't sign here, a file without a .pub next to it isn't a key.
    assertThat(keys.options()).extracting(Option::value).containsExactly("~/.ssh/id_ed25519", "~/.ssh/deploy_key");

    key("id_rsa", true);
    Suggestions two = local("{\"connection\":{\"host\":\"10.0.0.7\"}}");
    // Two default keys: ssh would try both; the person picks one.
    assertThat(two.fields().get("/credentials/privateKeyPath").value()).isNull();
  }

  @Test
  void theMethodStaysWhenSomethingWasSetUpForTheDefault() throws IOException {
    key("id_ed25519", true);

    assertThat(local("{\"credentials\":{\"method\":\"vaultSshCa\",\"vault\":{\"store\":\"team-vault\"}}}").fields())
        .doesNotContainKey("/credentials/method");
    assertThat(local("{\"credentials\":{\"method\":\"password\"}}").fields())
        .doesNotContainKey("/credentials/method");

    // SSH log files: password is the default; a typed password keeps it.
    ConnectionConfig cfg = draft(SshLogsConnector.TYPE, "{\"credentials\":{\"method\":\"password\"}}");
    assertThat(logs.suggest(cfg, new TestSecrets(), TestCtx.read(), Suggestions.Scope.LOCAL).fields()
        .get("/credentials/method").value()).isEqualTo("privateKeyFile");
    assertThat(logs.suggest(cfg, new TestSecrets().with("/credentials/password", "typed"), TestCtx.read(),
        Suggestions.Scope.LOCAL).fields()).doesNotContainKey("/credentials/method");
  }

  @Test
  void anSshServerOnLoopbackIsOfferedNeverFilledIn() throws IOException {
    loopback.add(2222);

    Suggestions s = local("{}");

    assertThat(s.fields().get("/connection/host").options())
        .containsExactly(new Option("localhost", null, "An SSH server answers on port 2222"));
    assertThat(s.fields().get("/connection/host").value()).isNull();
    // Only 2222 answers: a typed localhost takes that port.
    assertThat(local("{\"connection\":{\"host\":\"localhost\",\"port\":22}}").fields().get("/connection/port")
        .value()).isEqualTo("2222");
  }

  @Test
  void bastionsGateRookCantUseAreNotes() throws IOException {
    config("""
        Host two-hops
          HostName 10.0.0.8
          ProxyJump a.example.test,b.example.test
        Host by-command
          HostName 10.0.0.9
          ProxyCommand ssh -W %h:%p gw.example.test
        Host one-hop
          HostName 10.0.0.10
          ProxyJump ops@gw.example.test:2200
        """);

    assertThat(local("{\"connection\":{\"host\":\"10.0.0.8\"}}").notes())
        .anyMatch(n -> n.startsWith("two-hops goes through 2 jump hosts"));
    assertThat(local("{\"connection\":{\"host\":\"10.0.0.9\"}}").notes())
        .anyMatch(n -> n.startsWith("by-command connects through a ProxyCommand"));
    Suggestions one = local("{\"connection\":{\"host\":\"10.0.0.10\"}}");
    assertThat(one.fields().get("/jump/host").value()).isEqualTo("gw.example.test");
    assertThat(one.fields().get("/jump/port").value()).isEqualTo("2200");
    assertThat(one.fields().get("/jump/username").value()).isEqualTo("ops");

    // SSH log files has no bastion: it says so instead.
    Suggestions logsDraft = logs.suggest(draft(SshLogsConnector.TYPE, "{\"connection\":{\"host\":\"10.0.0.10\"}}"),
        new TestSecrets(), TestCtx.read(), Suggestions.Scope.LOCAL);
    assertThat(logsDraft.fields().keySet()).noneMatch(p -> p.startsWith("/jump"));
    assertThat(logsDraft.notes()).anyMatch(n -> n.startsWith("one-hop goes through a jump host"));
  }

  @Test
  void anEmptyHomeOffersOnlyThisComputersUser() {
    Suggestions s = local("{\"connection\":{\"host\":\"10.0.0.7\"}}");

    assertThat(s.fields().keySet()).containsExactly("/connection/username");
    assertThat(s.notes()).isEmpty();
  }

  @Test
  void theServiceStepOfSshSignsInToNothingAndSaysWhatToTrust() {
    Suggestions s = ssh.suggest(draft(SshConnector.TYPE, """
        {"connection":{"host":"10.0.0.5","username":"deploy"},"credentials":{"method":"password"},
         "jump":{"host":"bastion.example.test"}}"""), new TestSecrets().with("/credentials/password", "p"),
        TestCtx.read(), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly(
        "The host key of the bastion bastion.example.test:22 isn't trusted yet: Test connection shows its "
            + "fingerprint to compare before you trust it.",
        "The host key of 10.0.0.5:22 isn't trusted yet: Test connection shows its fingerprint to compare before "
            + "you trust it.");
  }

  @Test
  void theServiceStepOfSshLogsWaitsForATrustedHostKey() {
    TestSecrets secrets = new TestSecrets().with("/credentials/password", "p");

    Suggestions s = logs.suggest(draft(SshLogsConnector.TYPE, """
        {"connection":{"host":"127.0.0.1","port":1,"username":"deploy"},"credentials":{"method":"password"}}"""),
        secrets, TestCtx.read(), Suggestions.Scope.SERVICE);

    assertThat(s.fields()).isEmpty();
    assertThat(s.notes()).containsExactly("The host key of 127.0.0.1:1 isn't trusted yet: Test connection shows "
        + "its fingerprint to compare before you trust it.");
    assertThat(secrets.resolved.get()).isZero();
  }

  /** {@code |1|salt|hash}: a hashed known_hosts host, as {@code HashKnownHosts yes} writes it. */
  private static String hashed(String host, int port) throws IOException {
    byte[] salt = new byte[20];
    new SecureRandom().nextBytes(salt);
    try {
      byte[] digest = KnownHostHashValue.calculateHashValue(host, port, KnownHostHashValue.DEFAULT_DIGEST, salt);
      return KnownHostHashValue.append(new StringBuilder(), KnownHostHashValue.DEFAULT_DIGEST, salt, digest)
          .toString();
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}
