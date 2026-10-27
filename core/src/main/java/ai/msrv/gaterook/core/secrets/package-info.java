/**
 * Secrets module (ARCHITECTURE.md §2.2). Tink envelope encryption, MasterKeyService (and the locked mode), external
 * stores, ScopedSecretResolver (feeding platform.Taint), LeaseManager, short-lived credentials and the
 * implementation of HmacKeys.
 *
 * <p>Allowed dependencies are those of the architecture's module table; the public API is this package, every
 * subpackage is internal.
 */
@ApplicationModule(displayName = "Secrets", allowedDependencies = {"platform", "settings"})
package ai.msrv.gaterook.core.secrets;

import org.springframework.modulith.ApplicationModule;
