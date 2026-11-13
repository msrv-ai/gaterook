package com.gaterook.connectors.redis;

import java.nio.charset.StandardCharsets;
import redis.clients.jedis.commands.ProtocolCommand;

/**
 * Every command this connector builds. Separate executor modes allow reads, bounded writes or exact connection
 * probes. The agent never supplies a command name.
 */
enum Cmd implements ProtocolCommand {
  // Reads (report 05 §3.4–3.7; all O(limit), no KEYS, no HGETALL/SMEMBERS, strings by GETRANGE past the limit).
  PING, HELLO, CLIENT, TYPE, PTTL, MEMORY, DBSIZE, SCAN,
  STRLEN, GET, GETRANGE, HLEN, HSCAN, LLEN, LRANGE, SCARD, SSCAN, ZCARD, ZRANGE, XLEN, XREVRANGE,
  JSON_GET("JSON.GET"),
  // Writes: one validated key and bounded values; SET also has a non-writing permission probe shape.
  SET, HSET, LPUSH, RPUSH, SADD, ZADD,
  // Test connection only: they can't change anything in the shape the probe executor allows.
  ACL, CONFIG, COMMAND, INFO;

  private final byte[] raw;

  Cmd() {
    this.raw = name().getBytes(StandardCharsets.US_ASCII);
  }

  Cmd(String wire) {
    this.raw = wire.getBytes(StandardCharsets.US_ASCII);
  }

  @Override
  public byte[] getRaw() {
    return raw.clone();
  }

  /** The name on the wire, {@code JSON.GET} for {@link #JSON_GET}. */
  String wire() {
    return new String(raw, StandardCharsets.US_ASCII);
  }
}
