package com.gaterook.connectors.mongodb;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.HexFormat;
import java.util.Locale;
import org.bson.BsonArray;
import org.bson.BsonBinaryReader;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.bson.ByteBuf;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonValueCodec;
import org.bson.codecs.DecoderContext;
import org.bson.io.ByteBufferBsonInput;

/**
 * A raw document read within the page's limits (report 05 §3.1). The driver hands over each document as its bytes;
 * building it whole ({@code BsonDocument}, then its text) takes several times its size, which for a document of a
 * million fields or a string of megabytes is the gateway's heap. So, first, {@link #atLeast}: how long the document's
 * relaxed Extended JSON is at the very least, read off the bytes without building anything and stopped as soon as it
 * passes what the page has room for. Then {@link #decode}: the document with its long strings cut to
 * {@code maxChars} and its long binaries replaced as they are read, so that no value longer than that is ever held.
 */
final class BoundedBson {

  private static final BsonValueCodec CODEC = new BsonValueCodec();
  private static final DecoderContext CONTEXT = DecoderContext.builder().build();

  private final ByteBuf bytes;
  private final BsonBinaryReader reader;
  private final int maxChars;
  private int cut;

  private BoundedBson(RawBsonDocument raw, int maxChars) {
    this.bytes = raw.getByteBuffer();
    this.reader = new BsonBinaryReader(new ByteBufferBsonInput(raw.getByteBuffer()));
    this.maxChars = maxChars;
  }

  /**
   * A lower bound of the UTF-8 length of {@code raw}'s relaxed Extended JSON after its values are cut, or any number
   * above {@code limit} once the bound passes it.
   */
  static long atLeast(RawBsonDocument raw, int maxChars, long limit) {
    BoundedBson b = new BoundedBson(raw, maxChars);
    try (b.reader) {
      b.reader.readStartDocument();
      return b.measure(2, limit, false);
    }
  }

  /**
   * {@code raw} as a document whose strings, symbols and code are cut to {@code maxChars} characters
   * ({@code …‹truncated 12,345 chars›}) and whose binaries longer than {@link Ejson#MAX_BINARY} bytes are a
   * description, cut while they are read; {@code cutValues[0]} gets how many values were.
   */
  static BsonDocument decode(RawBsonDocument raw, int maxChars, int[] cutValues) {
    BoundedBson b = new BoundedBson(raw, maxChars);
    try (b.reader) {
      BsonDocument doc = b.document();
      cutValues[0] += b.cut;
      return doc;
    }
  }

  private long measure(long bound, long limit, boolean array) {
    boolean first = true;
    BsonType type;
    while ((type = reader.readBsonType()) != BsonType.END_OF_DOCUMENT) {
      // "name": and the ", " before every element but the first; an array's index names aren't written, and the
      // reader has already passed them. A name's characters are at most its bytes.
      bound += (array ? 0 : reader.readName().length() + 4) + (first ? 0 : 2);
      first = false;
      switch (type) {
        case DOCUMENT -> {
          reader.readStartDocument();
          bound = measure(bound + 2, limit, false);
          reader.readEndDocument();
        }
        case ARRAY -> {
          reader.readStartArray();
          bound = measure(bound + 2, limit, true);
          reader.readEndArray();
        }
        case STRING, SYMBOL, JAVASCRIPT -> {
          // The text's UTF-8 bytes are at least its characters; a cut keeps maxChars of them.
          int length = bytes.getInt(position()) - 1;
          reader.skipValue();
          bound += Math.min(length, maxChars) + 2;
        }
        default -> {
          reader.skipValue();
          bound += switch (type) {
            case BOOLEAN, NULL -> 4;
            case OBJECT_ID -> 30;
            case DATE_TIME, DECIMAL128, TIMESTAMP, REGULAR_EXPRESSION, BINARY, DB_POINTER -> 20;
            case MIN_KEY, MAX_KEY, UNDEFINED, JAVASCRIPT_WITH_SCOPE -> 10;
            default -> 1;
          };
        }
      }
      if (bound > limit) {
        return bound;
      }
    }
    return bound;
  }

  private BsonDocument document() {
    reader.readStartDocument();
    BsonDocument doc = new BsonDocument();
    BsonType type;
    while ((type = reader.readBsonType()) != BsonType.END_OF_DOCUMENT) {
      String name = reader.readName();
      doc.append(name, value(type));
    }
    reader.readEndDocument();
    return doc;
  }

  private BsonArray array() {
    reader.readStartArray();
    BsonArray array = new BsonArray();
    BsonType type;
    while ((type = reader.readBsonType()) != BsonType.END_OF_DOCUMENT) {
      array.add(value(type));
    }
    reader.readEndArray();
    return array;
  }

  private BsonValue value(BsonType type) {
    return switch (type) {
      case DOCUMENT -> document();
      case ARRAY -> array();
      case STRING, SYMBOL, JAVASCRIPT -> text();
      case BINARY -> binary();
      default -> CODEC.decode(reader, CONTEXT);
    };
  }

  /** A string, symbol or code value: whole when it fits, else its first {@code maxChars} characters and a marker. */
  private BsonValue text() {
    int start = position();
    int from = start + 4;
    int to = from + bytes.getInt(start) - 1;
    // UTF-16 units, as String.length() counts them: a four-byte sequence is a surrogate pair.
    int units = 0;
    int end = from;
    while (end < to) {
      int b = bytes.get(end) & 0xFF;
      int width = b < 0x80 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
      int u = width == 4 ? 2 : 1;
      if (units + u > maxChars) {
        break;
      }
      units += u;
      end = Math.min(to, end + width);
    }
    if (end == to) {
      return CODEC.decode(reader, CONTEXT);
    }
    long total = units;
    for (int i = end; i < to; i++) {
      int b = bytes.get(i) & 0xFF;
      if ((b & 0xC0) != 0x80) {
        total += b >= 0xF0 ? 2 : 1;
      }
    }
    byte[] head = new byte[end - from];
    for (int i = 0; i < head.length; i++) {
      head[i] = bytes.get(from + i);
    }
    reader.skipValue();
    cut++;
    return new BsonString(Ejson.truncated(new String(head, StandardCharsets.UTF_8), total - units));
  }

  /** A binary value: as it is up to {@link Ejson#MAX_BINARY} bytes, else a description read from its header. */
  private BsonValue binary() {
    int start = position();
    int length = bytes.getInt(start);
    byte subtype = bytes.get(start + 4);
    if (subtype == 2) {
      // The old binary subtype repeats the length inside the data; the driver's data leaves it out.
      length -= 4;
    }
    if (length <= Ejson.MAX_BINARY) {
      return CODEC.decode(reader, CONTEXT);
    }
    reader.skipValue();
    cut++;
    return new BsonString("‹binary " + NumberFormat.getIntegerInstance(Locale.US).format(length) + " bytes, subtype "
        + HexFormat.of().toHexDigits(subtype) + "›");
  }

  private int position() {
    return reader.getBsonInput().getPosition();
  }
}
