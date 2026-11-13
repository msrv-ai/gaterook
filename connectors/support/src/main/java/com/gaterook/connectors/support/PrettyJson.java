package com.gaterook.connectors.support;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

/**
 * Indented JSON for what a person reads, such as the body of a change shown for approval. Lines end with
 * {@code \n} on every system: Jackson's default printer ends them with the system's separator, so the same request
 * would read differently on Windows.
 */
public final class PrettyJson {

  private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
      .withObjectIndenter(new DefaultIndenter("  ", "\n"));

  private PrettyJson() {}

  /** The mapper's writer with two-space indentation and {@code \n} line ends. */
  public static ObjectWriter writer(ObjectMapper mapper) {
    return mapper.writer().with(PRINTER);
  }
}
