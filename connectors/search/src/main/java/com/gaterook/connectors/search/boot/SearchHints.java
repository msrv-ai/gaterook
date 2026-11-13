package com.gaterook.connectors.search.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the vector database and search engine connectors. They speak HTTP through the core's egress
 * guard and JSON as Jackson trees, so what is left are their schemas.
 */
class SearchHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/search/*.schema.json");
  }
}
