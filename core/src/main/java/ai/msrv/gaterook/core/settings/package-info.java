/**
 * Settings module (ARCHITECTURE.md §2.2). Typed sections of Settings on top of SettingStore, CoreInfo, Local API,
 * Approval rules, Appearance, General and DatabaseBackupJob.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Settings", allowedDependencies = {"platform"})
package ai.msrv.gaterook.core.settings;

import org.springframework.modulith.ApplicationModule;
