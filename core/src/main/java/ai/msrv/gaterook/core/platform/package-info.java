/**
 * Platform module (ARCHITECTURE.md §2.2). Shared by every module, depends on none: bootstrap over stdin, progress
 * and ready line, FailureAnalyzer, BuildFlags; the gateway database (GatewayDataSourceConfig, the JSON FormatMapper)
 * and SettingStore (table setting); LocalGuardFilter and LaunchSecret; Caller and Ingress with the Authenticator SPI
 * (CallerAuthenticator tries the Authenticator beans of an ingress); PublicHostname (written by tunnel); Masker and
 * Taint; the SetupChangeRecorder port (implemented by audit) and the HmacKeys port (implemented by secrets); RowMac;
 * EventBus (SSE), Ids, Clock, NativeOs (FFM), CoreHints and HandleCodec.
 *
 * <p>Depends on no other module.
 */
@ApplicationModule(displayName = "Platform", allowedDependencies = {})
package ai.msrv.gaterook.core.platform;

import org.springframework.modulith.ApplicationModule;
