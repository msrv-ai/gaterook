package ai.msrv.gaterook.connectors.kubernetes.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.kubernetes.KubernetesConnector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KubernetesConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(KubernetesConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersTheConnector() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KubernetesConnectorsAutoConfiguration.class))
        .run(ctx -> assertThat(ctx.getBeansOfType(Connector.class).values())
            .extracting(c -> c.manifest().id())
            .containsExactly("kubernetes"));
  }

  @Test
  void schemasAreNativeImageResources() {
    RuntimeHints hints = new RuntimeHints();
    new KubernetesHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/kubernetes/k8s_get.input.schema.json"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/kubernetes/config.schema.json"))
        .accepts(hints);
  }

  /**
   * The connector reads every answer as a JSON tree and builds every request body itself, so no class of the
   * Fabric8 model ({@code io.fabric8.kubernetes.api.model}) is (de)serialized — the native image needs no reflection
   * metadata for them (ARCHITECTURE.md §1, A8). A model class appearing in the connector's bytecode would bring that
   * back unnoticed; this keeps it out.
   */
  @Test
  void noModelClassOfFabric8IsUsed() throws Exception {
    List<Path> classFiles;
    try (Stream<Path> files = Files.walk(classesDir())) {
      classFiles = files.filter(f -> f.toString().endsWith(".class")).toList();
    }
    // A jar on the class path instead of target/classes would make the check pass on nothing.
    assertThat(classFiles).hasSizeGreaterThan(20);
    assertThat(classFiles.stream().filter(KubernetesConnectorsAutoConfigurationTest::referencesModel)).isEmpty();
  }

  private static boolean referencesModel(Path classFile) {
    try {
      return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1)
          .contains("io/fabric8/kubernetes/api/model");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path classesDir() throws URISyntaxException {
    return Path.of(KubernetesConnector.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }
}
