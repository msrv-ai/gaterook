package gaterook.plugins.duckdb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help for the DuckDB form: the DuckDB files of the typed folder, and for the chosen file the subfolders next to it
 * that hold data files, as data folders; the database's own folder never is one. Nothing is filled in.
 */
class DuckDbSuggestionsTest {

  private final DuckDbConnector connector = new DuckDbConnector(TestCalls.manifest());

  @TempDir
  Path dir;

  private Suggestions suggest(String file, Suggestions.Scope scope) throws Exception {
    ConnectionConfig cfg = TestCalls.config(Env.DEV, file, List.of());
    return connector.suggest(cfg, new TestCalls.NoSecrets(), new TestCalls.Context(TestCalls.workDir(
        dir.resolve("gaterook-home")), false), scope);
  }

  @Test
  void theDatabasesOfTheFolderAndTheDataSubfoldersNextToTheChosenOneAreOffered() throws Exception {
    Path project = dir.resolve("analytics");
    Path warehouse = TestCalls.database(project.resolve("warehouse.duckdb"), "CREATE TABLE t (x INT)");
    TestCalls.database(project.resolve("archive.duckdb"), "CREATE TABLE a (x INT)");
    Files.writeString(project.resolve("notes.db"), "not a DuckDB database");
    Path data = Files.createDirectories(project.resolve("data"));
    Files.writeString(data.resolve("sales.parquet"), "PAR1");
    Files.writeString(data.resolve("users.csv"), "id,name\n");
    Files.writeString(data.resolve("events.csv"), "id\n");
    Files.createDirectories(project.resolve("empty"));
    Files.writeString(project.resolve("loose.csv"), "a,b\n");

    for (Suggestions.Scope scope : Suggestions.Scope.values()) {
      Suggestions s = suggest(warehouse.toString(), scope);

      Suggestions.Field file = s.fields().get(DuckDbSuggestions.FILE);
      assertThat(file.value()).as(scope.name()).isNull();
      assertThat(file.options()).extracting(Option::value).containsExactly(
          DuckDbSuggestions.shown(project.resolve("archive.duckdb")), DuckDbSuggestions.shown(warehouse));
      // The chips name the files of the typed folder; the whole path is what the field gets.
      assertThat(file.options()).extracting(Option::label).containsExactly("archive.duckdb", "warehouse.duckdb");
      Suggestions.Field folders = s.fields().get(DuckDbSuggestions.FOLDERS);
      assertThat(folders.value()).isNull();
      assertThat(folders.options()).singleElement().satisfies(o -> {
        assertThat(o.value()).isEqualTo(DuckDbSuggestions.shown(data));
        assertThat(o.label()).isEqualTo(data.getFileName() + "/");
        assertThat(o.detail()).isEqualTo("1 Parquet file, 2 CSV files");
      });
      assertThat(s.notes()).containsExactly("Data files next to warehouse.duckdb (1 CSV file) can't be read from its "
          + "folder, which holds the database: move them into a subfolder and choose that as a data folder.");
    }
  }

  @Test
  void aTypedFolderOffersItsDatabases() throws Exception {
    Path project = dir.resolve("p");
    TestCalls.database(project.resolve("warehouse.duckdb"), "CREATE TABLE t (x INT)");

    Suggestions s = suggest(project.toString(), Suggestions.Scope.LOCAL);

    assertThat(s.fields().get(DuckDbSuggestions.FILE).options()).hasSize(1);
    assertThat(s.fields()).doesNotContainKey(DuckDbSuggestions.FOLDERS);
    assertThat(s.notes()).isEmpty();
  }

  @Test
  void whatsWrongWithTheTypedFileIsANote() throws Exception {
    Path project = Files.createDirectories(dir.resolve("q"));
    Path text = Files.writeString(project.resolve("notes.duckdb"), "not a DuckDB database");

    assertThat(suggest(text.toString(), Suggestions.Scope.LOCAL).notes())
        .containsExactly(text + " isn't a DuckDB database.");
    assertThat(suggest(project.resolve("gone.duckdb").toString(), Suggestions.Scope.LOCAL).notes())
        .singleElement().satisfies(n -> assertThat(n).endsWith("gone.duckdb doesn't exist on this computer."));
    // A path that isn't one yet says nothing.
    assertThat(suggest("relative/warehouse.duckdb", Suggestions.Scope.LOCAL).isEmpty()).isTrue();
  }

  @Test
  void gaterooksOwnFolderOffersNothing() throws Exception {
    Path home = dir.resolve("gaterook-home");
    TestCalls.database(home.resolve("copy.duckdb"), "CREATE TABLE t (x INT)");

    Suggestions s = suggest(home.toString(), Suggestions.Scope.LOCAL);

    assertThat(s.fields()).doesNotContainKey(DuckDbSuggestions.FILE);
  }
}
