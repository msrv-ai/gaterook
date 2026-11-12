package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ~/.ssh/config} as OpenSSH reads it for one name: first value wins, IdentityFiles add up, Include follows globs
 * relative to {@code ~/.ssh}, a Match that would run something applies to nothing, and the reading is bounded.
 */
class OpenSshConfigTest {

  @TempDir
  Path dir;

  private SshHome home;

  @BeforeEach
  void home() throws IOException {
    home = new SshHome(dir.toRealPath(), "localuser");
    Files.createDirectories(home.ssh());
  }

  private void config(String text) throws IOException {
    Files.writeString(home.ssh().resolve("config"), text);
  }

  @Test
  void theFirstValueWinsAndIdentityFilesAddUp() throws IOException {
    config("""
        # comment
        Host prod-web web
          HostName 10.0.0.5
          User deploy
          Port=2222
          IdentityFile ~/.ssh/id_work
        Host *
          User nobody
          Port 22
          IdentityFile "%d/.ssh/id_%u"
        """);
    OpenSshConfig c = OpenSshConfig.read(home);

    OpenSshConfig.Resolved r = c.resolve("prod-web");
    assertThat(r.host()).isEqualTo("10.0.0.5");
    assertThat(r.user()).isEqualTo("deploy");
    assertThat(r.port()).isEqualTo(2222);
    assertThat(r.identityFiles()).containsExactly(home.ssh().resolve("id_work").toString(),
        home.ssh().resolve("id_localuser").toString());
    assertThat(c.aliases()).containsExactly("prod-web", "web");
    // Anything else only gets what Host * says.
    OpenSshConfig.Resolved other = c.resolve("db.example.test");
    assertThat(other.host()).isEqualTo("db.example.test");
    assertThat(other.user()).isEqualTo("nobody");
  }

  @Test
  void hostNameTokensAndNegatedPatterns() throws IOException {
    config("""
        Host *.corp !legacy.corp
          HostName %h.example.test
          User corp
        Host legacy.corp
          HostName 10.9.9.9
        """);
    OpenSshConfig c = OpenSshConfig.read(home);

    assertThat(c.resolve("app.corp").host()).isEqualTo("app.corp.example.test");
    assertThat(c.resolve("app.corp").user()).isEqualTo("corp");
    assertThat(c.resolve("legacy.corp").host()).isEqualTo("10.9.9.9");
    assertThat(c.resolve("legacy.corp").user()).isNull();
    // Wildcards and negations aren't aliases.
    assertThat(c.aliases()).containsExactly("legacy.corp");
  }

  @Test
  void includeReadsGlobsRelativeToTheSshFolderInTheBlockItStandsIn() throws IOException {
    Files.createDirectories(home.ssh().resolve("config.d"));
    Files.writeString(home.ssh().resolve("config.d/10-work.conf"), """
        Host work-db
          HostName 10.1.0.7
          User dba
        """);
    Files.writeString(home.ssh().resolve("config.d/20-home.conf"), """
        Host nas
          HostName 192.168.1.20
        """);
    Files.writeString(home.ssh().resolve("only-for-lab"), """
        User lab
        """);
    config("""
        Include config.d/*.conf
        Host lab-*
          Include only-for-lab
        Host *
          User fallback
        """);
    OpenSshConfig c = OpenSshConfig.read(home);

    assertThat(c.aliases()).containsExactly("work-db", "nas");
    assertThat(c.resolve("work-db").user()).isEqualTo("dba");
    assertThat(c.resolve("nas").host()).isEqualTo("192.168.1.20");
    // An Include inside a block applies only where the block does.
    assertThat(c.resolve("lab-1").user()).isEqualTo("lab");
    assertThat(c.resolve("prod").user()).isEqualTo("fallback");
  }

  @Test
  void aMatchThatWouldRunSomethingAppliesToNothing() throws IOException {
    Path ran = dir.resolve("ran");
    config("""
        Match exec "touch %s"
          User evil
        Match host *.example.test !originalhost skip.example.test
          Port 2200
        Match localuser localuser
          IdentityFile ~/.ssh/id_mine
        Match canonical all
          User canonical
        """.formatted(ran));
    OpenSshConfig c = OpenSshConfig.read(home);

    OpenSshConfig.Resolved r = c.resolve("app.example.test");
    assertThat(r.user()).isNull();
    assertThat(r.port()).isEqualTo(2200);
    assertThat(r.identityFiles()).containsExactly(home.ssh().resolve("id_mine").toString());
    assertThat(c.resolve("skip.example.test").port()).isNull();
    assertThat(ran).doesNotExist();
  }

  @Test
  void proxyJumpAndProxyCommandCompeteAndNoneMeansNone() throws IOException {
    config("""
        Host a
          ProxyJump bastion
          ProxyCommand ssh -W %h:%p other
        Host b
          ProxyCommand ssh -W %h:%p other
          ProxyJump bastion
        Host c
          ProxyJump none
        Host *
          ProxyJump everywhere
        """);
    OpenSshConfig c = OpenSshConfig.read(home);

    assertThat(c.resolve("a").proxyJump()).isEqualTo("bastion");
    assertThat(c.resolve("a").proxyCommand()).isNull();
    assertThat(c.resolve("b").proxyCommand()).isEqualTo("ssh -W %h:%p other");
    assertThat(c.resolve("b").proxyJump()).isNull();
    assertThat(c.resolve("c").proxyJump()).isNull();
    assertThat(c.resolve("d").proxyJump()).isEqualTo("everywhere");
  }

  @Test
  void anIncludeLoopAndBrokenLinesStayBounded() throws IOException {
    config("""
        Include config
        Host "unterminated
        Host ok
          HostName 10.0.0.1
        """);
    OpenSshConfig c = OpenSshConfig.read(home);

    assertThat(c.aliases()).containsExactly("ok");
    assertThat(c.resolve("ok").host()).isEqualTo("10.0.0.1");
  }

  @Test
  void aMissingConfigIsEmpty() {
    OpenSshConfig c = OpenSshConfig.read(home);

    assertThat(c.isEmpty()).isTrue();
    assertThat(c.resolve("anything").any()).isFalse();
  }

  @Test
  void wordsAndGlobsAsOpenSshSplitsAndMatchesThem() {
    assertThat(OpenSshConfig.words("a \"b c\" 'd e' f\\ g # comment")).containsExactly("a", "b c", "d e", "f g");
    assertThat(OpenSshConfig.words("\"open")).isNull();
    assertThat(OpenSshConfig.glob("web-01.example.test", "web-??.*.test")).isTrue();
    assertThat(OpenSshConfig.glob("web-1.example.test", "web-??.*.test")).isFalse();
    assertThat(OpenSshConfig.glob("anything", "*")).isTrue();
  }
}
