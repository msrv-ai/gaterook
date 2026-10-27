package ai.msrv.gaterook.connector.api;

import tools.jackson.databind.JsonNode;

/**
 * One tool. {@code connection} in {@code inputSchema} is a placeholder: the core fills its enum per endpoint.
 * {@code outputSchema} only for pure READ tools: a tool that may answer "pending" can't promise a result shape.
 *
 * @param name            {@code ^[a-z][a-z0-9_]{0,63}$}
 * @param annotations     MCP annotations, always explicit (the MCP defaults are destructive and open world)
 * @param defaultMode     initial mode on dev/stage endpoints
 * @param prodDefaultMode initial mode on prod endpoints
 */
public record ToolDefinition(String name, String title, String description, JsonNode inputSchema,
    JsonNode outputSchema, Annotations annotations, Nature nature, Mode defaultMode, Mode prodDefaultMode,
    ResultShape resultShape, Risk risk) {

  /** {@code MIXED}: classify() decides per call (ssh_run, http_request). */
  public enum Nature { READ, MUTATION, MIXED }

  /** BYPASS explicitly permits execution without approval; policy denials and limits still apply. */
  public enum Mode { OFF, READ_ONLY, APPROVAL, BYPASS }

  public enum ResultShape { TABLE, JSON, TEXT, LOGS }

  /** Risk callout; {@code LIMITED} = fixed runbooks, enum/int/bool params only. */
  public enum Egress { NONE, LIMITED, CHANNEL }

  public record Annotations(Boolean readOnlyHint, Boolean destructiveHint, Boolean idempotentHint,
      Boolean openWorldHint) {

    public static Annotations read() {
      return new Annotations(true, false, true, false);
    }

    public static Annotations write(boolean destructive) {
      return new Annotations(false, destructive, false, false);
    }
  }

  public record Risk(boolean privateData, boolean untrustedContent, Egress egress) {}

  public boolean mutating() {
    return nature != Nature.READ;
  }

  /** Read only is offered, else natively disabled with a reason. */
  public boolean readOnlyModeAllowed() {
    return nature != Nature.MUTATION && !Boolean.TRUE.equals(annotations.destructiveHint());
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static final class Builder {
    private final String name;
    private String title;
    private String description = "";
    private JsonNode inputSchema;
    private JsonNode outputSchema;
    private Annotations annotations = Annotations.read();
    private Nature nature = Nature.READ;
    private Mode defaultMode = Mode.READ_ONLY;
    private Mode prodDefaultMode = Mode.READ_ONLY;
    private ResultShape resultShape = ResultShape.JSON;
    private Risk risk = new Risk(true, true, Egress.NONE);

    private Builder(String name) {
      this.name = name;
      this.title = name;
    }

    public Builder title(String v) {
      title = v;
      return this;
    }

    public Builder description(String v) {
      description = v;
      return this;
    }

    public Builder inputSchema(JsonNode v) {
      inputSchema = v;
      return this;
    }

    public Builder outputSchema(JsonNode v) {
      outputSchema = v;
      return this;
    }

    public Builder annotations(Annotations v) {
      annotations = v;
      return this;
    }

    public Builder nature(Nature v) {
      nature = v;
      return this;
    }

    public Builder defaultMode(Mode dev, Mode prod) {
      defaultMode = dev;
      prodDefaultMode = prod;
      return this;
    }

    public Builder resultShape(ResultShape v) {
      resultShape = v;
      return this;
    }

    public Builder risk(Risk v) {
      risk = v;
      return this;
    }

    public ToolDefinition build() {
      if (nature != Nature.READ && outputSchema != null) {
        throw new IllegalStateException(name + ": outputSchema only for READ tools");
      }
      if (nature == Nature.MUTATION && (defaultMode == Mode.READ_ONLY || prodDefaultMode == Mode.READ_ONLY)) {
        throw new IllegalStateException(name + ": a mutating tool can't default to Read only");
      }
      return new ToolDefinition(name, title, description, inputSchema, outputSchema, annotations, nature,
          defaultMode, prodDefaultMode, resultShape, risk);
    }
  }
}
