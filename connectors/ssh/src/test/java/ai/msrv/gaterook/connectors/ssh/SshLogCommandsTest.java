package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;

class SshLogCommandsTest {
  @TempDir Path temp;

  @Test
  void readsTailRangesAndBytesWithPortableRemoteUtilities() throws Exception {
    Path root = temp.toRealPath();
    Files.writeString(root.resolve("app.log"), "first\nsecond\nthird\nfourth\nfifth\n");
    var settings = settings(root, "app.log");

    var tail = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.TAIL, 2, 0, 1024));
    assertThat(text(tail.data())).isEqualTo("fourth\nfifth\n");
    assertThat(tail.before().size()).isEqualTo(Files.size(root.resolve("app.log")));
    assertThat(tail.after()).isEqualTo(tail.before());
    var head = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.HEAD, 2, 0, 1024));
    assertThat(text(head.data())).isEqualTo("first\nsecond\n");
    assertThat(head.offset()).isZero();
    assertThat(head.endOffset()).isEqualTo(13);
    assertThat(head.endsAtLineBoundary()).isTrue();

    var range = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.LINES, 2, 4, 1024));
    assertThat(text(range.data())).isEqualTo("second\nthird\nfourth\n");
    assertThat(range.offset()).isEqualTo(6);
    var bytes = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.BYTES, 6, 7, 7));
    assertThat(text(bytes.data())).isEqualTo("second\n");
    var atEnd = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.BYTES,
        Files.size(root.resolve("app.log")), 16, 16));
    assertThat(atEnd.data()).isEmpty();
  }

  @Test
  void lineRangesPreserveUtf8OffsetsAndAnUnterminatedLastLine() throws Exception {
    Path root = temp.toRealPath();
    Files.writeString(root.resolve("app.log"), "日本語\nsecond\nlast");
    var settings = settings(root, "app.log");
    var range = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.LINES, 2, 3, 1024));
    assertThat(text(range.data())).isEqualTo("second\nlast");
    assertThat(range.offset()).isEqualTo("日本語\n".getBytes(StandardCharsets.UTF_8).length);
    var empty = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.LINES, 5, 8, 1024));
    assertThat(empty.data()).isEmpty();
    assertThat(empty.offset()).isEqualTo(Files.size(root.resolve("app.log")));
  }

  @Test
  void reportsLineBoundsAndPartialWindowsWithoutTransferringWholeFiles() throws Exception {
    Path root = temp.toRealPath();
    Files.writeString(root.resolve("app.log"), "first\nsecond\nthird\nlast");
    var settings = settings(root, "app.log");
    var bounds = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.BOUNDS, 2, 3, 0));
    assertThat(bounds.data()).isEmpty();
    assertThat(bounds.offset()).isEqualTo(6);
    assertThat(bounds.endOffset()).isEqualTo(19);
    var unbounded = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.BOUNDS, 3, 0, 0));
    assertThat(unbounded.offset()).isEqualTo(13);
    assertThat(unbounded.endOffset()).isEqualTo(Files.size(root.resolve("app.log")));

    var partial = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.BYTES, 2, 8, 8));
    assertThat(text(partial.data())).isEqualTo("rst\nseco");
    assertThat(partial.startsAtLineBoundary()).isFalse();
    assertThat(partial.endsAtLineBoundary()).isFalse();
    assertThat(partial.lastCompleteEnd()).isEqualTo(6);

    var tail = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.TAIL, 5, 0, 8));
    assertThat(tail.data()).hasSize(8);
    assertThat(tail.startsAtLineBoundary()).isFalse();
    assertThat(tail.endsAtLineBoundary()).isTrue();
    assertThat(tail.offset()).isEqualTo(tail.before().size() - 8);
    var head = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.HEAD, 5, 0, 8));
    assertThat(text(head.data())).isEqualTo("first\nse");
    assertThat(head.startsAtLineBoundary()).isTrue();
    assertThat(head.endsAtLineBoundary()).isFalse();
    assertThat(head.lastCompleteEnd()).isEqualTo(6);
  }

  @Test
  void grepReturnsCandidatesAndQueryIndependentSourceBounds() throws Exception {
    Path root = temp.toRealPath();
    Files.writeString(root.resolve("app.log"), "first\nERROR selected\nlast");
    var settings = settings(root, "app.log");
    var match = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.GREP, 0, 22, 1024, "ERROR"));
    var none = run(SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.GREP, 0, 22, 1024, "absent"));
    assertThat(text(match.data())).isEqualTo("6:ERROR selected\n");
    assertThat(none.data()).isEmpty();
    assertThat(match.offset()).isEqualTo(none.offset()).isZero();
    assertThat(match.endOffset()).isEqualTo(none.endOffset()).isEqualTo(22);
    assertThat(match.lastCompleteEnd()).isEqualTo(none.lastCompleteEnd()).isEqualTo(21);
  }

  @Test
  void fileNamesRemainDataRatherThanShellSyntax() throws Exception {
    Path root = temp.toRealPath();
    for (String name : List.of("a'b.log", "$(touch injected).log", "a; touch injected #.log", "--help", "日本語.log")) {
      Files.writeString(root.resolve(name), "literal path\n");
      var read = run(SshLogCommands.command(settings(root, name), name, SshLogCommands.Kind.TAIL, 1, 0, 1024));
      assertThat(text(read.data())).isEqualTo("literal path\n");
    }
    assertThat(root.resolve("injected")).doesNotExist();
  }

  @Test
  void rejectsSymlinkAncestorsAndNonRegularFilesBeforeReading() throws Exception {
    Path root = temp.toRealPath();
    Path nested = Files.createDirectory(root.resolve("nested"));
    Files.writeString(nested.resolve("app.log"), "private\n");
    Files.createSymbolicLink(root.resolve("linked"), nested);
    Files.createSymbolicLink(root.resolve("link.log"), nested.resolve("app.log"));
    for (String name : List.of("linked/app.log", "link.log", "nested")) {
      var process = process(SshLogCommands.command(settings(root, name), name, SshLogCommands.Kind.TAIL, 1, 0, 1024));
      assertThat(process.getInputStream().readAllBytes()).isEmpty();
      assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isIn(41, 42);
    }
  }

  @Test
  void rejectsUnselectedFilesAndInvalidRangesLocally() throws Exception {
    var settings = settings(temp.toRealPath(), "app.log");
    assertThatThrownBy(() -> SshLogCommands.command(settings, "private.log", SshLogCommands.Kind.TAIL, 1, 0, 1024))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("not selected");
    assertThatThrownBy(() -> SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.LINES, 4, 2, 1024))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("invalid");
    assertThatThrownBy(() -> SshLogCommands.command(settings, "app.log", SshLogCommands.Kind.BYTES, -1, 2, 1024))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("invalid");
  }

  @Test
  void acceptsAppendButRejectsReplacementTruncationAndMalformedMetadata() throws Exception {
    assertThat(SshLogCommands.decode(new byte[0], metadata("1:2:100:10", "1:2:120:11")).after().size()).isEqualTo(120);
    for (String after : List.of("1:3:100:10", "1:2:80:10", "1:2:100:11")) {
      assertThatThrownBy(() -> SshLogCommands.decode(new byte[0], metadata("1:2:100:10", after)))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("replaced, truncated or rewritten");
    }
    assertThatThrownBy(() -> SshLogCommands.decode(new byte[0], "server banner\n".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("metadata");
  }

  private static SshLogSettings settings(Path root, String file) {
    return new SshLogSettings(null, null, root.toString(), List.of(file), JsonNodeFactory.instance.objectNode());
  }

  private SshLogCommands.Result run(String command) throws Exception {
    Process process = process(command);
    byte[] stdout = process.getInputStream().readAllBytes();
    byte[] stderr = process.getErrorStream().readAllBytes();
    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(text(stderr)).isZero();
    return SshLogCommands.decode(stdout, stderr);
  }

  private Process process(String command) throws Exception {
    return new ProcessBuilder("/bin/sh", "-c", command).directory(temp.toFile()).start();
  }

  private static String text(byte[] data) {
    return new String(data, StandardCharsets.UTF_8);
  }

  private static byte[] metadata(String before, String after) {
    return ("GATEROOK_LOG_V1:" + before + "\nGATEROOK_LOG_WINDOW:0:0:1:1:0\nGATEROOK_LOG_V1:" + after + "\n")
        .getBytes(StandardCharsets.US_ASCII);
  }
}
