/**
 * GCP connector ({@code gcp}), report 09: resources through Cloud Asset (plus a live GET of one resource from its own
 * service by eight fixed routes), metrics through Cloud Monitoring, and log searches through Cloud Logging. Shared
 * log tools use the same connection scopes and credentials. Only fixed read routes ever reach Google; dangerous
 * resource GETs ({@code authString}, serial port output, objects of a bucket, {@code :access}) are unreachable ({@link
 * ai.msrv.gaterook.connectors.gcp.LiveRoutes}).
 */
package ai.msrv.gaterook.connectors.gcp;
