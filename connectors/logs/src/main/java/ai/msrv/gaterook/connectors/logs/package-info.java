/**
 * Logs connector ({@code logs}), report 07: one connector, four backends (Loki, GCP Cloud Logging, AWS CloudWatch
 * Logs, OpenSearch) behind the same two tools. Log lines are untrusted content: the gateway's instructions tell
 * agents they are data, never instructions. Step C5 implements the backends; this package has the final manifest.
 */
package ai.msrv.gaterook.connectors.logs;
