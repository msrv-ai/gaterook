package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The remote host selects bounded content; metadata browsing remains available over SFTP. */
class SshLogRemoteCommandsIT {
  @TempDir Path temp;
  private Path root;
  private TestSshd sshd;
  private SshLogsConnector connector;
  private TestSecrets secrets;

  @BeforeEach
  void start() throws Exception {
    root = temp.toRealPath();
    sshd = TestSshd.start();
    sshd.server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
    connector = new SshLogsConnector();
    secrets = new TestSecrets().with("/credentials/password", TestSshd.PASSWORD);
  }

  @AfterEach
  void stop() throws Exception {
    connector.close();
    sshd.close();
  }

  private ConnectionConfig cfg(String file) {
    ObjectNode config = TestConfigs.password(sshd.port(), sshd.hostKeyLine());
    var logs = config.putObject("logs").put("root", root.toString());
    logs.putArray("allowedFiles").add(file);
    return new ConnectionConfig("logs-remote", "server-logs", "ssh-logs", Env.DEV, 1, config,
        Set.of("/credentials/password", "/credentials/passphrase"), JsonNodeFactory.instance.objectNode());
  }

  private JsonNode run(String tool, String file, ObjectNode arguments, TestCtx context) throws Exception {
    arguments.put("file", file);
    return connector.execute(new ToolCall("tc_remote", tool, "server-logs", arguments, null), cfg(file), secrets,
        context).structuredContent();
  }

  @Test
  void tailsFiveRecordsFromALargeFileWithoutTransferringTheFileOrUsingSftpReads() throws Exception {
    Path file = root.resolve("large.log");
    try (RandomAccessFile data = new RandomAccessFile(file.toFile(), "rw")) {
      data.setLength(170L * 1024 * 1024);
      data.seek(data.length());
      data.writeBytes("\nlast-1\nlast-2\nlast-3\nlast-4\nlast-5\n");
    }
    var contentReads = new AtomicInteger();
    var sftp = new SftpSubsystemFactory.Builder().build();
    sftp.addSftpEventListener(new org.apache.sshd.sftp.server.SftpEventListener() {
      @Override
      public void reading(org.apache.sshd.server.session.ServerSession session, String handle,
          org.apache.sshd.sftp.server.FileHandle opened, long offset, byte[] data, int dataOffset, int length)
          throws IOException {
        contentReads.incrementAndGet();
        throw new IOException("Content must be selected by the remote command, not downloaded over SFTP");
      }
    });
    sshd.server.setSubsystemFactories(List.of(sftp));
    AtomicLong transferred = countTransferredBytes();
    var limits = TestCtx.limits(Duration.ofSeconds(30), Duration.ofSeconds(5), 256 * 1024, 64 * 1024);
    var result = run("ssh_logs_read", "large.log", JsonNodeFactory.instance.objectNode().put("tail", true)
        .put("maxLines", 5), TestCtx.of(limits));

    assertThat(result.path("lines")).isEqualTo(TestConfigs.json("[\"last-1\",\"last-2\",\"last-3\",\"last-4\",\"last-5\"]"));
    assertThat(result.path("bytesRead").asLong()).isLessThan(4096);
    assertThat(transferred.get()).as("all command stdout, including integrity reads").isLessThan(4096);
    assertThat(contentReads.get()).isZero();
    assertThat(sshd.commands).anyMatch(command -> command.contains("tail -n 5"));
  }

  @Test
  void lineRangesPageWithinInclusiveBoundsAndKeepByteOffsets() throws Exception {
    Files.writeString(root.resolve("app.log"), "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\n");
    var args = JsonNodeFactory.instance.objectNode().put("startLine", 3).put("endLine", 7).put("maxLines", 2);
    List<String> lines = new ArrayList<>();
    JsonNode result;
    int pages = 0;
    do {
      result = run("ssh_logs_read", "app.log", args, TestCtx.read());
      if (pages++ == 0) assertThat(result.path("entries").get(0).path("offset").asLong()).isEqualTo(12);
      result.path("lines").forEach(line -> lines.add(line.asString()));
      if (!result.path("scanComplete").asBoolean()) args.put("cursor", result.path("nextCursor").asString());
      assertThat(pages).isLessThanOrEqualTo(3);
    } while (!result.path("scanComplete").asBoolean());
    assertThat(lines).containsExactly("line3", "line4", "line5", "line6", "line7");
    assertThat(result.path("entries").get(0).path("endOffset").asLong()).isEqualTo(42);
  }

  @Test
  void lineRangeSearchFiltersOnlyRecordsWithinTheRange() throws Exception {
    Files.writeString(root.resolve("app.log"), "ERROR before\nINFO inside\nERROR inside\nERROR after\n");
    var result = run("ssh_logs_search", "app.log", JsonNodeFactory.instance.objectNode().put("startLine", 2)
        .put("endLine", 3).put("query", "ERROR"), TestCtx.read());
    assertThat(result.path("lines")).isEqualTo(TestConfigs.json("[\"ERROR inside\"]"));
    assertThat(result.path("scanComplete").asBoolean()).isTrue();
  }

  @Test
  void appendingDuringAReadAndBetweenPagesKeepsTheOriginalSnapshot() throws Exception {
    Path file = root.resolve("app.log");
    Files.writeString(file, "first\nsecond\nthird\n");
    var commands = sshd.server.getCommandFactory();
    var appended = new AtomicBoolean();
    sshd.server.setCommandFactory((channel, command) -> {
      if (appended.compareAndSet(false, true)) {
        command += "\ngr_test_status=$?\nprintf %s " + ShellQuote.quote("fourth\n") + " >> "
            + ShellQuote.quote(file.toString()) + "\nexit \"$gr_test_status\"";
      }
      return commands.createCommand(channel, command);
    });
    var args = JsonNodeFactory.instance.objectNode().put("tail", true).put("maxLines", 2);
    var first = run("ssh_logs_read", "app.log", args, TestCtx.read());
    assertThat(first.path("lines")).isEqualTo(TestConfigs.json("[\"second\",\"third\"]"));
    assertThat(appended.get()).isTrue();
    Files.writeString(file, "fifth\n", StandardOpenOption.APPEND);
    args.put("cursor", first.path("nextCursor").asString());
    var older = run("ssh_logs_read", "app.log", args, TestCtx.read());
    assertThat(older.path("lines")).isEqualTo(TestConfigs.json("[\"first\"]"));
    assertThat(older.path("scanComplete").asBoolean()).isTrue();
    args.remove("cursor");
    var fresh = run("ssh_logs_read", "app.log", args, TestCtx.read());
    assertThat(fresh.path("lines")).isEqualTo(TestConfigs.json("[\"fourth\",\"fifth\"]"));
  }

  @Test
  void continuationsRejectTruncationAndReplacementWithTheSameBytesAndTimestamp() throws Exception {
    for (boolean rotate : List.of(false, true)) {
      String name = rotate ? "rotated.log" : "truncated.log";
      Path file = root.resolve(name);
      String text = "first\nsecond\nthird\n";
      Files.writeString(file, text);
      var args = JsonNodeFactory.instance.objectNode().put("maxLines", 1);
      var first = run("ssh_logs_read", name, args, TestCtx.read());
      if (rotate) {
        var modified = Files.getLastModifiedTime(file);
        Files.move(file, root.resolve(name + ".old"));
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, modified);
      } else {
        Files.writeString(file, "short\n");
      }
      args.put("cursor", first.path("nextCursor").asString());
      assertThatThrownBy(() -> run("ssh_logs_read", name, args, TestCtx.read()))
          .as(name).isInstanceOf(ConnectorException.class);
    }
  }

  @Test
  void filenamesAndSearchTermsRemainDataInsteadOfShellPrograms() throws Exception {
    String name = "log ' ; touch INJECTED ; #.log";
    String needle = "$(touch INJECTED) ; `touch INJECTED`";
    Files.writeString(root.resolve(name), "ordinary\n" + needle + "\n");
    var commands = sshd.server.getCommandFactory();
    sshd.server.setCommandFactory((channel, command) -> commands.createCommand(channel,
        "cd " + ShellQuote.quote(root.toString()) + "\n" + command));
    var result = run("ssh_logs_search", name, JsonNodeFactory.instance.objectNode().put("query", needle), TestCtx.read());
    assertThat(result.path("lines")).hasSize(1);
    assertThat(result.path("lines").get(0).asString()).isEqualTo(needle);
    assertThat(Files.exists(root.resolve("INJECTED"))).isFalse();
  }

  @Test
  void grepReducesTransferredCandidatesWhileMaskedMatchingStillDecidesResults() throws Exception {
    Files.writeString(root.resolve("app.log"), "ordinary event\n".repeat(80000)
        + "ERROR visible\nWARN visible\nERROR secret-value\n");
    AtomicLong transferred = countTransferredBytes();
    var ctx = TestCtx.read().withTextMaskMarker("[masked]").withMasker(tree -> tree.isString()
        ? JsonNodeFactory.instance.textNode(tree.asString().replace("ERROR secret-value", "[masked]")) : tree);
    var result = run("ssh_logs_search", "app.log", JsonNodeFactory.instance.objectNode().put("query", "ERROR|WARN")
        .put("matchMode", "regex"), ctx);
    assertThat(result.path("lines")).isEqualTo(TestConfigs.json("[\"ERROR visible\",\"WARN visible\"]"));
    assertThat(result.path("scanComplete").asBoolean()).isTrue();
    assertThat(transferred.get()).isLessThan(16 * 1024);
    assertThat(sshd.commands).anyMatch(command -> command.contains("grep -a -b -E"));
  }

  private AtomicLong countTransferredBytes() {
    AtomicLong transferred = new AtomicLong();
    var commands = sshd.server.getCommandFactory();
    sshd.server.setCommandFactory((channel, command) ->
        new CountingCommand(commands.createCommand(channel, command), transferred));
    return transferred;
  }

  private record CountingCommand(Command delegate, AtomicLong transferred) implements Command {
    @Override public void setInputStream(InputStream in) { delegate.setInputStream(in); }
    @Override public void setErrorStream(OutputStream err) { delegate.setErrorStream(err); }
    @Override public void setExitCallback(ExitCallback callback) { delegate.setExitCallback(callback); }
    @Override public void start(ChannelSession channel, Environment env) throws IOException { delegate.start(channel, env); }
    @Override public void destroy(ChannelSession channel) throws Exception { delegate.destroy(channel); }

    @Override
    public void setOutputStream(OutputStream out) {
      delegate.setOutputStream(new FilterOutputStream(out) {
        @Override
        public void write(int value) throws IOException {
          transferred.incrementAndGet();
          out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
          transferred.addAndGet(length);
          out.write(bytes, offset, length);
        }
      });
    }
  }
}
