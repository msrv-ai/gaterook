/**
 * Audit module (ARCHITECTURE.md §2.2). tool_call and setup_change (append-only, hash chain), AuditWriter (fail-
 * closed), the implementation of SetupChangeRecorder, the Activity query language, trace and TraceSummarizer, export
 * and retention (configured through SettingStore).
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal. {@code secrets} since K5: an export destination's credentials (the SIEM's client key, the
 * AWS secret key, the GCP service account key) are write-only secrets of the gateway, opened for one export.
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = {"platform", "secrets"})
package ai.msrv.gaterook.core.audit;

import org.springframework.modulith.ApplicationModule;
