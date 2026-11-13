package com.gaterook.connectors.influxdb.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the InfluxDB connector. It speaks HTTP with {@code java.net.http} and JSON as Jackson trees, so
 * what is left are its schemas.
 */
class InfluxDbHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/influxdb/*.schema.json");
  }
}
