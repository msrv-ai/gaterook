package ai.msrv.gaterook.connectors.firebase;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Firestore field paths: field names joined by dots, {@code address.city}; a name that isn't a simple identifier is
 * written in backticks, {@code `first-name`}, with {@code \`} and {@code \\} inside. What an agent writes is read
 * strictly and given back canonical: simple names as they are, every other name quoted — a name such as
 * {@code first-name} may be written without backticks and is quoted here. {@code __name__} is the document's name.
 */
final class FieldPaths {

  static final String NAME = "__name__";
  /** Firestore's longest field path. */
  static final int MAX_BYTES = 1_500;
  /** Firestore nests maps 20 deep. */
  static final int MAX_DEPTH = 20;
  private static final Pattern SIMPLE = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
  private static final Pattern RESERVED = Pattern.compile("^__.*__$", Pattern.DOTALL);

  private FieldPaths() {}

  /** The canonical path of what an agent wrote. */
  static String canonical(String path) throws Refusal {
    return join(segments(path));
  }

  /** The names of {@code path}, unquoted: what to look up in a document's fields. */
  static List<String> segments(String path) throws Refusal {
    if (path == null || path.isEmpty()) {
      throw Refusal.bad("A field path is empty.", "Name a field such as status or address.city.");
    }
    if (Names.utf8Length(path) > MAX_BYTES) {
      throw Refusal.bad("A field path is longer than Firestore allows.", null);
    }
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = path.length();
    while (i < n) {
      StringBuilder name = new StringBuilder();
      if (path.charAt(i) == '`') {
        i++;
        while (i < n && path.charAt(i) != '`') {
          char c = path.charAt(i);
          if (c == '\\') {
            if (i + 1 >= n || (path.charAt(i + 1) != '`' && path.charAt(i + 1) != '\\')) {
              throw bad(path, "has a backslash that escapes neither ` nor \\");
            }
            c = path.charAt(++i);
          }
          name.append(c);
          i++;
        }
        if (i >= n) {
          throw bad(path, "opens a backtick it doesn't close");
        }
        i++;
        if (i < n && path.charAt(i) != '.') {
          throw bad(path, "goes on after a closing backtick without a dot");
        }
      } else {
        int dot = path.indexOf('.', i);
        int end = dot < 0 ? n : dot;
        String plain = path.substring(i, end);
        if (plain.indexOf('`') >= 0 || plain.indexOf('\\') >= 0) {
          throw bad(path, "has a backtick or a backslash outside a quoted name");
        }
        name.append(plain);
        i = end;
      }
      String problem = problem(name.toString());
      if (problem != null) {
        throw bad(path, "has a name that " + problem);
      }
      out.add(name.toString());
      if (i < n) {
        i++;
        if (i == n) {
          throw bad(path, "ends with a dot");
        }
      }
    }
    if (out.size() > MAX_DEPTH) {
      throw bad(path, "is deeper than Firestore nests fields");
    }
    if (out.contains(NAME) && out.size() > 1) {
      throw bad(path, "uses __name__ inside a path");
    }
    return List.copyOf(out);
  }

  /** Why {@code name} can't be a field name, or {@code null}. {@code __name__} itself is allowed here. */
  static String problem(String name) {
    if (name.isEmpty()) {
      return "is empty";
    }
    for (int i = 0; i < name.length(); ) {
      int cp = name.codePointAt(i);
      if (Character.isSurrogate(name.charAt(i)) && Character.charCount(cp) == 1) {
        return "holds a broken character";
      }
      if (cp < 0x20 || cp == 0x7f || (cp >= 0x80 && cp <= 0x9f)) {
        return "holds a control character";
      }
      int type = Character.getType(cp);
      if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
        return "holds an invisible formatting character";
      }
      i += Character.charCount(cp);
    }
    if (!name.equals(NAME) && RESERVED.matcher(name).matches()) {
      return "is reserved by Firestore (__…__)";
    }
    return null;
  }

  /** One field name as one segment of a path: as it is when simple, else in backticks. */
  static String quote(String name) {
    if (SIMPLE.matcher(name).matches()) {
      return name;
    }
    return "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`";
  }

  static String join(List<String> names) {
    List<String> quoted = new ArrayList<>(names.size());
    names.forEach(n -> quoted.add(quote(n)));
    return String.join(".", quoted);
  }

  private static Refusal bad(String path, String problem) {
    return Refusal.bad("The field path " + Names.shown(path) + " " + problem + ".",
        "Join field names with dots, such as address.city; quote a name with dots in backticks: `a.b`.");
  }
}
