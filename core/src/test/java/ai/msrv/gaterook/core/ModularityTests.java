package ai.msrv.gaterook.core;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Module boundaries of the core (ARCHITECTURE.md §2.2). A CI gate since step M1.1: parallel steps start only on a
 * green {@code verify()}, so a forbidden dependency fails the step that introduces it.
 */
class ModularityTests {

  /** The module table of ARCHITECTURE.md §2.2, in its order. */
  private static final List<String> MODULES = List.of("platform", "settings", "secrets", "audit", "connectors",
      "connections", "templates", "mcpservers", "policy", "endpoints", "invocation", "approvals", "mcp", "playground",
      "clients", "tunnel", "oauth", "notifications", "insights", "admin");

  private static final ApplicationModules modules = ApplicationModules.of(GatewayApplication.class);

  @Test
  void codeRespectsTheDeclaredModuleDependencies() {
    modules.verify();
  }

  @Test
  void everyModuleOfTheArchitectureExists() {
    assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
        .containsExactlyInAnyOrderElementsOf(MODULES);
  }

  @Test
  void platformIsSharedAndDependsOnNoModule() {
    ApplicationModule platform = modules.getModuleByName("platform").orElseThrow();

    assertThat(modules.getSharedModules()).containsExactly(platform);
    assertThat(platform.getDirectDependencies(modules).isEmpty()).isTrue();
    assertThat(declaredDependencies().get("platform")).isEmpty();
  }

  @Test
  void theMasterKeyEventIsVisibleToTheModulesThatReSign() {
    // RowMac (platform), the audit head and the owners of MAC'd rows react to MasterKeyChanged (a new key leaves
    // their rows unverified); only secrets publishes it, and most of them may not depend on secrets.
    ApplicationModule platform = modules.getModuleByName("platform").orElseThrow();

    assertThat(platform.contains(MasterKeyChanged.class)).isTrue();
    assertThat(platform.contains(HmacKeys.class)).isTrue();
  }

  @Test
  void onlyTheApprovalExecutorRunsApprovedCalls() {
    // §9 "Approval cannot be forged through the protocol": the agent's request never reaches the approved path; only
    // the executor, after its re-checks, hands a stored call to the invoker, and only the invoker makes the context
    // that tells a connector a person approved.
    com.tngtech.archunit.core.domain.JavaClasses classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
        .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("ai.msrv.gaterook.core");
    com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that()
        .doNotHaveFullyQualifiedName("ai.msrv.gaterook.core.approvals.internal.ApprovalExecutor")
        .should().callMethod(ai.msrv.gaterook.core.invocation.ConnectorInvoker.class, "executeApproved",
            ai.msrv.gaterook.core.invocation.ConnectorInvoker.Request.class,
            ai.msrv.gaterook.core.invocation.ConnectorInvoker.Approval.class)
        .check(classes);
    com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that()
        .doNotHaveFullyQualifiedName(ai.msrv.gaterook.core.invocation.ConnectorInvoker.class.getName())
        .should().callConstructorWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
            com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName("ApprovedContext"))))
        .check(classes);
  }

  @Test
  void nothingThatWaitsOrCallsAResourceHoldsATransaction() {
    // §3 and §5 step 11: the invoker does I/O against a resource for up to 30 s and the approval path waits for a
    // person; neither may hold the single write connection (or a read one) while it does. Short transactions inside
    // them use TransactionTemplate; a @Transactional anywhere on these classes would wrap the whole wait.
    com.tngtech.archunit.core.domain.JavaClasses classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
        .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("ai.msrv.gaterook.core");
    com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> waiting =
        com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage("ai.msrv.gaterook.core.mcp..",
            "ai.msrv.gaterook.core.invocation..")
            .or(com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf(
                ai.msrv.gaterook.core.approvals.ApprovalService.class,
                ai.msrv.gaterook.core.approvals.ApprovalStatusTool.class,
                ai.msrv.gaterook.core.approvals.internal.ApprovalExecutor.class,
                ai.msrv.gaterook.core.approvals.internal.ApprovalWaiters.class));
    for (String transactional : List.of("org.springframework.transaction.annotation.Transactional",
        "jakarta.transaction.Transactional")) {
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that(waiting)
          .should().beMetaAnnotatedWith(transactional).check(classes);
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods().that().areDeclaredInClassesThat(waiting)
          .should().beMetaAnnotatedWith(transactional).check(classes);
    }
    // The rule names classes that exist: a rename must not empty it.
    assertThat(classes.contain(ai.msrv.gaterook.core.invocation.ConnectorInvoker.class)).isTrue();
    assertThat(classes.stream().filter(waiting::test).map(com.tngtech.archunit.core.domain.JavaClass::getSimpleName))
        .contains("ConnectorInvoker", "ApprovalDelivery", "ApprovalStatusTool", "ApprovalService",
            "ApprovalExecutor", "ToolCallHandler");
  }

  @Test
  void declaredDependenciesFormTheArchitecturesGraph() {
    Map<String, Set<String>> declared = declaredDependencies();

    declared.forEach((module, deps) -> {
      assertThat(MODULES).as("dependencies of " + module).containsAll(deps);
      assertThat(deps).as(module + " depends on itself").doesNotContain(module);
      if (!module.equals("platform")) {
        assertThat(deps).as(module + " may use the shared platform").contains("platform");
      }
    });
    // Nothing below mcp depends on it: only the playground calls through it and admin serves the UI.
    assertThat(declared.entrySet().stream().filter(e -> e.getValue().contains("mcp")).map(Map.Entry::getKey))
        .containsExactlyInAnyOrder("playground", "admin");
    // admin is the top: nobody depends on it.
    assertThat(declared.values()).noneMatch(deps -> deps.contains("admin"));
    assertThat(cycleIn(declared)).as("a cycle in allowedDependencies").isEmpty();
  }

  /** The {@code allowedDependencies} each module's package-info declares. */
  private static Map<String, Set<String>> declaredDependencies() {
    Map<String, Set<String>> declared = new HashMap<>();
    for (String module : MODULES) {
      try {
        String name = GatewayApplication.class.getPackageName() + "." + module + ".package-info";
        Class<?> packageInfo = Class.forName(name);
        var annotation = packageInfo.getAnnotation(org.springframework.modulith.ApplicationModule.class);
        assertThat(annotation).as(module + " package-info has @ApplicationModule").isNotNull();
        declared.put(module, Set.copyOf(Arrays.asList(annotation.allowedDependencies())));
      } catch (ClassNotFoundException e) {
        throw new AssertionError(module + " has no package-info", e);
      }
    }
    return declared;
  }

  /** Depth-first search for a cycle; returns its path or an empty list. */
  private static List<String> cycleIn(Map<String, Set<String>> graph) {
    Set<String> done = new HashSet<>();
    for (String start : graph.keySet()) {
      Deque<String> path = new ArrayDeque<>();
      List<String> cycle = visit(start, graph, path, done);
      if (!cycle.isEmpty()) {
        return cycle;
      }
    }
    return List.of();
  }

  private static List<String> visit(String node, Map<String, Set<String>> graph, Deque<String> path, Set<String> done) {
    if (path.contains(node)) {
      return List.copyOf(path);
    }
    if (!done.add(node)) {
      return List.of();
    }
    path.push(node);
    for (String next : graph.getOrDefault(node, Set.of())) {
      List<String> cycle = visit(next, graph, path, done);
      if (!cycle.isEmpty()) {
        return cycle;
      }
    }
    path.pop();
    return List.of();
  }
}
