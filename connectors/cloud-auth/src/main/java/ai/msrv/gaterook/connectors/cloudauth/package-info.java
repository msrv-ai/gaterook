/**
 * Short-lived cloud credentials for connectors and the core (reports 07, 09, 12).
 *
 * <p>{@link ai.msrv.gaterook.connectors.cloudauth.CloudCredentials#gcp} returns {@code GoogleCredentials} from Google
 * sign-in on this computer or a service account key, impersonating a service account for 900 s when the connection
 * names one; {@link ai.msrv.gaterook.connectors.cloudauth.CloudCredentials#aws} returns an {@code AwsCredentialsProvider}
 * from an access key or a profile, assuming a role with ExternalId and a session policy
 * ({@link ai.msrv.gaterook.connectors.cloudauth.AwsSessionPolicy}). Every derived token goes to the call's taint through
 * {@code requestMetadata(…, ctx::taint)} and {@code tainting(…, ctx::taint)}, so the masker knows it. No Spring.
 */
package ai.msrv.gaterook.connectors.cloudauth;
