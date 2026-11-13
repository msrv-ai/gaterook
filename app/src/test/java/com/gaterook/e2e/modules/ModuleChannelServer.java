package com.gaterook.e2e.modules;

import com.gaterook.e2e.platform.CoreProcess;
import com.gaterook.plugin.protocol.PluginManifest;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import tools.jackson.databind.node.ObjectNode;

/**
 * A module channel that lists one package, as a release's channel does: a signed index and the package over HTTPS
 * (MockWebServer), described by the package's own manifest. The package can be held back, so a test sees what a core
 * does while a module is on its way.
 */
final class ModuleChannelServer implements AutoCloseable {

  private final MockWebServer server = new MockWebServer();
  private final HeldCertificate ca = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Modules e2e CA").build();
  private final KeyPair key;
  private final AtomicInteger downloads = new AtomicInteger();
  private volatile CountDownLatch held = new CountDownLatch(0);

  private ModuleChannelServer(KeyPair key) {
    this.key = key;
  }

  /** Starts a channel that lists {@code jar}, whose manifest is {@code module}, as the file {@code file}. */
  static ModuleChannelServer serving(PluginManifest module, byte[] jar, String file) throws Exception {
    ModuleChannelServer channel = new ModuleChannelServer(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    byte[] index = index(module, jar, file);
    Map<String, byte[]> files = Map.of("/modules/" + file, jar, "/modules/index.json", index,
        "/modules/index.json.sig", channel.signature(index));
    HeldCertificate leaf = new HeldCertificate.Builder().commonName("127.0.0.1")
        .addSubjectAlternativeName("127.0.0.1").signedBy(channel.ca).build();
    channel.server.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf).build().sslSocketFactory());
    channel.server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
        byte[] body = files.get(request.getTarget());
        if (body != null && request.getTarget().endsWith(".jar")) {
          channel.downloads.incrementAndGet();
          channel.held.await(60, TimeUnit.SECONDS);
        }
        return body == null ? new MockResponse.Builder().code(404).build()
            : new MockResponse.Builder().code(200).body(new okio.Buffer().write(body)).build();
      }
    });
    channel.server.start(InetAddress.getByName("127.0.0.1"), 0);
    return channel;
  }

  /** The flags that make a core read this channel; its CA is written into {@code home}. */
  List<String> flags(Path home) throws Exception {
    Path caFile = home.resolve("channel-ca.pem");
    Files.writeString(caFile, ca.certificatePem());
    byte[] spki = key.getPublic().getEncoded();
    return List.of("-Dgateway.modules.channel-url=https://127.0.0.1:" + server.getPort() + "/modules/",
        "-Dgateway.modules.channel-keys=e2e-modules=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            Arrays.copyOfRange(spki, spki.length - 32, spki.length)),
        "-Dgateway.test.catalog.ca=" + caFile);
  }

  /** Holds the package back from now on: a download waits until {@link #release}. */
  void hold() {
    held = new CountDownLatch(1);
  }

  void release() {
    held.countDown();
  }

  /** How often the package was asked for. */
  int downloads() {
    return downloads.get();
  }

  @Override
  public void close() {
    release();
    server.close();
  }

  /** The channel's list with the package as its own manifest describes it. */
  private static byte[] index(PluginManifest module, byte[] jar, String file) throws Exception {
    ObjectNode index = CoreProcess.JSON.createObjectNode();
    index.put("schema", 1).put("sequence", 1);
    index.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    index.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode entry = index.putArray("entries").addObject();
    entry.put("kind", "module").put("id", module.id()).put("version", module.version()).put("name", module.name())
        .put("description", module.description());
    entry.set("requires", module.raw().path("requires"));
    entry.putObject("artifact").put("url", file).put("sha256", HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(jar))).put("size", jar.length);
    for (PluginManifest.Type type : module.types()) {
      ObjectNode t = entry.withArray("types").addObject().put("id", type.id()).put("name", type.name())
          .put("icon", type.icon()).put("group", type.group()).put("description", type.description());
      type.tools().forEach(tool -> t.withArray("tools").add(tool.name()));
    }
    index.putArray("revoked");
    return CoreProcess.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
  }

  private byte[] signature(byte[] index) throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(key.getPrivate());
    s.update(index);
    return ("{\"keyId\":\"e2e-modules\",\"alg\":\"Ed25519\",\"sig\":\"" + Base64.getEncoder().encodeToString(s.sign())
        + "\"}").getBytes(StandardCharsets.UTF_8);
  }
}
