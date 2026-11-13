/**
 * InfluxDB ({@code influxdb}): databases, measurements, and SQL or InfluxQL reads of InfluxDB 3 Core and Enterprise,
 * InfluxDB 2.x and InfluxDB 1.x; one approved line protocol write per change (docs/influxdb.md). GateRook reads every
 * statement with its own lexer before it runs and runs only what it reads as a read; Flux is never run.
 */
package com.gaterook.connectors.influxdb;
