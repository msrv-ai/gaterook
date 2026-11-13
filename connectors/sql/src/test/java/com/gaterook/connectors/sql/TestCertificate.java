package com.gaterook.connectors.sql;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A self-signed server certificate for {@code localhost} only, made once per run with the JDK's own {@code keytool}:
 * its PEM is the CA a connection trusts, so verify-full passes for "localhost" and fails for 127.0.0.1.
 */
final class TestCertificate {

  private static final char[] PASSWORD = "test-only-0001".toCharArray();
  private static TestCertificate localhost;

  /** The certificate as a connection's CA certificate. */
  final String pem;
  /** A server that presents it. */
  final SSLContext server;

  private TestCertificate(String pem, SSLContext server) {
    this.pem = pem;
    this.server = server;
  }

  static synchronized TestCertificate localhost() {
    if (localhost == null) {
      try {
        Path dir = Files.createTempDirectory("gw-sql-cert-");
        Path store = dir.resolve("server.p12");
        Path pem = dir.resolve("server.pem");
        // Deleted in reverse order at exit: the files, then their folder.
        dir.toFile().deleteOnExit();
        store.toFile().deleteOnExit();
        pem.toFile().deleteOnExit();
        String pw = new String(PASSWORD);
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost", "-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12",
            "-storepass", pw, "-keypass", pw, "-noprompt");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(), "-storetype", "PKCS12",
            "-storepass", pw, "-file", pem.toString());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
          keys.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        localhost = new TestCertificate(Files.readString(pem, StandardCharsets.US_ASCII), context);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
    return localhost;
  }

  private static void keytool(String... args) throws IOException {
    List<String> command = new java.util.ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
      if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
        throw new IOException("keytool " + String.join(" ", args) + " failed: " + out);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }
}
