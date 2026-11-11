package ai.msrv.gaterook.connectors.firebase;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A path under a Firestore database's documents, checked: collection and document IDs in turn, {@code users},
 * {@code users/alice}, {@code users/alice/orders}; the root has none. An ID is what Firestore allows (at most 1,500
 * bytes, not {@code .} or {@code ..}, not {@code __reserved__}) and what {@link Names} lets into a URL. One leading
 * slash is ignored; an empty segment or a trailing slash is refused.
 */
record FirestorePath(List<String> segments) {

  static final int MAX_ID_BYTES = 1_500;
  /** Firestore nests subcollections 100 deep: 200 IDs; a path an agent writes is checked against that. */
  static final int MAX_SEGMENTS = 200;
  /** Firestore's reserved IDs. */
  private static final Pattern RESERVED = Pattern.compile("^__.*__$", Pattern.DOTALL);

  static final FirestorePath ROOT = new FirestorePath(List.of());

  FirestorePath {
    segments = List.copyOf(segments);
  }

  /** The path {@code text}, checked; blank is the root. */
  static FirestorePath parse(String text) throws Refusal {
    if (text == null || text.isEmpty() || text.equals("/")) {
      return ROOT;
    }
    String t = text.startsWith("/") ? text.substring(1) : text;
    if (t.endsWith("/")) {
      throw Refusal.bad("The path " + Names.shown(text) + " ends with a slash.", "Write it as users/alice.");
    }
    List<String> out = new ArrayList<>();
    for (String id : t.split("/", -1)) {
      String problem = problem(id);
      if (problem != null) {
        throw Refusal.bad("The path " + Names.shown(text) + " has an ID that " + problem + ".",
            "Write collection and document IDs separated by /, such as users/alice/orders/o-1.");
      }
      out.add(id);
    }
    if (out.size() > MAX_SEGMENTS) {
      throw Refusal.bad("The path is deeper than Firestore allows.", null);
    }
    return new FirestorePath(out);
  }

  /** Why {@code id} can't be a collection or document ID, or {@code null}. */
  static String problem(String id) {
    if (id.equals(".") || id.equals("..")) {
      return "is a dot segment";
    }
    String problem = Names.problem(id, "", MAX_ID_BYTES);
    if (problem != null) {
      return problem;
    }
    return RESERVED.matcher(id).matches() ? "is reserved by Firestore (__…__)" : null;
  }

  boolean root() {
    return segments.isEmpty();
  }

  /** An even number of IDs: collection, document, … */
  boolean document() {
    return !segments.isEmpty() && segments.size() % 2 == 0;
  }

  boolean collection() {
    return segments.size() % 2 == 1;
  }

  /** The last ID. */
  String id() {
    return segments.getLast();
  }

  FirestorePath parent() {
    return new FirestorePath(segments.subList(0, segments.size() - 1));
  }

  FirestorePath child(String id) {
    List<String> out = new ArrayList<>(segments);
    out.add(id);
    return new FirestorePath(out);
  }

  /** {@code users/alice}; the root is empty. */
  String text() {
    return String.join("/", segments);
  }

  /** The IDs percent-encoded, joined by {@code /}. */
  String encoded() {
    List<String> out = new ArrayList<>(segments.size());
    segments.forEach(s -> out.add(Names.encode(s)));
    return String.join("/", out);
  }

  /** The full name of this path in {@code documents} ({@code projects/p/databases/d/documents}). */
  String name(String documents) {
    return root() ? documents : documents + "/" + text();
  }

  @Override
  public String toString() {
    return text();
  }
}
