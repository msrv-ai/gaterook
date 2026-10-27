package ai.msrv.gaterook.core.platform;

/**
 * Facts fixed when the binary was built (ARCHITECTURE.md §2.4). The release native image initializes this class at
 * build time ({@code --initialize-at-build-time=ai.msrv.gaterook.core.platform.BuildFlags} with
 * {@code -Dgateway.release=true}), so a release can't be talked into its development relaxations at run time: no
 * {@code gateway.dev}, no {@code GW_DEV_INTERNAL_SECRET}, no {@code localhost:4200} origin, no demo data in a real
 * home. It is the only class of ours initialized at build time; it holds no secret.
 *
 * <p>On the JVM the flag comes from the {@code gateway.release} system property, which the JVM fallback artifact
 * sets in its launcher.
 */
public final class BuildFlags {

  /** Whether this is a release build. */
  public static final boolean RELEASE = Boolean.getBoolean("gateway.release");

  private BuildFlags() {}
}
