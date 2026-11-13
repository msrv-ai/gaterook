package com.gaterook.connectors.cosmosdb.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Cosmos DB connector: its schemas. The REST client is {@code java.net.http}, the signature
 * the JDK's {@code HmacSHA256}, which the image keeps as other connectors use it.
 */
class CosmosDbHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/cosmosdb/*.schema.json");
  }
}
