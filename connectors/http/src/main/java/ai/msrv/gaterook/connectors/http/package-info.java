/**
 * HTTP API connector ({@code http}), report 10.
 *
 * <p>{@code http_get} (GET and HEAD) and {@code http_request} (any method; POST, PUT, PATCH and DELETE always wait
 * for approval of the exact request) through the egress guard: base URLs of the connection intersected with the
 * environment's allowlist, no metadata or unapproved private addresses, no redirects. Step C9 implements execution
 * and the OpenAPI template kind; this package has the final manifest.
 */
package ai.msrv.gaterook.connectors.http;
