package com.gaterook.e2e.builtin;

import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorManifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The manifests of every built-in connector module, registered through its own {@code AutoConfiguration.imports}. */
final class BuiltInManifests {

  private BuiltInManifests() {}

  static Map<String, ConnectorManifest> load() {
    ClassLoader classLoader = BuiltInManifests.class.getClassLoader();
    List<Class<?>> configurations = new ArrayList<>();
    for (String name : ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates()) {
      if (!name.startsWith("com.gaterook.connectors.")) continue;
      try {
        configurations.add(Class.forName(name));
      } catch (ClassNotFoundException e) {
        throw new AssertionError("AutoConfiguration.imports names a missing class " + name, e);
      }
    }
    Map<String, ConnectorManifest> byId = new LinkedHashMap<>();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(configurations.toArray(Class<?>[]::new)))
        .run(ctx -> ctx.getBeansOfType(Connector.class).values()
            .forEach(connector -> byId.put(connector.manifest().id(), connector.manifest())));
    return Map.copyOf(byId);
  }
}
