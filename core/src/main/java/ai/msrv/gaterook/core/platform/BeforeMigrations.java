package ai.msrv.gaterook.core.platform;

/**
 * SPI: work of a start-up step that must happen before the database migrates and needs no database, reported as its
 * own progress line ({@code {"step":"master-key","state":"running|done"}}). The {@code secrets} module reads the
 * master keyset here, so the status screen shows "Master key" before "Database migrations" as the kit does; what needs
 * the database (does the keyset open the stored secrets?) runs after the migrations.
 *
 * <p>Beans run in {@code @Order}; an exception stops the start with the step marked failed.
 */
public interface BeforeMigrations {

  /** The progress step id: {@code master-key}. */
  String step();

  void run();
}
