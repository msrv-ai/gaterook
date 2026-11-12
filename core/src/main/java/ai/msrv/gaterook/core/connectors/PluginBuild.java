package ai.msrv.gaterook.core.connectors;

/**
 * The connector of an installed plugin as the core runs it, which says whether its build came with a valid signature
 * of GateRook's catalog key. A signed build is code GateRook reviewed, the annotations of its tools included; an
 * unsigned one ("Community code, not reviewed") only declares them.
 */
public interface PluginBuild {

  /** Whether GateRook's catalog key signed the build this connector runs. */
  boolean signed();
}
