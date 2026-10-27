package ai.msrv.gaterook.connectors.mongodb;

import java.text.NumberFormat;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.BsonJavaScript;
import org.bson.BsonString;
import org.bson.BsonSymbol;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.bson.codecs.BsonArrayCodec;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;
import org.bson.json.JsonMode;
import org.bson.json.JsonParseException;
import org.bson.json.JsonReader;
import org.bson.json.JsonWriterSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Extended JSON in and out (report 05 §3.1).
 *
 * <p>In: what the agent wrote — canonical or relaxed Extended JSON, or the mongosh literals the driver's reader knows
 * ({@code ObjectId('…')}, {@code ISODate('…')}, {@code NumberLong(…)}) — becomes one {@link BsonDocument}; the
 * validator and the driver get that same document, so duplicate keys and odd spellings mean exactly what was checked.
 * One value per argument, nothing after it, at most {@value #MAX_DEPTH} levels deep and {@value #MAX_CHARS}
 * characters long (the reader recurses, and a body of a million brackets must not take a thread's stack).
 *
 * <p>Out: relaxed Extended JSON with one fix: an Int64 outside ±2^53 is {@code {"$numberLong": "…"}} — relaxed
 * output prints it as a bare number, and JavaScript clients lose its last digits.
 */
final class Ejson {

  static final int MAX_DEPTH = 64;
  static final int MAX_CHARS = 100_000;
  /** Binary values longer than this are replaced by a description. */
  static final int MAX_BINARY = 256;

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final long SAFE_INTEGER = 1L << 53;

  /** Relaxed Extended JSON with exact Int64 outside ±2^53: what the agent and the approver read. */
  static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED)
      .int64Converter((value, writer) -> {
        if (value > SAFE_INTEGER || value < -SAFE_INTEGER) {
          writer.writeStartObject();
          writer.writeString("$numberLong", Long.toString(value));
          writer.writeEndObject();
        } else {
          writer.writeNumber(Long.toString(value));
        }
      })
      .build();

  /** Canonical Extended JSON: every BSON type spelled out, what an approved call runs again. */
  static final JsonWriterSettings CANONICAL = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

  private Ejson() {}

  /** One document from an argument: a JSON object as is, or a string in Extended JSON. */
  static BsonDocument document(JsonNode argument, String field) {
    if (argument == null || argument.isNull() || argument.isMissingNode()) {
      return new BsonDocument();
    }
    String text = argument.isString() ? argument.asString() : JSON.writeValueAsString(argument);
    if (!argument.isString() && !argument.isObject()) {
      throw new BadArgument(field, field + " must be an object or a string in Extended JSON.");
    }
    check(text, field);
    try {
      JsonReader reader = new JsonReader(text);
      BsonDocument doc = new BsonDocumentCodec().decode(reader, DecoderContext.builder().build());
      requireEnd(reader, field);
      return doc;
    } catch (JsonParseException | org.bson.BsonInvalidOperationException | IllegalArgumentException e) {
      throw new BadArgument(field, field + " isn't Extended JSON: " + firstLine(e.getMessage()));
    } catch (StackOverflowError e) {
      // check() keeps the reader within MAX_DEPTH; this is the backstop should a token ever escape it.
      throw tooDeep(field);
    }
  }

  /** The stages of a pipeline argument (a JSON array of objects). */
  static BsonArray array(JsonNode argument, String field) {
    if (argument == null || !argument.isArray()) {
      throw new BadArgument(field, field + " must be an array of stages.");
    }
    String text = JSON.writeValueAsString(argument);
    check(text, field);
    try {
      JsonReader reader = new JsonReader(text);
      BsonArray array = new BsonArrayCodec().decode(reader, DecoderContext.builder().build());
      requireEnd(reader, field);
      return array;
    } catch (JsonParseException | org.bson.BsonInvalidOperationException | IllegalArgumentException e) {
      throw new BadArgument(field, field + " isn't Extended JSON: " + firstLine(e.getMessage()));
    } catch (StackOverflowError e) {
      throw tooDeep(field);
    }
  }

  /** The text a person approves: relaxed Extended JSON of the command, exactly as it will be sent. */
  static String relaxed(BsonDocument doc) {
    return doc.toJson(RELAXED);
  }

  static String canonical(BsonValue value) {
    if (value instanceof BsonDocument d) {
      return d.toJson(CANONICAL);
    }
    BsonDocument wrapper = new BsonDocument("v", value);
    String json = wrapper.toJson(CANONICAL);
    // {"v": <value>} → <value>
    return json.substring(json.indexOf(':') + 1, json.length() - 1).strip();
  }

  /** A document for the agent: relaxed Extended JSON as a Jackson tree. */
  static JsonNode tree(BsonDocument doc) {
    return JSON.readTree(doc.toJson(RELAXED));
  }

  /**
   * Cuts what is too long before anything is converted: strings (and symbols, code) to {@code maxChars} with
   * {@code …‹truncated 12,345 chars›}, binary values longer than {@value #MAX_BINARY} bytes to a description. Returns
   * how many values were cut.
   */
  static int shrink(BsonValue value, int maxChars) {
    int cut = 0;
    if (value instanceof BsonDocument d) {
      for (Map.Entry<String, BsonValue> e : d.entrySet()) {
        BsonValue v = e.getValue();
        BsonValue replaced = replacement(v, maxChars);
        if (replaced != null) {
          e.setValue(replaced);
          cut++;
        } else {
          cut += shrink(v, maxChars);
        }
      }
    } else if (value instanceof BsonArray a) {
      for (int i = 0; i < a.size(); i++) {
        BsonValue v = a.get(i);
        BsonValue replaced = replacement(v, maxChars);
        if (replaced != null) {
          a.set(i, replaced);
          cut++;
        } else {
          cut += shrink(v, maxChars);
        }
      }
    }
    return cut;
  }

  private static BsonValue replacement(BsonValue v, int maxChars) {
    if (v.getBsonType() == BsonType.STRING) {
      String s = ((BsonString) v).getValue();
      return s.length() > maxChars ? new BsonString(cut(s, maxChars)) : null;
    }
    if (v.getBsonType() == BsonType.SYMBOL) {
      String s = ((BsonSymbol) v).getSymbol();
      return s.length() > maxChars ? new BsonString(cut(s, maxChars)) : null;
    }
    if (v.getBsonType() == BsonType.JAVASCRIPT) {
      String s = ((BsonJavaScript) v).getCode();
      return s.length() > maxChars ? new BsonString(cut(s, maxChars)) : null;
    }
    if (v.getBsonType() == BsonType.BINARY) {
      BsonBinary b = (BsonBinary) v;
      if (b.getData().length > MAX_BINARY) {
        return new BsonString("‹binary " + NumberFormat.getIntegerInstance(Locale.US).format(b.getData().length)
            + " bytes, subtype " + HexFormat.of().toHexDigits(b.getType()) + "›");
      }
    }
    return null;
  }

  private static String cut(String s, int maxChars) {
    int end = maxChars;
    if (Character.isHighSurrogate(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(0, end) + "…‹truncated " + NumberFormat.getIntegerInstance(Locale.US)
        .format(s.length() - end) + " chars›";
  }

  /**
   * Length and depth before the recursive reader sees the text. The tokens are the driver's scanner's: strings of
   * either quote and regular expressions ({@code /…/flags}) are skipped with their backslash escapes — a quote inside
   * {@code /"/} must not hide the brackets after it, or a body of 50,000 brackets would take the reader's stack.
   */
  private static void check(String text, String field) {
    if (text.length() > MAX_CHARS) {
      throw new BadArgument(field, field + " is longer than " + NumberFormat.getIntegerInstance(Locale.US)
          .format(MAX_CHARS) + " characters.");
    }
    int depth = 0;
    int max = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\'' || c == '/') {
        // Strings of either quote and regular expressions, with backslash escapes, as the driver's scanner reads them.
        for (i++; i < text.length() && text.charAt(i) != c; i++) {
          if (text.charAt(i) == '\\') {
            i++;
          }
        }
      } else if (c == '{' || c == '[') {
        depth++;
        max = Math.max(max, depth);
      } else if (c == '}' || c == ']') {
        depth--;
      }
    }
    if (max > MAX_DEPTH) {
      throw tooDeep(field);
    }
  }

  private static BadArgument tooDeep(String field) {
    return new BadArgument(field, field + " is nested deeper than " + MAX_DEPTH + " levels.");
  }

  private static void requireEnd(JsonReader reader, String field) {
    BsonType next;
    try {
      next = reader.readBsonType();
    } catch (JsonParseException e) {
      throw new BadArgument(field, field + " has text after its value.");
    }
    if (next != BsonType.END_OF_DOCUMENT) {
      throw new BadArgument(field, field + " has text after its value.");
    }
  }

  static String firstLine(String message) {
    if (message == null) {
      return "it doesn't parse.";
    }
    int nl = message.indexOf('\n');
    return nl < 0 ? message : message.substring(0, nl);
  }

  /** An argument the gateway can't use: DENY {@code mongodb.bad-argument} in classify, BAD_INPUT in execute. */
  static final class BadArgument extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String field;

    BadArgument(String field, String message) {
      super(message, null, false, false);
      this.field = field;
    }
  }
}
