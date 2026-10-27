/**
 * The egress guard (report 10 §6.1, ARCHITECTURE.md §9 "SSRF"): {@link ai.msrv.gaterook.connectors.support.egress.AddressPolicy}
 * judges addresses, {@link ai.msrv.gaterook.connectors.support.egress.GuardDns} what names resolve to,
 * {@link ai.msrv.gaterook.connectors.support.egress.GuardSocketFactory} where each TCP connection really goes, and
 * {@link ai.msrv.gaterook.connectors.support.egress.EgressClients} makes the only OkHttp clients the gateway uses for
 * destinations a person configured. {@link ai.msrv.gaterook.connectors.support.egress.UrlRules} keeps agent paths under
 * the configured base URLs.
 */
package ai.msrv.gaterook.connectors.support.egress;
