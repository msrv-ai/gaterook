package com.gaterook.connectors.redshift.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Redshift connector. The AWS SDK's signer and STS client are covered by the hints of
 * connectors/cloud-auth and the logs connector, the JDK's DOM parser of {@code DescribeClusters} answers by the image
 * itself (as for the S3 connector); what is left are the connector's own schemas.
 */
class RedshiftHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/redshift/*.schema.json");
  }
}
