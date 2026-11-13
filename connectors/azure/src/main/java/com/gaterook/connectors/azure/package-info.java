/**
 * The Azure connector: Resource Graph inventory ({@code azure_describe}), Azure Resource Manager requests
 * ({@code azure_api_read}: GET and read-only queries; {@code azure_api_change}: everything else, with approval; secret
 * actions refused), Azure Monitor metrics, alerts, the Activity Log and Resource Health ({@code azure_monitor}), and
 * the shared log tools over Log Analytics and Application Insights. Entra ID tokens come from
 * {@code connectors/cloud-auth}. Spring only in {@code .boot}.
 */
package com.gaterook.connectors.azure;
