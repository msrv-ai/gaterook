package com.gaterook.connectors.snowflake.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Snowflake connector: its schemas. The SQL API client is {@code java.net.http}, and the JWT
 * is signed with the JDK's {@code SHA256withRSA}, which the image keeps as other connectors use it.
 */
class SnowflakeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/snowflake/*.schema.json");
  }
}
