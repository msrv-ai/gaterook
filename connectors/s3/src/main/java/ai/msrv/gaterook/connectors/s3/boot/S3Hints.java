package ai.msrv.gaterook.connectors.s3.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the S3 connector. The AWS SDK's signer and STS client are covered by the hints of
 * connectors/cloud-auth and the logs connector, the JDK's DOM parser by the image itself (the SQL and AWS connectors
 * parse XML the same way); what is left are the connector's own schemas.
 */
class S3Hints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/s3/*.schema.json");
  }
}
