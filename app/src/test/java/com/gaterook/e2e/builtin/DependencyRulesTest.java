package com.gaterook.e2e.builtin;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Code-level module rules of ARCHITECTURE.md §2.1 over the assembled classpath; the Maven enforcer covers the
 * artifact level, Spring Modulith the modules inside the core.
 */
class DependencyRulesTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importProductionClasses() {
    classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        // Code that Spring AOT generates into target/classes on a -Pnative build (GatewayApplication__Bean…,
        // *__BeanDefinitions, *Impl__AotRepository) wires the whole assembly by design; none of ours has "__".
        .withImportOption(location -> !location.contains("__"))
        .importPackages("com.gaterook");
  }

  @Test
  void connectorApiDependsOnlyOnTheJdkAndJackson() {
    classes().that().resideInAPackage("com.gaterook.connector.api..")
        .should().onlyDependOnClassesThat().resideInAnyPackage("com.gaterook.connector.api..", "java..",
            "tools.jackson..")
        .check(classes);
  }

  @Test
  void connectorsNeverReachIntoTheCore() {
    noClasses().that().resideInAPackage("com.gaterook.connectors..")
        .should().dependOnClassesThat().resideInAPackage("com.gaterook.core..")
        .check(classes);
  }

  @Test
  void connectorsUseSpringOnlyInTheirBootPackage() {
    noClasses().that().resideInAPackage("com.gaterook.connectors..")
        .and().resideOutsideOfPackage("com.gaterook.connectors.*.boot..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework..")
        .check(classes);
  }

  @Test
  void theCoreUsesConnectorsOnlyThroughTheSpiAndTheSharedLibraries() {
    noClasses().that().resideInAPackage("com.gaterook.core..")
        .should().dependOnClassesThat(resideInAPackage("com.gaterook.connectors..").and(not(
            resideInAnyPackage("com.gaterook.connectors.support..", "com.gaterook.connectors.cloudauth.."))))
        .check(classes);
  }
}
