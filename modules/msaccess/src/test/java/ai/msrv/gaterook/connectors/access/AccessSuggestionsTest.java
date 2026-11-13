package ai.msrv.gaterook.connectors.access;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Help for the Access form: the Access databases of the typed folder, and why the typed file can't be used. */
class AccessSuggestionsTest {

  private final AccessConnector connector = new AccessConnector(TestCalls.definition());

  @TempDir
  Path dir;

  private Suggestions suggest(String file, Suggestions.Scope scope) throws Exception {
    return connector.suggest(TestCalls.config(Env.DEV, file), new TestCalls.NoSecrets(),
        new TestCalls.Context(TestCalls.workDir(dir.resolve("gaterook-home")), false), scope);
  }

  @Test
  void theAccessDatabasesOfTheFolderAreOfferedInEitherScope() throws Exception {
    Path docs = dir.resolve("docs");
    Path orders = TestCalls.database(docs.resolve("orders.accdb"));
    Files.writeString(docs.resolve("notes.mdb"), "not an Access database");
    Files.writeString(docs.resolve("readme.txt"), "hello");

    for (Suggestions.Scope scope : Suggestions.Scope.values()) {
      Suggestions s = suggest(docs.resolve("ord").toString(), scope);

      Suggestions.Field file = s.fields().get(AccessSuggestions.FILE);
      assertThat(file.value()).as(scope.name()).isNull();
      assertThat(file.options()).singleElement().satisfies(o -> {
        assertThat(o.value()).isEqualTo(AccessSuggestions.shown(orders));
        assertThat(o.label()).isEqualTo(orders.getFileName().toString());
        assertThat(o.detail()).isEqualTo("ACE (.accdb)");
      });
      assertThat(s.notes()).singleElement().satisfies(n -> assertThat(n).endsWith("doesn't exist on this "
          + "computer: choose a database file of its folder."));
    }
    assertThat(suggest(docs.toString(), Suggestions.Scope.LOCAL).fields().get(AccessSuggestions.FILE).options())
        .extracting(Option::value).containsExactly(AccessSuggestions.shown(orders));
  }

  @Test
  void whatsWrongWithTheTypedFileIsANote() throws Exception {
    Path notes = Files.writeString(Files.createDirectories(dir.resolve("q")).resolve("notes.mdb"), "not one");

    assertThat(suggest(notes.toString(), Suggestions.Scope.LOCAL).notes())
        .containsExactly(notes + " isn't an Access database (.accdb or .mdb).");
    assertThat(suggest("relative/orders.accdb", Suggestions.Scope.LOCAL).isEmpty()).isTrue();
  }
}
