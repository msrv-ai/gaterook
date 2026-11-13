package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * What is published, through the assembled core (docs/connector-delivery.md): a core that reads GateRook's list of
 * connector modules on GitHub lists every released module, installs each of them from its release, and then has on
 * its disk exactly the package that {@code modules/modules.lock.json} records as released. A core that trusts
 * another key takes nothing from the same address.
 *
 * <p>It needs the network and checks what is published now, never what a build makes, so it runs only when asked:
 * {@code -Dgateway.e2e.officialLists=true}.
 */
class PublishedModulesCoreProcessIT {

  private static final String LIST = "https://raw.githubusercontent.com/gaterook/gaterook-plugins/main/modules/";

  @BeforeAll
  static void asked() {
    assumeTrue(BuiltModule.OFFICIAL, "Checks the published list: run with -Dgateway.e2e.officialLists=true");
  }

  @Test
  void everyReleasedModuleInstallsFromTheListAsThePackageThatWasReleased(@TempDir Path home) throws Exception {
    JsonNode released = CoreProcess.JSON.readTree(Files.readAllBytes(Path.of(System.getProperty("gateway.modulesDir",
        "../modules"), "modules.lock.json"))).path("modules");
    CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), BuiltModule.flags(home, null)).bootstrap();
    try {
      core.untilReady(Duration.ofSeconds(90));
      CoreClient api = new CoreClient(core);
      JsonNode listed = api.get("/api/modules");
      assertThat(listed.at("/channel/configured").asBoolean()).isTrue();
      assertThat(listed.at("/channel/problem").isNull()).as("the list's signature, sequence and expiry: " + listed
          .path("channel")).isTrue();
      Map<String, String> versions = new LinkedHashMap<>();
      listed.path("modules").forEach(m -> versions.put(m.path("id").asString(), m.path("version").asString()));
      List<String> releasedIds = new ArrayList<>();
      released.properties().forEach(module -> releasedIds.add(module.getKey()));
      assertThat(versions.keySet()).as("every module that was released is listed")
          .containsExactlyInAnyOrderElementsOf(releasedIds);

      // Each is asked for by its id, as a card in Add connection asks for it; they download side by side.
      for (Map.Entry<String, String> module : versions.entrySet()) {
        assertThat(released.path(module.getKey()).has(module.getValue())).as(module + " is a released version")
            .isTrue();
        api.json(api.admin("POST", "/api/modules/" + module.getKey() + "/install", "{}"), 200);
      }
      for (Map.Entry<String, String> module : versions.entrySet()) {
        JsonNode ready = api.awaitModule(module.getKey(), "ready", Duration.ofMinutes(5));
        assertThat(ready.path("installed").asBoolean()).as(ready.toString()).isTrue();
        assertThat(ready.path("signed").asBoolean()).as(ready.toString()).isTrue();
        Path jar = home.resolve("plugins").resolve(module.getKey()).resolve(module.getValue()).resolve("plugin.jar");
        JsonNode pin = released.path(module.getKey()).path(module.getValue());
        assertThat(Files.size(jar)).as(module + ": the size that was released").isEqualTo(pin.path("size").asLong());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))))
            .as(module + ": the package that was released").isEqualTo(pin.path("sha256").asString());
      }
    } finally {
      core.close();
    }
  }

  @Test
  void aCoreThatTrustsAnotherKeyTakesNothingFromTheSameList(@TempDir Path home) throws Exception {
    byte[] spki = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
    String otherKey = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(spki, spki.length - 32,
        spki.length));
    // The same address and the same key id as a release reads, with other key bytes: an address given explicitly
    // comes before the official one.
    List<String> flags = new ArrayList<>(BuiltModule.flags(home, null));
    flags.add("-Dgateway.modules.channel-url=" + LIST);
    flags.add("-Dgateway.modules.channel-keys=modules-2026-1=" + otherKey);
    CoreProcess core = CoreProcess.start(home, CoreProcess.freePort(), flags).bootstrap();
    try {
      core.untilReady(Duration.ofSeconds(90));
      CoreClient api = new CoreClient(core);
      JsonNode listed = api.get("/api/modules");
      assertThat(listed.at("/channel/configured").asBoolean()).isTrue();
      assertThat(listed.at("/channel/problem").asString("")).as(listed.path("channel").toString())
          .contains("signature doesn't match");
      assertThat(listed.path("modules")).as("nothing of a list that didn't verify is offered").isEmpty();

      CoreClient.Answer refused = api.admin("POST", "/api/modules/h2/install", "{}");
      assertThat(refused.status()).as(refused.body()).isIn(404, 409);
      assertThat(Files.exists(home.resolve("plugins").resolve("h2"))).as("nothing was downloaded").isFalse();
    } finally {
      core.close();
    }
  }
}
