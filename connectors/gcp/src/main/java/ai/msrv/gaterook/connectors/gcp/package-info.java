/**
 * GCP connector ({@code gcp}), report 09: resources through Cloud Asset (plus a live GET of one resource from its own
 * service by eight fixed routes) and metrics through Cloud Monitoring, read with service account impersonation. Only
 * GETs the gateway builds and {@code testIamPermissions} ever reach Google; dangerous GETs ({@code authString}, serial
 * port output, objects of a bucket, {@code :access}) are unreachable by construction ({@link
 * ai.msrv.gaterook.connectors.gcp.LiveRoutes}).
 */
package ai.msrv.gaterook.connectors.gcp;
