package ai.msrv.gaterook.connectors.firebase.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.firebase.FirestoreConnector;
import ai.msrv.gaterook.connectors.firebase.RtdbConnector;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Cloud Firestore and Firebase Realtime Database connectors (ARCHITECTURE.md §2.3, A13): explicit beans
 * listed in this module's own {@code AutoConfiguration.imports}, resolved by Spring AOT at build time. No
 * {@code @Conditional*}: the types are switched on and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives the Google tokens (and, in development builds, the test endpoints
 * {@code gcp.firestore}, {@code gcp.firebase-rtdb} and {@code gcp.oauth2}); a context without the core gets
 * credentials for the real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(FirebaseHints.class)
public class FirebaseConnectorsAutoConfiguration {

  @Bean
  Connector firestoreConnector(ObjectProvider<CloudCredentials> cloud) {
    return new FirestoreConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }

  @Bean
  Connector firebaseRtdbConnector(ObjectProvider<CloudCredentials> cloud) {
    return new RtdbConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
