package ai.msrv.gaterook.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** What a connector hands the setup form: options per field, a value to fill in, notes. */
class SuggestionsTest {

  @Test
  void collectsFieldsInOrderAndSkipsEmptyOnes() {
    Suggestions s = Suggestions.builder()
        .value("/context", "your kubeconfig", "kind-dev",
            List.of(Suggestions.Option.of("kind-dev", "https://127.0.0.1:6443"), Suggestions.Option.of("prod")))
        .options("/namespace", "the cluster", List.of())
        .options("/region", "~/.aws/config", List.of(Suggestions.Option.of("eu-central-1")))
        .note("Found 2 contexts.")
        .note("Found 2 contexts.")
        .note(" ")
        .build();

    assertThat(s.fields()).containsOnlyKeys("/context", "/region");
    assertThat(s.fields().keySet()).containsExactly("/context", "/region");
    assertThat(s.fields().get("/context").value()).isEqualTo("kind-dev");
    assertThat(s.fields().get("/context").options()).extracting(Suggestions.Option::detail)
        .containsExactly("https://127.0.0.1:6443", null);
    assertThat(s.notes()).containsExactly("Found 2 contexts.");
    assertThat(s.isEmpty()).isFalse();
  }

  @Test
  void aLongListIsCutAndMarkedIncomplete() {
    List<Suggestions.Option> many = IntStream.range(0, Suggestions.MAX_OPTIONS + 5)
        .mapToObj(i -> Suggestions.Option.of("ns-" + i)).toList();

    Suggestions.Field f = Suggestions.Field.of("the cluster", many);

    assertThat(f.options()).hasSize(Suggestions.MAX_OPTIONS);
    assertThat(f.complete()).isFalse();
    assertThat(Suggestions.Field.of("the cluster", many.subList(0, 3)).complete()).isTrue();
  }

  @Test
  void aServiceAnswerReplacesTheLocalOneForTheSameField() {
    Suggestions local = Suggestions.builder()
        .value("/namespace", "your kubeconfig", "app-dev", List.of(Suggestions.Option.of("app-dev")))
        .options("/context", "your kubeconfig", List.of(Suggestions.Option.of("kind-dev")))
        .note("From your kubeconfig.")
        .build();
    Suggestions service = Suggestions.builder()
        .options("/namespace", "the cluster", List.of(Suggestions.Option.of("app-dev"), Suggestions.Option.of("kube-system")))
        .note("Signed in to the cluster.")
        .build();

    Suggestions merged = local.merge(service);

    assertThat(merged.fields().get("/namespace").source()).isEqualTo("the cluster");
    assertThat(merged.fields().get("/context").options()).hasSize(1);
    assertThat(merged.notes()).containsExactly("From your kubeconfig.", "Signed in to the cluster.");
    assertThat(local.merge(Suggestions.none())).isSameAs(local);
    assertThat(Suggestions.none().isEmpty()).isTrue();
  }
}
