/**
 * Shared library of the built-in connectors and the core (ARCHITECTURE.md §2.1). No Spring here: any connector built
 * on it can move into a plugin.
 *
 * <ul>
 *   <li>{@link com.gaterook.connectors.support.egress}: the egress guard on OkHttp (report 10 §6.1);</li>
 *   <li>{@link com.gaterook.connectors.support.TimeArg}: {@code from}/{@code to} of tools;</li>
 *   <li>{@link com.gaterook.connectors.support.Glob}: policy masks and path globs without regular expressions;</li>
 *   <li>{@link com.gaterook.connectors.support.OutputSanitizer}: untrusted output with visible escapes;</li>
 *   <li>{@link com.gaterook.connectors.support.JsonNumbers}: the text of parsed JSON numbers, bounded by their
 *       digits and never by their exponent;</li>
 *   <li>{@link com.gaterook.connectors.support.Pem} and {@link com.gaterook.connectors.support.TrustStores}: PEM
 *       text of connection forms and in-memory trust;</li>
 *   <li>{@link com.gaterook.connectors.support.SchemaResources} and {@link com.gaterook.connectors.support.BuiltIn}:
 *       manifests of built-in connectors;</li>
 *   <li>{@link com.gaterook.connectors.support.mcp}: the core's view of external MCP servers (C10).</li>
 * </ul>
 */
package com.gaterook.connectors.support;
