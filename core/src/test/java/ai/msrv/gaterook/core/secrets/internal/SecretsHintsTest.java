package ai.msrv.gaterook.core.secrets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * The native-image reflection the secrets module needs (found by the native smoke: without the protobuf entry the
 * binary refuses to start at the first binary keyset parse).
 */
class SecretsHintsTest {

  @Test
  void registersWhatTinkAndProtobufLookUpReflectively() throws Exception {
    RuntimeHints hints = new RuntimeHints();
    new SecretsConfiguration.Hints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.reflection()
        .onMethodInvocation(ExtensionRegistry.class.getMethod("getEmptyRegistry"))).accepts(hints);
    assertThat(RuntimeHintsPredicates.reflection()
        .onMethodInvocation(ExtensionRegistry.class.getMethod("newInstance"))).accepts(hints);
    assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of("org.conscrypt.Conscrypt"))).accepts(hints);
    assertThat(RuntimeHintsPredicates.reflection()
        .onType(TypeReference.of("ai.msrv.gaterook.core.secrets.LocalSecretValue$Refuse"))).accepts(hints);
  }
}
