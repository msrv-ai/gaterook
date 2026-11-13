package com.gaterook.core.connectors;

/**
 * The connector of an installed package as the core runs it (a plugin, or a connector module), which says whether
 * GateRook signed its build: a plugin's by the catalog key, a module's by the signed list of modules that pins its
 * package. A signed build is code GateRook reviewed, the annotations of its tools included; an unsigned one
 * ("Community code, not reviewed", or a person's own module from a file) only declares them.
 */
public interface PluginBuild {

  /** Whether GateRook signed the build this connector runs. */
  boolean signed();

  /**
   * What a person's confirmation of this build's tools is bound to besides their definitions, when that isn't the
   * connector's version: the version and the package of a connector module that GateRook didn't sign, so that another
   * build of it starts unconfirmed. {@code null} for a plugin, whose version it is.
   */
  default String provenance() {
    return null;
  }
}
