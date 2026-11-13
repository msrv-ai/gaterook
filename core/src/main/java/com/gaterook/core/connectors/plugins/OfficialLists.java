package com.gaterook.core.connectors.plugins;

import com.gaterook.core.platform.BuildFlags;

/**
 * GateRook's own signed lists as a release reads them (docs/connector-delivery.md): the list of connector modules in
 * GateRook's distribution repository on GitHub, and the public half of the key it is signed with. The key is the trust
 * anchor: a list from this address that another key signed is refused like any other.
 *
 * <p>A release reads them when nothing else is configured. A development build reads no list unless it is given one
 * ({@code gateway.modules.channel-url} and {@code gateway.modules.channel-keys}) or asked for the official ones
 * ({@code gateway.lists=official}), so tests and the development loop never depend on the network.
 */
final class OfficialLists {

  /** The distribution repository's files as GitHub serves them. */
  static final String BASE = "https://raw.githubusercontent.com/gaterook/gaterook-plugins/main/";
  /** The folder of the list of connector modules ({@code index.json} and {@code index.json.sig}). */
  static final String MODULES_URL = BASE + "modules/";
  /** The key the list of connector modules is signed with: its id and the 32 raw bytes in base64url. */
  static final String MODULES_KEYS = "modules-2026-1=KGh_d_U-8EMVgHQi6ev_iW1_0dOP043n_kFQdAB4upo";

  private OfficialLists() {}

  /** Whether this build reads the official lists where no other is configured. */
  static boolean inUse(String lists) {
    return BuildFlags.RELEASE || "official".equalsIgnoreCase(lists == null ? "" : lists.strip());
  }
}
