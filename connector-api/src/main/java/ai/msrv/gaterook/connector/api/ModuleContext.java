package ai.msrv.gaterook.connector.api;

/** What a {@link ConnectorModule} may know when it builds its connectors: its own identity, nothing of the gateway. */
public interface ModuleContext {

  /** The module's id as its manifest declares it ({@code sql-oracle}). */
  String moduleId();

  /** The module's version as its manifest declares it ({@code 1.0.0}). */
  String moduleVersion();
}
