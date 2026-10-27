package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.JacksonComponent;

/**
 * What the framework parts taken out of the context for the start-up time (M1.7) rely on: no class of the gateway is
 * a {@code @JacksonComponent} ({@code PlatformConfiguration.withoutJacksonComponentScan}), and there are no
 * properties-file named queries for the repositories ({@code GatewayDataSourceConfig.leanRepositoryDefinitions}).
 */
class UnusedFrameworkBeansTest {

  @Test
  void noGatewayClassIsAJacksonComponent() {
    List<String> components = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("ai.msrv.gaterook").stream().filter(c -> c.isAnnotatedWith(JacksonComponent.class))
        .map(JavaClass::getName).toList();
    assertThat(components).as("move them into a Jackson module, or let the context scan for them again").isEmpty();
  }

  @Test
  void thereAreNoPropertiesFileNamedQueries() throws Exception {
    assertThat(getClass().getClassLoader().getResources("META-INF/jpa-named-queries.properties").hasMoreElements())
        .as("repositories get no named queries from properties files (@Query on the method instead)").isFalse();
  }
}
