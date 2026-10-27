package ai.msrv.gaterook.core.tunnel;

/**
 * The tunnel came up, went down or failed (ARCHITECTURE.md §2.2 events). Published after the state changed in
 * memory, outside any transaction: listeners use plain {@code @EventListener}. {@code oauth} hears {@code on} with a
 * new host and marks the sign-ins bound to the previous address as {@code address-changed}.
 *
 * @param state {@code off}, {@code starting}, {@code on} or {@code error}
 * @param host  the public hostname while {@code on}, else null
 */
public record TunnelStateChanged(String state, String host, String endpointSlug, TunnelProvider provider) {}
