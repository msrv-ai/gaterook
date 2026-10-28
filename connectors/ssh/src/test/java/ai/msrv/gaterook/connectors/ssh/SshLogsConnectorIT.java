package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Real SSH authentication and SFTP reads against a disposable in-process server; no shell commands. */
class SshLogsConnectorIT {
  @TempDir Path temp;
  private Path root;
  private TestSshd sshd;
  private SshLogsConnector connector;
  private TestSecrets secrets;

  @BeforeEach
  void start() throws Exception {
    root = temp.toRealPath();
    Files.writeString(root.resolve("app.log"), "first\nERROR one\nthird\nERROR two\nlast\n");
    Files.writeString(root.resolve("private.txt"), "not selected");
    Files.createDirectory(root.resolve("nested"));
    Files.writeString(root.resolve("nested/worker.log"), "worker\n");
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

  private ConnectionConfig cfg(boolean pin, String... files) {
    ObjectNode config = TestConfigs.password(sshd.port(), pin ? sshd.hostKeyLine() : null);
    var logs = config.putObject("logs");
    logs.put("root", root.toString());
    var allowed = logs.putArray("allowedFiles");
    for (String file : files) {
      allowed.add(file);
    }
    return config(config);
  }

  private ConnectionConfig config(ObjectNode config) {
    return new ConnectionConfig("logs1", "server-logs", "ssh-logs", Env.DEV, 1, config,
        Set.of("/credentials/password", "/credentials/passphrase"), JsonNodeFactory.instance.objectNode());
  }

  private ToolCall call(String tool, String json) {
    return new ToolCall("tc_logs", tool, "server-logs", (ObjectNode) TestConfigs.json(json), null);
  }

  @Test
  void discoversFoldersWithoutReadingFilesAndPromptsBeforeResolvingCredentials() {
    var untrusted = cfg(false);
    var result = connector.browseFiles(untrusted, secrets, TestCtx.read(), root.toString());
    assertThat(result.needsConfirmation()).isNotNull();
    assertThat(result.needsConfirmation().kind()).isEqualTo("host-key");
    assertThat(secrets.resolved.get()).isZero();
    assertThat(sshd.passwordAttempts.get()).isZero();
    assertThat(result.needsConfirmation().configPatch().path("hostKeys").path("pinned").get(0).asString())
        .isEqualTo(sshd.hostKeyLine());
    var trusted = connector.browseFiles(cfg(true), secrets, TestCtx.read(), root.toString());
    assertThat(trusted.ok()).as(String.valueOf(trusted.error())).isTrue();
    assertThat(trusted.details().path("path").asString()).isEqualTo(root.toString());
    assertThat(trusted.details().path("entries")).hasSize(3);
    assertThat(sshd.commands).isEmpty();
  }

  @Test
  void listsOnlyAllowedFilesAndReadsWithForwardPagingAndTail() throws Exception {
    var cfg = cfg(true, "app.log", "nested/worker.log");
    assertThat(connector.test(cfg, secrets, TestCtx.read()).ok()).isTrue();
    var list = connector.execute(call("ssh_logs_list", "{}"), cfg, secrets, TestCtx.read()).structuredContent();
    assertThat(list.path("files")).hasSize(2);
    var first = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"maxLines\":2}"), cfg,
        secrets, TestCtx.read()).structuredContent();
    assertThat(first.path("lines")).isEqualTo(TestConfigs.json("[\"first\",\"ERROR one\"]"));
    assertThat(first.path("nextOffset").asLong()).isEqualTo("first\nERROR one\n".getBytes(StandardCharsets.UTF_8).length);
    var next = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"maxLines\":2,\"offset\":"
        + first.path("nextOffset").asLong() + "}"), cfg, secrets, TestCtx.read()).structuredContent();
    assertThat(next.path("lines")).isEqualTo(TestConfigs.json("[\"third\",\"ERROR two\"]"));
    var tail = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"maxLines\":2,\"tail\":true}"),
        cfg, secrets, TestCtx.read()).structuredContent();
    assertThat(tail.path("lines")).isEqualTo(TestConfigs.json("[\"ERROR two\",\"last\"]"));
    assertThat(sshd.commands).isEmpty();
  }

  @Test
  void searchesLiteralTextAndContinuesAfterTheLastMatchWithoutSkippingMatches() throws Exception {
    var cfg = cfg(true, "app.log");
    var first = connector.execute(call("ssh_logs_search", "{\"file\":\"app.log\",\"query\":\"ERROR\",\"maxLines\":1}"),
        cfg, secrets, TestCtx.read()).structuredContent();
    assertThat(first.path("lines")).isEqualTo(TestConfigs.json("[\"ERROR one\"]"));
    var next = connector.execute(call("ssh_logs_search", "{\"file\":\"app.log\",\"query\":\"ERROR\",\"offset\":"
        + first.path("nextOffset").asLong() + "}"), cfg, secrets, TestCtx.read()).structuredContent();
    assertThat(next.path("lines")).isEqualTo(TestConfigs.json("[\"ERROR two\"]"));
    var literal = connector.execute(call("ssh_logs_search", "{\"file\":\"app.log\",\"query\":\".*\"}"),
        cfg, secrets, TestCtx.read()).structuredContent();
    assertThat(literal.path("lines")).isEmpty();
  }

  @Test
  void searchMatchesMaskedTextSoItCannotGuessAHiddenSecret() throws Exception {
    Files.writeString(root.resolve("app.log"), "token-secret ERROR\nordinary line\n");
    var context = TestCtx.read().withMasker(tree -> {
      return JsonNodeFactory.instance.textNode(tree.asString().replace("token-secret", "[masked]"));
    });
    var result = connector.execute(call("ssh_logs_search", "{\"file\":\"app.log\",\"query\":\"token-secret\"}"),
        cfg(true, "app.log"), secrets, context);
    assertThat(result.structuredContent().path("lines")).isEmpty();
  }

  @Test
  void cancellationClosesTheSessionWhileAReadIsBlocked() throws Exception {
    var reading = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var factory = new SftpSubsystemFactory.Builder().build();
    factory.addSftpEventListener(new org.apache.sshd.sftp.server.SftpEventListener() {
      @Override
      public void reading(org.apache.sshd.server.session.ServerSession session, String handle,
          org.apache.sshd.sftp.server.FileHandle file, long offset, byte[] data, int dataOffset, int length)
          throws java.io.IOException {
        reading.countDown();
        try {
          release.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new java.io.IOException("Test read interrupted", e);
        }
      }
    });
    sshd.server.setSubsystemFactories(List.of(factory));
    var context = TestCtx.read();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\"}"),
          cfg(true, "app.log"), secrets, context));
      try {
        assertThat(reading.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        context.cancel();
        assertThatThrownBy(() -> result.get(2, java.util.concurrent.TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(ConnectorException.class);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void rejectsUnselectedTraversalSymlinksAndDirectories() throws Exception {
    var cfg = cfg(true, "app.log");
    for (String path : List.of("private.txt", "../private.txt", "/etc/passwd", "nested/../app.log", "nested//worker.log")) {
      ObjectNode args = JsonNodeFactory.instance.objectNode().put("file", path);
      var call = new ToolCall("tc_denied", "ssh_logs_read", cfg.name(), args, null);
      assertThatThrownBy(() -> connector.execute(call, cfg, secrets, TestCtx.read())).isInstanceOf(ConnectorException.class);
    }
    Files.createSymbolicLink(root.resolve("link.log"), root.resolve("private.txt"));
    Files.createSymbolicLink(root.resolve("linked"), root.resolve("nested"));
    for (String file : List.of("link.log", "linked/worker.log", "nested")) {
      var forbidden = cfg(true, file);
      assertThatThrownBy(() -> connector.execute(call("ssh_logs_read", "{\"file\":\"" + file + "\"}"),
          forbidden, secrets, TestCtx.read())).isInstanceOf(ConnectorException.class);
    }
    var browser = connector.browseFiles(cfg(true), secrets, TestCtx.read(), root.toString());
    assertThat(browser.details().path("entries")).hasSize(3);
    assertThat(sshd.commands).isEmpty();
  }

  @Test
  void detectsSymlinkReplacementAfterAFileWasSelected() throws Exception {
    var cfg = cfg(true, "app.log");
    assertThat(connector.test(cfg, secrets, TestCtx.read()).ok()).isTrue();
    Files.delete(root.resolve("app.log"));
    Files.createSymbolicLink(root.resolve("app.log"), root.resolve("private.txt"));
    assertThatThrownBy(() -> connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\"}"), cfg, secrets,
        TestCtx.read())).isInstanceOf(ConnectorException.class).hasMessageContaining("Symbolic links");
  }

  @Test
  void readsEncryptedLocalPrivateKeyWithoutUploadingItAndRereadsAfterReplacement() throws Exception {
    var pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    sshd.authorizedKeys.add(pair.getPublic());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    OpenSSHKeyEncryptionContext encryption = new OpenSSHKeyEncryptionContext();
    encryption.setPassword("key password");
    encryption.setCipherName("AES");
    encryption.setCipherMode("CTR");
    encryption.setCipherType("256");
    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test key", encryption, output);
    Path key = root.resolve("identity");
    Files.write(key, output.toByteArray());
    ObjectNode settings = (ObjectNode) cfg(true, "app.log").config();
    settings.putObject("credentials").put("method", "privateKeyFile").put("privateKeyPath", key.toString());
    var cfg = config(settings);
    secrets = new TestSecrets().with("/credentials/passphrase", "key password");
    var ctx = TestCtx.read();
    assertThat(connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\"}"), cfg, secrets, ctx).isError()).isFalse();
    assertThat(ctx.tainted()).anyMatch(s -> s.contains("BEGIN OPENSSH PRIVATE KEY"));
    assertThat(sshd.passwordAttempts.get()).isZero();
    Files.writeString(key, "invalid replacement");
    assertThatThrownBy(() -> connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\"}"), cfg, secrets,
        TestCtx.read())).isInstanceOf(ConnectorException.class);
    assertThat(sshd.commands).isEmpty();
  }

  @Test
  void rejectsChangedHostBeforeCredentialsAndRejectsEmptyCredentialPolicy() {
    ObjectNode config = (ObjectNode) cfg(true, "app.log").config();
    config.putObject("hostKeys").putArray("pinned").add("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIG4q5cLnwuK6nh3KekSjpSxn3RUE8TATPm0X+fK02R8D");
    var result = connector.test(config(config), secrets, TestCtx.read());
    assertThat(result.ok()).isFalse();
    assertThat(result.needsConfirmation()).isNull();
    assertThat(secrets.resolved.get()).isZero();
    var policy = new PolicyView(Env.DEV, "dev.ssh", TestConfigs.json("{\"allowedCredentialMethods\":[]}"), TestCtx.devLimits());
    assertThat(connector.classify(call("ssh_logs_list", "{}"), cfg(true, "app.log"), policy).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void escapedControlLinesFitTheResponseBudgetAndContinueWithoutLoss() throws Exception {
    Files.write(root.resolve("app.log"), ("\0\0\0\0\0\0\0\0\0\0\n".repeat(300)).getBytes(StandardCharsets.UTF_8));
    var defaults = TestCtx.devLimits();
    var limits = new ai.msrv.gaterook.connector.api.Limits(defaults.maxRows(), defaults.maxLines(), 8192,
        defaults.maxRequestBytes(), defaults.maxOutputBytes(), defaults.maxStderrBytes(), defaults.maxCellChars(),
        defaults.statementTimeout(), defaults.commandTimeout(), defaults.toolTimeout(), defaults.maxRange());
    long offset = 0;
    int count = 0;
    while (offset < Files.size(root.resolve("app.log"))) {
      var result = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"offset\":" + offset + "}"),
          cfg(true, "app.log"), secrets, TestCtx.of(limits)).structuredContent();
      assertThat(result.toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);
      assertThat(result.path("nextOffset").asLong()).isGreaterThan(offset);
      count += result.path("lines").size();
      offset = result.path("nextOffset").asLong();
    }
    assertThat(count).isEqualTo(300);
  }

  @Test
  void findsRareMatchesBeyondTheOutputWindowAcrossAllSelectedFiles() throws Exception {
    Files.writeString(root.resolve("app.log"), ("ordinary event " + "x".repeat(1000) + "\n").repeat(3500)
        + "2026-10-28T10:30:00Z ERROR Rare timeout\n");
    Files.writeString(root.resolve("nested/worker.log"), "2026-10-28T10:40:00Z WARNING rare retry\n");
    var result = connector.execute(call("ssh_logs_search", "{\"query\":\"rare\",\"caseSensitive\":false}"),
        cfg(true, "app.log", "nested/worker.log"), secrets, TestCtx.read()).structuredContent();
    assertThat(result.path("entries")).hasSize(2);
    assertThat(result.path("entries").get(0).path("file").asString()).isEqualTo("app.log");
    assertThat(result.path("entries").get(1).path("file").asString()).isEqualTo("nested/worker.log");
    assertThat(result.path("scannedBytes").asLong()).isGreaterThan(3 * 1024 * 1024);
    assertThat(result.path("scanComplete").asBoolean()).isTrue();
  }

  @Test
  void actualTailScansPastOversizedLinesAndPagesBackwardsWithoutDuplicates() throws Exception {
    Files.writeString(root.resolve("app.log"), "oldest\r\n" + "x".repeat(2 * 1024 * 1024) + "\nnewer\r\nnewest\n");
    var first = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"tail\":true,\"maxLines\":2}"),
        cfg(true, "app.log"), secrets, TestCtx.read()).structuredContent();
    assertThat(first.path("lines")).isEqualTo(TestConfigs.json("[\"newer\",\"newest\"]"));
    ObjectNode args = (ObjectNode) TestConfigs.json("{\"file\":\"app.log\",\"tail\":true,\"maxLines\":2}");
    args.put("cursor", first.path("nextCursor").asString());
    var next = connector.execute(new ToolCall("tc_next", "ssh_logs_read", "server-logs", args, null),
        cfg(true, "app.log"), secrets, TestCtx.read()).structuredContent();
    assertThat(next.path("lines")).isEqualTo(TestConfigs.json("[\"oldest\"]"));
    assertThat(next.path("skippedLines").asInt()).isEqualTo(1);
    assertThat(next.path("scannedBytes").asLong()).isGreaterThan(2 * 1024 * 1024);
    assertThat(next.path("scanComplete").asBoolean()).isTrue();
  }

  @Test
  void scanCapCanReturnAnEmptyPageWithSafeForwardAndReverseContinuation() throws Exception {
    for (boolean tail : List.of(false, true)) {
      String filler = "unrelated log event\n".repeat(120000);
      Files.writeString(root.resolve("app.log"), tail ? "NEEDLE\n" + filler : filler + "NEEDLE\n");
      ObjectNode args = JsonNodeFactory.instance.objectNode().put("file", "app.log").put("query", "NEEDLE")
          .put("scanBytes", 1024 * 1024).put("tail", tail);
      JsonNode result;
      int total = 0;
      int pages = 0;
      do {
        result = connector.execute(new ToolCall("tc_scan", "ssh_logs_search", "server-logs", args, null),
            cfg(true, "app.log"), secrets, TestCtx.read()).structuredContent();
        assertThat(result.path("scannedBytes").asLong()).isLessThanOrEqualTo(1024 * 1024);
        if (pages++ == 0) {
          assertThat(result.path("lines")).isEmpty();
          assertThat(result.path("truncationReason").asString()).isEqualTo("scan_limit");
        }
        total += result.path("lines").size();
        if (!result.path("scanComplete").asBoolean()) args.put("cursor", result.path("nextCursor").asString());
        assertThat(pages).isLessThan(6);
      } while (!result.path("scanComplete").asBoolean());
      assertThat(total).isEqualTo(1);
    }
  }

  @Test
  void filtersMixedTextAndJsonlByHalfOpenEventTimeRegexAndNormalizedLevels() throws Exception {
    Files.writeString(root.resolve("app.log"), "2026-10-28T10:00:00Z INFO start\n"
        + "2026-10-28T10:15:00Z ERROR Connection REFUSED\n"
        + "{\"timestamp\":\"2026-10-28T10:30:00Z\",\"severity\":\"warning\",\"message\":\"Timeout waiting\"}\n"
        + "2026-10-28T11:00:00Z ERROR timeout excluded\n"
        + "stack trace continuation\n");
    var result = connector.execute(call("ssh_logs_search", """
        {"file":"app.log","from":"2026-10-28T10:00:00Z","to":"2026-10-28T11:00:00Z",
         "query":"timeout|connection refused","matchMode":"regex","caseSensitive":false,"levels":["warning","ERROR"]}
        """), cfg(true, "app.log"), secrets, TestCtx.read()).structuredContent();
    assertThat(result.path("entries")).hasSize(2);
    assertThat(result.path("entries").get(0).path("timestamp").asString()).isEqualTo("2026-10-28T10:15:00Z");
    assertThat(result.path("entries").get(1).path("level").asString()).isEqualTo("WARN");
    assertThat(result.path("unparsedLines").asInt()).isEqualTo(1);
    assertThat(result.path("warnings")).isNotEmpty();
  }

  @Test
  void masksJsonSecretFieldsBeforeQueryingAndReturningRecords() throws Exception {
    Files.writeString(root.resolve("app.log"), "{\"password\":\"secret-guess\",\"level\":\"ERROR\",\"message\":\"failed\"}\n");
    var context = TestCtx.read().withMasker(tree -> {
      if (tree.isObject()) {
        ObjectNode masked = ((ObjectNode) tree).deepCopy();
        masked.put("password", "[masked]");
        return masked;
      }
      return tree;
    });
    var hidden = connector.execute(call("ssh_logs_search", "{\"query\":\"secret-guess\"}"), cfg(true, "app.log"), secrets,
        context).structuredContent();
    assertThat(hidden.path("entries")).isEmpty();
    var visible = connector.execute(call("ssh_logs_search", "{\"query\":\"failed\"}"), cfg(true, "app.log"), secrets,
        context).structuredContent();
    assertThat(visible.path("lines").get(0).asString()).contains("[masked]").doesNotContain("secret-guess");
  }

  @Test
  void signedCursorRejectsTamperingChangedQueryChangedConfigAndFileMutation() throws Exception {
    var cfg = cfg(true, "app.log");
    var first = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"maxLines\":1}"), cfg, secrets,
        TestCtx.read()).structuredContent();
    String cursor = first.path("nextCursor").asString();
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("file", "app.log").put("maxLines", 1).put("cursor", cursor + "x");
    assertThatThrownBy(() -> connector.execute(new ToolCall("tc", "ssh_logs_read", cfg.name(), args, null), cfg, secrets,
        TestCtx.read())).isInstanceOf(ConnectorException.class).hasMessageContaining("invalid");
    args.put("cursor", cursor).put("maxLines", 2);
    assertThatThrownBy(() -> connector.execute(new ToolCall("tc", "ssh_logs_read", cfg.name(), args, null), cfg, secrets,
        TestCtx.read())).isInstanceOf(ConnectorException.class).hasMessageContaining("another query");
    args.put("maxLines", 1);
    var changed = new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), 2, cfg.config(), cfg.secretFields(), cfg.metadata());
    assertThatThrownBy(() -> connector.execute(new ToolCall("tc", "ssh_logs_read", cfg.name(), args, null), changed, secrets,
        TestCtx.read())).isInstanceOf(ConnectorException.class).hasMessageContaining("settings have changed");
    var modified = Files.getLastModifiedTime(root.resolve("app.log"));
    Files.writeString(root.resolve("app.log"), "FIRST\nERROR one\nthird\nERROR two\nlast\n");
    Files.setLastModifiedTime(root.resolve("app.log"), modified);
    assertThatThrownBy(() -> connector.execute(new ToolCall("tc", "ssh_logs_read", cfg.name(), args, null), cfg, secrets,
        TestCtx.read())).isInstanceOf(ConnectorException.class).hasMessageContaining("changed");
  }

  @Test
  void relativeTimeAnchorAndMultiFileOrderRemainStableAcrossPages() throws Exception {
    var now = java.time.Instant.now();
    Files.writeString(root.resolve("app.log"), now.minusSeconds(100) + " INFO first\n" + now.minusSeconds(90) + " INFO second\n");
    Files.writeString(root.resolve("nested/worker.log"), now.minusSeconds(200) + " INFO third\n");
    ObjectNode args = (ObjectNode) TestConfigs.json("{\"files\":[\"nested/worker.log\",\"app.log\"],\"from\":\"-1h\",\"maxLines\":1}");
    List<String> names = new java.util.ArrayList<>();
    String anchor = null;
    for (int i = 0; i < 3; i++) {
      var result = connector.execute(new ToolCall("tc_order", "ssh_logs_search", "server-logs", args, null),
          cfg(true, "app.log", "nested/worker.log"), secrets, TestCtx.read()).structuredContent();
      if (anchor == null) anchor = result.path("anchor").asString();
      assertThat(result.path("anchor").asString()).isEqualTo(anchor);
      names.add(result.path("entries").get(0).path("file").asString());
      if (i < 2) args.put("cursor", result.path("nextCursor").asString());
      else assertThat(result.path("scanComplete").asBoolean()).isTrue();
    }
    assertThat(names).containsExactly("app.log", "app.log", "nested/worker.log");
  }

  @Test
  void cursorFingerprintsHideRawLogHashesAndAreScopedToEachScanAndGatewaySession() throws Exception {
    byte[] source = Files.readAllBytes(root.resolve("app.log"));
    String rawHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source));
    var context = TestCtx.read();
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("file", "app.log").put("maxLines", 1);
    var call = new ToolCall("tc_private_cursor", "ssh_logs_read", "server-logs", args, null);
    var first = connector.execute(call, cfg(true, "app.log"), secrets, context).structuredContent();
    String cursor = first.path("nextCursor").asString();
    var payload = context.handles().open("ssh-logs.scan", cursor.substring(4)).orElseThrow();
    assertThat(payload.toString()).doesNotContain(rawHash).doesNotContain("ERROR one");
    var second = connector.execute(call, cfg(true, "app.log"), secrets, context).structuredContent();
    var payload2 = context.handles().open("ssh-logs.scan", second.path("nextCursor").asString().substring(4)).orElseThrow();
    assertThat(payload.path("snapshot").path("head").asString())
        .isNotEqualTo(payload2.path("snapshot").path("head").asString());
    args.put("cursor", cursor);
    try (var restarted = new SshLogsConnector()) {
      assertThatThrownBy(() -> restarted.execute(call, cfg(true, "app.log"), secrets, context))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("previous gateway session");
    }
  }

  @Test
  void tailReturnsTheWholeUtf8RecordAcrossChunkBoundaries() throws Exception {
    String text = "😀".repeat(20000);
    Files.writeString(root.resolve("app.log"), "older\n" + text + "\n");
    var result = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"tail\":true,\"maxLines\":1}"),
        cfg(true, "app.log"), secrets, TestCtx.read()).structuredContent();
    assertThat(result.path("lines").get(0).asString()).isEqualTo(text);
    assertThat(result.path("entries").get(0).path("offset").asLong()).isEqualTo(6);
    assertThat(result.path("partialLine").asBoolean()).isFalse();
  }

  @Test
  void refusesMidLineOffsetsAndCompressedFiles() throws Exception {
    assertThatThrownBy(() -> connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\",\"offset\":2}"),
        cfg(true, "app.log"), secrets, TestCtx.read())).isInstanceOf(ConnectorException.class).hasMessageContaining("line boundary");
    Files.write(root.resolve("app.log"), new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0});
    assertThatThrownBy(() -> connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\"}"), cfg(true, "app.log"),
        secrets, TestCtx.read())).isInstanceOf(ConnectorException.class).hasMessageContaining("Compressed");
  }

  @Test
  void boundsDirectoryListingAndByteReadsAndHonorsCancellation() throws Exception {
    for (int i = 0; i < 510; i++) {
      Files.createFile(root.resolve("extra-" + i + ".log"));
    }
    var browse = connector.browseFiles(cfg(true), secrets, TestCtx.read(), root.toString());
    assertThat(browse.ok()).isTrue();
    assertThat(browse.details().path("truncated").asBoolean()).isTrue();
    assertThat(browse.details().path("entries").size()).isLessThanOrEqualTo(500);
    Files.writeString(root.resolve("app.log"), "x".repeat(1024 * 1024));
    var read = connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\"}"), cfg(true, "app.log"), secrets, TestCtx.read());
    assertThat(read.structuredContent().path("bytesRead").asLong()).isEqualTo(1024 * 1024);
    assertThat(read.structuredContent().path("skippedLines").asInt()).isEqualTo(1);
    assertThat(read.structuredContent().path("partialLine").asBoolean()).isFalse();
    var cancelled = TestCtx.read();
    cancelled.cancel();
    assertThatThrownBy(() -> connector.execute(call("ssh_logs_read", "{\"file\":\"app.log\"}"), cfg(true, "app.log"),
        secrets, cancelled)).isInstanceOf(ConnectorException.class).hasMessageContaining("cancelled");
  }
}
