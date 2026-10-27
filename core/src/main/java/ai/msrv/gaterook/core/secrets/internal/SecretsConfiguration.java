package ai.msrv.gaterook.core.secrets.internal;

import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * Beans of the secrets module: the pre-migration keyset loader, the cloud credentials the Logs, GCP and AWS connectors
 * and the cloud secret stores share, and the hourly cleanup of expired previous versions.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(SecretsConfiguration.Hints.class)
class SecretsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SecretsConfiguration.class);

  @Bean
  @Order(0)
  KeysetLoader keysetLoader(GatewayPaths paths, Bootstrap bootstrap) {
    return new KeysetLoader(paths, bootstrap);
  }

  /**
   * One {@link CloudCredentials} for the whole core (ARCHITECTURE.md §2.3). Test endpoints
   * ({@code gateway.cloud.gcp.token-uri}, {@code …iam-endpoint}, {@code gateway.cloud.aws.sts-endpoint}, and the
   * cloud APIs of connectors in {@code gateway.cloud.endpoints}: {@code gcp.logging=http://…,aws.logs=http://…}) are
   * honoured by development builds only: a release never sends credentials anywhere but Google and AWS.
   */
  @Bean(destroyMethod = "close")
  CloudCredentials cloudCredentials(Environment environment, Clock clock) {
    CloudEndpoints endpoints = new CloudEndpoints(uri(environment, "gateway.cloud.gcp.token-uri"),
        environment.getProperty("gateway.cloud.gcp.iam-endpoint"), null,
        uri(environment, "gateway.cloud.aws.sts-endpoint"), null, null,
        CloudEndpoints.parseServices(environment.getProperty("gateway.cloud.endpoints")));
    if (endpoints.overridden() && BuildFlags.RELEASE) {
      log.warn("Cloud endpoint overrides are ignored in a release build");
      endpoints = CloudEndpoints.PRODUCTION;
    }
    return new CloudCredentials(endpoints, clock);
  }

  /** Expired previous versions go once the core is up and then every hour (Replace shreds them too). */
  @Bean
  PreviousVersionShredder previousVersionShredder(SecretService secrets) {
    return new PreviousVersionShredder(secrets::shredExpiredPrevious, Duration.ofHours(1));
  }

  private static URI uri(Environment environment, String key) {
    String value = environment.getProperty(key);
    return value == null || value.isBlank() ? null : URI.create(value);
  }

  /**
   * Reflection of the native image (report 15 §9: Tink has no metadata of its own, the repository's protobuf-java
   * entry is for 2.5.0). Traced with the native-image agent over EnvelopeTest, SecretsIT and CanaryLeakIT:
   *
   * <ul>
   *   <li>the serializer that refuses to write a secret, which Jackson instantiates reflectively;
   *   <li>{@code ExtensionRegistryLite.getEmptyRegistry()} of protobuf-java, which finds the full registry's factory
   *       methods by reflection on every parse of a binary keyset (each wrapped data key);
   *   <li>the presence checks of Tink for Gson and Conscrypt ({@code Class.forName}; Conscrypt is absent and must
   *       stay a {@code ClassNotFoundException}).
   * </ul>
   */
  static final class Hints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
      hints.reflection().registerType(TypeReference.of("ai.msrv.gaterook.core.secrets.LocalSecretValue$Refuse"),
          MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
      hints.reflection().registerType(TypeReference.of("com.google.protobuf.ExtensionRegistry"), type -> type
          .withMethod("getEmptyRegistry", List.of(), ExecutableMode.INVOKE)
          .withMethod("newInstance", List.of(), ExecutableMode.INVOKE));
      hints.reflection().registerType(TypeReference.of("com.google.gson.Gson"));
      hints.reflection().registerType(TypeReference.of("org.conscrypt.Conscrypt"));
    }
  }
}
