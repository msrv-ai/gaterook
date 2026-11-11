/**
 * The Azure connector: Resource Graph inventory ({@code azure_describe}), classified Azure Resource Manager calls
 * ({@code azure_call}: GET reads, read-only queries, everything else with approval, secret actions refused), Azure
 * Monitor metrics, alerts, the Activity Log and Resource Health ({@code azure_monitor}), and the shared log tools over
 * Log Analytics and Application Insights. Entra ID tokens come from {@code connectors/cloud-auth}. Spring only in
 * {@code .boot}.
 */
package ai.msrv.gaterook.connectors.azure;
