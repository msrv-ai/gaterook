package com.gaterook.connectors.azure;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * What a blob, a file or a message read as text may be, the same rules as {@code gcp_storage_read}: a text content
 * type, or a text file name when the type says nothing; never a file that holds keys, credentials or Terraform state
 * more often than not; decoded as UTF-8 without a sequence cut at the end; refused when it holds control characters.
 */
final class Texts {

  /** Files that hold keys and credentials more often than not: refused by name before any request. */
  static final Pattern SENSITIVE_NAME = Pattern.compile("(?i)((^|/)(\\.env(\\.[^/]*)?|\\.netrc|\\.npmrc"
      + "|\\.pypirc|\\.git-credentials|id_(rsa|dsa|ecdsa|ed25519)|credentials(\\.json)?)"
      + "|\\.(pem|key|p12|pfx|jks|keystore|tfstate|tfstate\\.backup|kubeconfig|publishsettings))$");
  private static final Pattern TEXT_TYPE = Pattern.compile("(?i)^(text/[^;]+|application/(json|xml|yaml|x-yaml"
      + "|javascript|x-ndjson|ndjson|csv|x-csv|sql|toml|x-sh|graphql|[a-z0-9.+-]+\\+(json|xml)))\\s*(;.*)?$");
  private static final Pattern TEXT_NAME = Pattern.compile("(?i)\\.(txt|log|json|ndjson|jsonl|csv|tsv|yaml|yml|xml"
      + "|md|sql|ini|cfg|conf|toml|html?|js|ts|py|java|go|rb|sh|properties)$");
  /** Content types that say nothing about the bytes; the file name decides then. */
  private static final Pattern UNSPECIFIC = Pattern.compile("(?i)^(|application/octet-stream|binary/octet-stream)"
      + "\\s*(;.*)?$");

  private Texts() {}

  static boolean sensitiveName(String name) {
    return SENSITIVE_NAME.matcher(name).find();
  }

  /** Whether an object of {@code contentType} named {@code name} reads as text. */
  static boolean text(String contentType, String name) {
    String type = contentType == null ? "" : contentType;
    return TEXT_TYPE.matcher(type).matches() || UNSPECIFIC.matcher(type).matches() && TEXT_NAME.matcher(name).find();
  }

  /** The length of {@code bytes} without a UTF-8 sequence cut at the end. */
  static int completeUtf8(byte[] bytes) {
    int end = bytes.length;
    int i = end - 1;
    int continuation = 0;
    while (i >= 0 && continuation < 3 && (bytes[i] & 0xC0) == 0x80) {
      i--;
      continuation++;
    }
    if (i < 0) {
      return end;
    }
    int lead = bytes[i] & 0xFF;
    int need = lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : 0;
    return need > continuation ? i : end;
  }

  static String decode(byte[] bytes, int length) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    } catch (CharacterCodingException e) {
      return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
  }

  /** UTF-8 that decodes without a replacement character, or {@code null}. */
  static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  /** NUL, or more than one in a hundred characters a control character other than whitespace: not text. */
  static boolean binary(String text) {
    if (text.isEmpty()) {
      return false;
    }
    int control = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == 0) {
        return true;
      }
      if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
        control++;
      }
    }
    return control * 100 > text.length();
  }

  /** {@code text} cut to at most {@code max} UTF-8 bytes, without a sequence cut. */
  static String cut(String text, int max) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= max) {
      return text;
    }
    byte[] head = java.util.Arrays.copyOf(bytes, max);
    return decode(head, completeUtf8(head));
  }
}
