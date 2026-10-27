package ai.msrv.gaterook.core.platform.db;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.boot.archive.scan.spi.ClassDescriptor;
import org.hibernate.boot.archive.scan.spi.MappingFileDescriptor;
import org.hibernate.boot.archive.scan.spi.PackageDescriptor;
import org.hibernate.boot.archive.scan.spi.ScanEnvironment;
import org.hibernate.boot.archive.scan.spi.ScanOptions;
import org.hibernate.boot.archive.scan.spi.ScanParameters;
import org.hibernate.boot.archive.scan.spi.ScanResult;
import org.hibernate.boot.archive.scan.spi.Scanner;
import org.hibernate.boot.archive.spi.InputStreamAccess;

/**
 * Hibernate's scanner for the gateway's persistence unit: the managed types Spring found at build time (AOT
 * {@code PersistenceManagedTypes}) are the whole unit, so nothing is scanned and nothing is looked up as a resource.
 *
 * <p>Without it Hibernate resolves every listed name it didn't scan by asking the class loader for
 * {@code <name>.class} and then {@code <name>/package-info.class}. A native image has no class files as resources, so
 * both lookups fail for each of the 33 entities, and each failure walks every resource bundle of the image: about a
 * tenth of the core's start-up (ARCHITECTURE.md §10, M1.7). Here a listed name is a class when it loads and a package
 * otherwise, which is what Hibernate concludes too, only by the long way.
 */
final class ListedClassesScanner implements Scanner {

  private final ClassLoader classLoader;

  ListedClassesScanner(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  @Override
  public ScanResult scan(ScanEnvironment environment, ScanOptions options, ScanParameters parameters) {
    List<String> listed = environment.getExplicitlyListedClassNames();
    Set<ClassDescriptor> classes = new HashSet<>();
    Set<PackageDescriptor> packages = new HashSet<>();
    if (listed != null) {
      for (String name : listed) {
        if (loads(name)) {
          classes.add(new Listed(name));
        } else {
          packages.add(new ListedPackage(name));
        }
      }
    }
    Set<ClassDescriptor> located = Set.copyOf(classes);
    Set<PackageDescriptor> locatedPackages = Set.copyOf(packages);
    return new ScanResult() {
      @Override
      public Set<PackageDescriptor> getLocatedPackages() {
        return locatedPackages;
      }

      @Override
      public Set<ClassDescriptor> getLocatedClasses() {
        return located;
      }

      @Override
      public Set<MappingFileDescriptor> getLocatedMappingFiles() {
        return Set.of();
      }
    };
  }

  /** An entity class: Hibernate only needs its name (no class file is read). */
  private record Listed(String name) implements ClassDescriptor {
    @Override
    public String getName() {
      return name;
    }

    @Override
    public Categorization getCategorization() {
      return Categorization.MODEL;
    }

    @Override
    public InputStreamAccess getStreamAccess() {
      return null;
    }
  }

  private record ListedPackage(String name) implements PackageDescriptor {
    @Override
    public String getName() {
      return name;
    }

    @Override
    public InputStreamAccess getStreamAccess() {
      return null;
    }
  }

  private boolean loads(String name) {
    try {
      Class.forName(name, false, classLoader);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }
}
