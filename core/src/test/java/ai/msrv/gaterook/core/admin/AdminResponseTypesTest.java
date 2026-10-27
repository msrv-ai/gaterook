package ai.msrv.gaterook.core.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The static half of "no field for a secret's value" (ARCHITECTURE.md §9, M1.6): every type an admin controller
 * answers with is walked — records, their components, lists, maps, optionals — and none has a property named like a
 * secret value that could hold text. The only exceptions are the two answers whose job is to show a one-time token once: a new endpoint
 * token and Electron main's file grant. {@link AdminApiIT} checks the same on the answers themselves, JSON trees
 * included.
 */
class AdminResponseTypesTest {

  /** {@code Type#getTypeName()} of the records allowed to carry a {@code token}. */
  private static final Set<String> SHOWS_A_TOKEN_ONCE = Set.of(
      "ai.msrv.gaterook.core.admin.tokens.TokensController$IssuedToken",
      "ai.msrv.gaterook.core.admin.core.FileGrantController$Granted");

  @Test
  void noResponseTypeHasAPropertyForASecretValue() {
    List<Class<?>> controllers = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("ai.msrv.gaterook.core.admin").stream()
        .filter(c -> c.isAnnotatedWith(RestController.class))
        .map(JavaClass::reflect)
        .toList();
    assertThat(controllers).hasSizeGreaterThanOrEqualTo(14);

    Set<Class<?>> seen = new HashSet<>();
    List<String> problems = new ArrayList<>();
    List<String> allowed = new ArrayList<>();
    int handlers = 0;
    for (Class<?> controller : controllers) {
      for (Method m : controller.getDeclaredMethods()) {
        if (!isHandler(m)) {
          continue;
        }
        handlers++;
        walk(m.getGenericReturnType(), controller.getSimpleName() + "." + m.getName(), seen, problems, allowed);
      }
    }
    assertThat(handlers).as("handlers found").isGreaterThanOrEqualTo(100);
    assertThat(seen.size()).as("response types walked").isGreaterThan(100);
    assertThat(allowed).containsExactlyInAnyOrder(
        "ai.msrv.gaterook.core.admin.tokens.TokensController$IssuedToken.token",
        "ai.msrv.gaterook.core.admin.core.FileGrantController$Granted.token");
    assertThat(problems).isEmpty();
  }

  private static boolean isHandler(Method m) {
    return m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
        || m.isAnnotationPresent(PutMapping.class) || m.isAnnotationPresent(DeleteMapping.class)
        || m.isAnnotationPresent(PatchMapping.class) || m.isAnnotationPresent(RequestMapping.class);
  }

  private static void walk(Type type, String path, Set<Class<?>> seen, List<String> problems, List<String> allowed) {
    switch (type) {
      case ParameterizedType p -> {
        walk(p.getRawType(), path, seen, problems, allowed);
        for (Type arg : p.getActualTypeArguments()) {
          walk(arg, path, seen, problems, allowed);
        }
      }
      case GenericArrayType a -> walk(a.getGenericComponentType(), path, seen, problems, allowed);
      case WildcardType w -> {
        for (Type upper : w.getUpperBounds()) {
          walk(upper, path, seen, problems, allowed);
        }
      }
      case Class<?> c when c.isArray() -> walk(c.getComponentType(), path, seen, problems, allowed);
      case Class<?> c when c.isRecord() -> {
        if (!seen.add(c)) {
          return;
        }
        for (RecordComponent rc : c.getRecordComponents()) {
          String name = jsonName(c, rc);
          if (AdminMvc.SECRET_PROPERTIES.contains(name) && canHoldText(rc.getType())) {
            String where = c.getTypeName() + "." + name;
            if (SHOWS_A_TOKEN_ONCE.contains(c.getTypeName()) && name.equals("token")) {
              allowed.add(where);
            } else {
              problems.add(where + " (answered by " + path + ")");
            }
          }
          walk(rc.getGenericType(), path, seen, problems, allowed);
        }
      }
      default -> {
        // JSON trees, strings, numbers, enums, java.* containers' raw types: their contents are checked at run time.
      }
    }
  }

  /** A flag such as {@code passphrase: true} ("this backup has one") can't carry a secret; text and trees can. */
  private static boolean canHoldText(Class<?> type) {
    return !(type.isPrimitive() || type == Boolean.class || Number.class.isAssignableFrom(type) || type.isEnum()
        || java.time.temporal.Temporal.class.isAssignableFrom(type));
  }

  /** The property's name on the wire: {@code @JsonProperty} lands on the component's field and accessor. */
  private static String jsonName(Class<?> record, RecordComponent rc) {
    JsonProperty onAccessor = rc.getAccessor().getAnnotation(JsonProperty.class);
    if (onAccessor != null && !onAccessor.value().isEmpty()) {
      return onAccessor.value();
    }
    try {
      Field field = record.getDeclaredField(rc.getName());
      JsonProperty onField = field.getAnnotation(JsonProperty.class);
      if (onField != null && !onField.value().isEmpty()) {
        return onField.value();
      }
    } catch (NoSuchFieldException e) {
      // A record always has the field; keep the component's name if reflection disagrees.
    }
    return rc.getName();
  }
}
