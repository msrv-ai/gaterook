package com.gaterook.connectors.firebase.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Firebase connectors. google-auth's credentials are covered by the hints of
 * connectors/cloud-auth and the GCP connector; what is left are the connectors' own schemas.
 */
class FirebaseHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/firestore/*.schema.json");
    hints.resources().registerPattern("connectors/firebase-rtdb/*.schema.json");
  }
}
