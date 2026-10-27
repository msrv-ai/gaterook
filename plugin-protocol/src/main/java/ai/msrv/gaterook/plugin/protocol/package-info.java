/**
 * GPP/1, the gateway plugin protocol: JSON-RPC 2.0 as NDJSON over stdio between the native core and the plugin
 * host (report 16 §4).
 *
 * <p>Both sides use the same classes: {@code JsonRpcPeer} (requests in both directions, virtual-thread dispatch),
 * {@code FrameReader} (byte-level framing with a reusable, zeroable buffer) and {@code WireCodec} (connector-api
 * types to and from {@code ObjectNode}, without reflection, golden-tested per message). Implemented by step K4.
 */
package ai.msrv.gaterook.plugin.protocol;
