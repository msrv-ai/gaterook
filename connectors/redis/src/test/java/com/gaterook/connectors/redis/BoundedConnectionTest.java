package com.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.RedisInputStream;

class BoundedConnectionTest {

  @Test
  void aLongBulkStringKeepsItsHeadAndTheStreamStaysAligned() {
    String big = "x".repeat(100_000);
    RedisInputStream in = stream("*3\r\n$" + big.length() + "\r\n" + big + "\r\n$2\r\nok\r\n:7\r\n+NEXT\r\n");

    List<?> reply = (List<?>) BoundedConnection.read(in, 256);

    BoundedConnection.Cut cut = (BoundedConnection.Cut) reply.get(0);
    assertThat(cut.length()).isEqualTo(big.length());
    assertThat(new String(cut.head(), StandardCharsets.UTF_8)).isEqualTo("x".repeat(256));
    assertThat(new String((byte[]) reply.get(1), StandardCharsets.UTF_8)).isEqualTo("ok");
    assertThat(reply.get(2)).isEqualTo(7L);
    assertThat(new String((byte[]) BoundedConnection.read(in, 256), StandardCharsets.UTF_8))
        .as("the next reply starts where this one ended").isEqualTo("NEXT");
  }

  @Test
  void nullsNestedArraysAndNestedErrorsReadAsJedisReadsThem() {
    RedisInputStream in = stream("*4\r\n$-1\r\n*-1\r\n*2\r\n$1\r\na\r\n$1\r\nb\r\n-WRONGTYPE no\r\n");

    List<?> reply = (List<?>) BoundedConnection.read(in, 256);

    assertThat(reply.get(0)).isNull();
    assertThat(reply.get(1)).isNull();
    assertThat((List<?>) reply.get(2)).hasSize(2);
    assertThat(reply.get(3)).isInstanceOfSatisfying(JedisDataException.class,
        e -> assertThat(e.getMessage()).startsWith("WRONGTYPE"));
  }

  @Test
  void anArrayCountReservesNoMoreThanArrives() {
    RedisInputStream in = stream("*2000000000\r\n");
    assertThatThrownBy(() -> BoundedConnection.read(in, 256)).isInstanceOf(JedisConnectionException.class);
  }

  @Test
  void typesOtherThanResp2AreABrokenStream() {
    assertThatThrownBy(() -> BoundedConnection.read(stream("%1\r\n"), 256))
        .isInstanceOf(JedisConnectionException.class).hasMessageContaining("Unknown reply");
    assertThatThrownBy(() -> BoundedConnection.read(stream("$-5\r\n"), 256))
        .isInstanceOf(JedisConnectionException.class).hasMessageContaining("Invalid bulk length");
  }

  @Test
  void anElementCutOnTheWireShowsItsWholeLength() {
    boolean[] cut = new boolean[1];
    var head = ("日本" + "y".repeat(300)).getBytes(StandardCharsets.UTF_8);
    var element = RedisValues.element(new BoundedConnection.Cut(java.util.Arrays.copyOf(head, 4), 1_000_000), 4, cut);
    assertThat(cut[0]).isTrue();
    assertThat(element.asString()).isEqualTo("日…‹truncated 999,997 bytes›");
    assertThat(RedisValues.field(new BoundedConnection.Cut("name".getBytes(StandardCharsets.UTF_8), 10)))
        .isEqualTo("name…‹truncated 6 bytes›");
    assertThat(Replies.bytes(new BoundedConnection.Cut(new byte[1], 2))).as("never mistaken for a whole value")
        .isNull();
  }

  private static RedisInputStream stream(String resp) {
    return new RedisInputStream(new ByteArrayInputStream(resp.getBytes(StandardCharsets.UTF_8)));
  }
}
