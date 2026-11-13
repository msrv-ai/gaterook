package com.gaterook.core.connectors.plugins;

import com.gaterook.core.platform.BuildFlags;

/**
 * GateRook's own signed lists as every build reads them (docs/connector-delivery.md): the list of connector modules
 * in GateRook's distribution repository on GitHub, and the public half of the key it is signed with. The key is the
 * trust anchor: a list from this address that another key signed is refused like any other.
 *
 * <p>A build reads them when nothing else is configured ({@code gateway.modules.channel-url} and
 * {@code gateway.modules.channel-keys} name another list), a development build as a release does: what GateRook
 * publishes shows up in the application by itself. Only a run that must not depend on the network says
 * {@code gateway.lists=none}, as the tests of this repository do; a release ignores that.
 */
final class OfficialLists {

  /** The distribution repository's files as GitHub serves them. */
  static final String BASE = "https://raw.githubusercontent.com/gaterook/gaterook-plugins/main/";
  /** The folder of the list of connector modules ({@code index.json} and {@code index.json.sig}). */
  static final String MODULES_URL = BASE + "modules/";
  /** The key the list of connector modules is signed with: its id and the 32 raw bytes in base64url. */
  static final String MODULES_KEYS = "modules-2026-1=KGh_d_U-8EMVgHQi6ev_iW1_0dOP043n_kFQdAB4upo";

  private OfficialLists() {}

  /** What a run says to read no official list: {@code -Dgateway.lists=none}. */
  static final String NONE = "none";

  /** Whether this run reads the official lists where no other is configured: always, unless it says {@link #NONE}. */
  static boolean inUse(String lists) {
    return BuildFlags.RELEASE || !NONE.equalsIgnoreCase(lists == null ? "" : lists.strip());
  }
}
