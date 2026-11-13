package com.gaterook.connector.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Help for the person who fills in a connection form ({@link Connector#suggest}): values found on this computer (the
 * contexts of a kubeconfig, AWS profiles, the hosts of {@code ~/.ssh/config}) or, once the draft can sign in, on the
 * service itself (namespaces, databases, regions, tables). Names, addresses and identifiers only: never a secret, and
 * never more than the person could already see with the credentials they typed.
 *
 * <p>The form offers each field's options under it and fills in {@link Field#value()} while the field is empty or
 * still holds its default. Nothing is saved by a suggestion: the person saves the form.
 *
 * @param fields what to offer, by the JSON Pointer of the config field ({@code /context},
 *     {@code /connection/database})
 * @param notes short sentences on what was found or what to do next ("Signed in to the cluster. 12 namespaces."), in
 *     order
 */
public record Suggestions(Map<String, Field> fields, List<String> notes) {

  /** The most options one field offers; a longer list is cut and marked incomplete. */
  public static final int MAX_OPTIONS = 200;

  public Suggestions {
    fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    notes = notes == null ? List.of() : List.copyOf(notes);
  }

  /** Where a call to {@link Connector#suggest} may look. */
  public enum Scope {
    /**
     * This computer only, while the person types: files of its command-line tools (kubeconfig, {@code ~/.aws/config},
     * {@code ~/.ssh/config}), the form's own values and typed secrets parsed in memory (a pasted kubeconfig, a service
     * account key). No network except loopback, no credential leaves the computer, no command runs.
     */
    LOCAL,
    /**
     * The service too: signs in with the draft's credentials, as Test connection does, and lists what the account
     * may use. Never asks for trust (an unknown host key or certificate, a credential command not yet allowed): it
     * returns what it has and a note instead.
     */
    SERVICE
  }

  /**
   * What one field is offered.
   *
   * @param options values to offer, the most likely first
   * @param value the value to fill in while the field is still empty or holds its default (the kubeconfig's current
   *     context, the region of the chosen profile), or {@code null}
   * @param source where the options come from, as the hint under the field names it ("your kubeconfig", "the
   *     cluster", "~/.aws/config")
   * @param complete {@code false} when more exist than are offered
   */
  public record Field(List<Option> options, String value, String source, boolean complete) {
    public Field {
      options = options == null ? List.of() : List.copyOf(options);
      if (options.size() > MAX_OPTIONS) {
        options = List.copyOf(options.subList(0, MAX_OPTIONS));
        complete = false;
      }
    }

    public static Field of(String source, List<Option> options) {
      return new Field(options, null, source, true);
    }

    public static Field value(String source, String value, List<Option> options) {
      return new Field(options, value, source, true);
    }
  }

  /**
   * One value to offer.
   *
   * @param value what goes into the field
   * @param label how it reads when it isn't the value itself ("Production · 1234-5678"), or {@code null}
   * @param detail one short fact about it (the server a context points to, a region), or {@code null}
   */
  public record Option(String value, String label, String detail) {
    public static Option of(String value) {
      return new Option(value, null, null);
    }

    public static Option of(String value, String detail) {
      return new Option(value, null, detail);
    }
  }

  public static Suggestions none() {
    return new Suggestions(Map.of(), List.of());
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isEmpty() {
    return fields.isEmpty() && notes.isEmpty();
  }

  /** These suggestions with {@code other}'s on top: its fields replace the same pointers, its notes come after. */
  public Suggestions merge(Suggestions other) {
    if (other == null || other.isEmpty()) {
      return this;
    }
    return builder().merge(this).merge(other).build();
  }

  /** Collects fields and notes in the order a form shows them. */
  public static final class Builder {
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    private Builder() {}

    public Builder field(String pointer, Field field) {
      if (pointer != null && field != null) {
        fields.put(pointer, field);
      }
      return this;
    }

    /** Options for a field; nothing when there are none. */
    public Builder options(String pointer, String source, List<Option> options) {
      return options == null || options.isEmpty() ? this : field(pointer, Field.of(source, options));
    }

    /** A value to fill in, offered with its alternatives. */
    public Builder value(String pointer, String source, String value, List<Option> options) {
      return value == null && (options == null || options.isEmpty()) ? this
          : field(pointer, Field.value(source, value, options));
    }

    public Builder note(String note) {
      if (note != null && !note.isBlank() && !notes.contains(note)) {
        notes.add(note);
      }
      return this;
    }

    public Builder merge(Suggestions other) {
      if (other != null) {
        other.fields().forEach(this::field);
        other.notes().forEach(this::note);
      }
      return this;
    }

    public Suggestions build() {
      return new Suggestions(fields, notes);
    }
  }
}
