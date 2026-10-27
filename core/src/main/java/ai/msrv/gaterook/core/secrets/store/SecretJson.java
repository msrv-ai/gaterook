package ai.msrv.gaterook.core.secrets.store;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Reading secrets out of JSON without making {@code String}s of them (report 12 §6 "Process memory"): a store's
 * response body is a {@code byte[]}; the value of one key goes straight from the parser's buffer into a fresh
 * {@code byte[]} (a string's characters) or is copied as JSON text (anything else), and the caller zeroes the body.
 * Non-secret parts of a response (Vault's metadata, lease facts) are read as trees.
 */
public final class SecretJson {

  private static final JsonFactory FACTORY = JsonFactory.builder().build();
  /** Reads a subtree mid-document: what follows it (the parent's end) isn't "trailing" here. */
  private static final JsonMapper MAPPER = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private SecretJson() {}

  /**
   * What {@link #pick} found in one object.
   *
   * @param present whether the object exists (and isn't {@code null})
   * @param value   the key's value (a string as its UTF-8 bytes, anything else as JSON text), or the whole object as
   *                JSON when no key was asked for; {@code null} when the key is missing
   * @param string  whether the value was a JSON string
   * @param keys    the object's key names, in order
   */
  public record Picked(boolean present, byte[] value, boolean string, List<String> keys) {
    public void clear() {
      if (value != null) {
        Arrays.fill(value, (byte) 0);
      }
    }
  }

  /**
   * In {@code json}, the object reached through {@code path} (field names from the root): the value of {@code key},
   * or the object itself when {@code key} is {@code null}.
   *
   * @throws IllegalArgumentException when {@code json} isn't JSON
   */
  public static Picked pick(byte[] json, List<String> path, String key) {
    try (JsonParser p = FACTORY.createParser(ObjectReadContext.empty(), json)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return new Picked(false, null, false, List.of());
      }
      for (String step : path) {
        if (!seek(p, step) || p.nextToken() != JsonToken.START_OBJECT) {
          return new Picked(false, null, false, List.of());
        }
      }
      return readObject(p, key);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("The store's answer isn't valid JSON", null);
    }
  }

  /** The non-secret tree at {@code path} (an object or array), or a missing node. */
  public static JsonNode tree(byte[] json, List<String> path) {
    try (JsonParser p = FACTORY.createParser(ObjectReadContext.empty(), json)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return MissingNode.getInstance();
      }
      for (int i = 0; i < path.size(); i++) {
        if (!seek(p, path.get(i))) {
          return MissingNode.getInstance();
        }
        JsonToken t = p.nextToken();
        if (i < path.size() - 1 && t != JsonToken.START_OBJECT) {
          return MissingNode.getInstance();
        }
      }
      JsonNode n = MAPPER.readTree(p);
      return n == null ? MissingNode.getInstance() : n;
    } catch (RuntimeException e) {
      return MissingNode.getInstance();
    }
  }

  /** {@code value} parsed as a JSON object: the value of {@code key} (secret payloads of GCP and AWS). */
  public static Picked pickTop(byte[] value, String key) {
    return pick(value, List.of(), key);
  }

  /** Whether {@code value} is a JSON object (Test reference's "JSON with keys …"). */
  public static boolean isObject(byte[] value) {
    try (JsonParser p = FACTORY.createParser(ObjectReadContext.empty(), value)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        return false;
      }
      p.skipChildren();
      return p.nextToken() == null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** UTF-8 bytes of {@code chars} in a fresh array; the encoder's buffer is zeroed. */
  public static byte[] utf8(char[] chars, int offset, int length) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars, offset, length));
    byte[] out = new byte[encoded.remaining()];
    encoded.get(out);
    if (encoded.hasArray()) {
      Arrays.fill(encoded.array(), (byte) 0);
    }
    return out;
  }

  /** Moves the parser, inside an object, to the value of field {@code name}; false at the object's end. */
  private static boolean seek(JsonParser p, String name) {
    JsonToken t;
    while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
      if (name.equals(p.currentName())) {
        return true;
      }
      p.nextToken();
      p.skipChildren();
    }
    return false;
  }

  private static Picked readObject(JsonParser p, String key) {
    List<String> keys = new ArrayList<>();
    byte[] value = null;
    boolean string = false;
    if (key == null) {
      Zeroable out = new Zeroable();
      try (JsonGenerator g = FACTORY.createGenerator(ObjectWriteContext.empty(), out)) {
        g.writeStartObject();
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
          keys.add(p.currentName());
          g.writeName(p.currentName());
          p.nextToken();
          copy(p, g);
        }
        g.writeEndObject();
      }
      value = out.take();
      return new Picked(true, value, false, keys);
    }
    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
      String name = p.currentName();
      keys.add(name);
      JsonToken t = p.nextToken();
      if (!name.equals(key) || value != null) {
        p.skipChildren();
        continue;
      }
      if (t == JsonToken.VALUE_STRING) {
        value = utf8(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());
        string = true;
      } else if (t == JsonToken.VALUE_NULL) {
        value = null;
      } else {
        Zeroable out = new Zeroable();
        try (JsonGenerator g = FACTORY.createGenerator(ObjectWriteContext.empty(), out)) {
          copy(p, g);
        }
        value = out.take();
      }
    }
    return new Picked(true, value, string, keys);
  }

  /** Copies the current value; strings go character arrays to character arrays, never through a {@code String}. */
  private static void copy(JsonParser p, JsonGenerator g) {
    JsonToken t = p.currentToken();
    switch (t) {
      case START_OBJECT -> {
        g.writeStartObject();
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
          g.writeName(p.currentName());
          p.nextToken();
          copy(p, g);
        }
        g.writeEndObject();
      }
      case START_ARRAY -> {
        g.writeStartArray();
        while (p.nextToken() != JsonToken.END_ARRAY) {
          copy(p, g);
        }
        g.writeEndArray();
      }
      case VALUE_STRING -> g.writeString(p.getStringCharacters(), p.getStringOffset(), p.getStringLength());
      default -> g.copyCurrentEvent(p);
    }
  }

  /** A buffer whose bytes are zeroed when they are taken out. */
  private static final class Zeroable extends ByteArrayOutputStream {
    byte[] take() {
      byte[] out = toByteArray();
      Arrays.fill(buf, (byte) 0);
      count = 0;
      return out;
    }
  }
}
