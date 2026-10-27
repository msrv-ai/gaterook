package ai.msrv.gaterook.core.platform;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a gateway class declares one of a few annotations anywhere in its own hierarchy: on the type or on a
 * declared method of the class, a superclass or an interface in {@code ai.msrv.gaterook}. One look at the plain declared
 * annotations per type, cached; what {@link GatewayAsync} and {@link TransactionAdvisorScope} ask before Spring's
 * merged-annotation lookups walk every method of a bean (ARCHITECTURE.md §10, M1.7). Meta-annotations are not
 * followed: the core uses {@code @Async} and {@code @Transactional} directly ({@code TransactionAdvisorScopeTest}
 * checks that against Spring's own pointcut).
 */
final class OwnAnnotations {

  static final String OWN_PACKAGE = "ai.msrv.gaterook.";

  private final Set<String> names;
  private final Map<Class<?>, Boolean> cache = new ConcurrentHashMap<>();

  OwnAnnotations(String... annotationTypeNames) {
    this.names = Set.of(annotationTypeNames);
  }

  /** {@code false} for every class outside {@code ai.msrv.gaterook}; CGLIB subclasses keep the name of their class. */
  boolean declaredBy(Class<?> type) {
    if (!type.getName().startsWith(OWN_PACKAGE)) {
      return false;
    }
    // Not computeIfAbsent: the walk asks about the interfaces through this method again, and a nested update of a
    // ConcurrentHashMap fails ("Recursive update") whenever the keys share a bin. Two threads may both compute a
    // type; they get the same answer.
    Boolean known = cache.get(type);
    if (known != null) {
      return known;
    }
    boolean declares = hierarchyDeclares(type);
    cache.put(type, declares);
    return declares;
  }

  private boolean hierarchyDeclares(Class<?> type) {
    for (Class<?> c = type; c != null && c.getName().startsWith(OWN_PACKAGE); c = c.getSuperclass()) {
      if (declares(c)) {
        return true;
      }
      for (Class<?> ifc : c.getInterfaces()) {
        if (ifc.getName().startsWith(OWN_PACKAGE) && declaredBy(ifc)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean declares(Class<?> type) {
    if (named(type.getDeclaredAnnotations())) {
      return true;
    }
    for (Method method : type.getDeclaredMethods()) {
      if (named(method.getDeclaredAnnotations())) {
        return true;
      }
    }
    return false;
  }

  private boolean named(Annotation[] annotations) {
    for (Annotation a : annotations) {
      if (names.contains(a.annotationType().getName())) {
        return true;
      }
    }
    return false;
  }
}
