package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.Limits;

/**
 * How much one answer may carry. The connector cuts its answer itself, at element boundaries, before it computes the
 * cursor: if the core's size limit cut it later, {@code nextCursor} would point past elements the agent never got.
 * The value appears twice (structured content and its text for clients that only read text), so each copy gets a
 * third of the response size; the rest is room for the masker and the core's fields.
 *
 * @param elements  at most this many elements or keys (the environment's row limit)
 * @param bytes     at most this many bytes of values in one copy of the answer
 * @param cellBytes at most this many bytes of one element (longer ones are cut with a marker)
 */
record Budget(int elements, long bytes, int cellBytes) {

  /** Bytes of JSON around one element (quotes, comma, a field name): a rough, conservative overhead. */
  static final int OVERHEAD = 16;

  static Budget of(Limits limits, int asked) {
    int rows = limits.maxRows() > 0 ? limits.maxRows() : asked;
    long size = limits.maxResponseBytes() > 0 ? limits.maxResponseBytes() : 512 * 1024;
    int cell = limits.maxCellChars() > 0 ? limits.maxCellChars() : 8 * 1024;
    return new Budget(Math.max(1, Math.min(asked, rows)), Math.max(4 * 1024, size / 3), Math.max(256, cell));
  }
}
